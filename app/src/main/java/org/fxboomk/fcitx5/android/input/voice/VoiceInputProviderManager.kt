/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.fxboomk.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.EditorSessionTracker
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceHostPermissions
import org.fxboomk.fcitx5.android.ui.main.settings.voice.JsVoiceHostPermissionDialog
import org.fxboomk.fcitx5.android.utils.appContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

data class VoiceInputProviderInfo(
    val id: String,
    val label: CharSequence,
    val packageName: String,
    val serviceName: String,
    val action: String,
)

object VoiceInputProviderManager {
    const val ID_PREFIX = "plugin:"
    private const val TAG = "FcitxVoiceInput"

    // 3-second post-speech silence ⇒ provider emits a final segment.
    private const val DEFAULT_SILENCE_MS = 3000L
    private const val KEEPALIVE_MIN_INTERVAL_MS = 10_000L
    private const val KEEPALIVE_BIND_TIMEOUT_MS = 2_000L
    /** Hard deadline for `onServiceConnected` to fire after `bindService` returns
     *  true for an active voice session. If the plugin process hangs during startup
     *  (e.g. model init deadlocks), the IME must not stay stuck in "connecting". */
    private const val ACTIVE_BIND_TIMEOUT_MS = 8_000L
    /** Hard deadline for `onReady` to fire after the provider connected. Providers
     *  load their model asynchronously (the binder `startSession` returns before the
     *  model is ready), so `onServiceConnected` firing does not mean the session is
     *  usable. Without this guard the IME stays stuck in "connecting"/"loading" forever
     *  when a cold-start model load hangs or never emits onReady. On-device model loads
     *  are normally well under this; the retry below covers a one-off stall. */
    private const val ACTIVE_READY_TIMEOUT_MS = 12_000L
    /** Total bind attempts for one voice session (initial + auto-retries). On a ready
     *  timeout the IME rebinds once, which makes the provider bump its session
     *  generation and reload — often recovering a one-off stuck cold start. */
    private const val MAX_BIND_ATTEMPTS = 2
    private const val WARM_CONNECTION_HOLD_MS = 5 * 60 * 1000L

    // Upper bound for draining the in-flight PCM queue before sending endStream.
    // Why: avoid tail-end word loss if the provider hangs in feedAudio.
    private const val FEED_DRAIN_TIMEOUT_MS = 1_000L

    /**
     * How long to wait for the provider to acknowledge endStream before forcing teardown.
     *
     * After endStream the session only ends when the provider calls back onSessionEnded or
     * onError. A provider that dies or simply never answers left `finishing = true` forever,
     * which made [toggle] refuse every later press: the microphone button was dead until the
     * IME process restarted.
     */
    private const val FINISH_WATCHDOG_MS = 3_000L

    // Pre-roll window: capture starts immediately on toggle so audio spoken
    // before the provider reports onReady is replayed once the session opens.
    // Eliminates head-end word loss from cold-bind + model-load latency.
    private const val PRE_ROLL_MAX_MS = 3_000L

    /**
     * 语音 PCM 队列最多能缓存多少毫秒音频。容量由它换算成包数，而不是拍一个包数或字节数。
     *
     * 为什么按时长：采集层固定 100ms 一包（见 [VoiceInputAudioCapture]，按 `sampleRate / 10`
     * 采样切包），采样格式一变（provider 可用 preferredConfig 指定采样率），同样包数代表的
     * 延迟就变了；而积压超过几百毫秒后，识别结果已经不是用户此刻说的那句话（高延迟识别）。
     * 取 3 秒与预滚窗口 [PRE_ROLL_MAX_MS] 一致——会话能容忍的"最老音频"上限就是 3 秒。
     *
     * 内存上界（换算见 [VoicePcmQueueBudget]）：默认 16kHz/16bit/单声道 = 3200 B/包，
     * 31 包 ≈ 97KB，与预滚缓冲同量级。改前是 Channel.UNLIMITED，堆按会话时长线性增长
     * （≈32KB/s，外加包数组与队列节点开销），长会话可能 GC 抖动甚至 OOM。
     */
    private const val MAX_BUFFERED_AUDIO_MS = 3_000L

    /** 队列容量（包数）。生产端 [offerAudioPacket] 用它判定过载。 */
    private val MAX_BUFFERED_AUDIO_PACKETS =
        VoicePcmQueueBudget.capacityPackets(MAX_BUFFERED_AUDIO_MS)

    /**
     * 预滚缓冲的硬上限（包数），与 [PRE_ROLL_MAX_MS] 同一预算推导。
     *
     * 预滚本身是滚动窗口、天然淘汰最旧的包（见 [startPreRollCapture]），正常路径下这个
     * 上限不会被触及；它只兜住 pts 不再前进的病态情况（例如系统时钟被回拨，
     * `pts - 队首.pts` 永远超不过窗口）。
     */
    private val PRE_ROLL_MAX_PACKETS = VoicePcmQueueBudget.capacityPackets(PRE_ROLL_MAX_MS)

    /** 单次 Binder feedAudio 超过该耗时即视为 provider 偏慢（记入统计并限频告警）。 */
    private const val FEED_SLOW_WARN_MS = 200L

    /** 慢调用告警的限频间隔，避免"provider 慢"自己制造日志风暴。 */
    private const val SLOW_FEED_WARN_INTERVAL_MS = 1_000L

    /**
     * 队列满时报告给调用方的失败原因（provider-too-slow 语义）。
     *
     * 复用既有的 provider→onError 回调链，与 [launchAudioFeedJob] 里 Binder 死亡时写的
     * "Provider disconnected" 同一形式；字符串最终会作为 Toast / 语音状态提示展示。
     * 注意：voice_error_* 的本地化文案在 res/ 下，本次改动范围只允许 input/voice/，
     * 所以沿用本文件已有的字面量写法，精确原因同时写进 release 可见的 Timber 日志。
     */
    private const val PROVIDER_TOO_SLOW_MESSAGE = "Provider too slow: voice PCM queue full"

    /** 本次会话的 PCM 队列统计：深度高水位、最旧包年龄、feedAudio 耗时、过载次数。 */
    private val pcmQueueMetrics = VoicePcmQueueMetrics()

    /**
     * 已经因过载中止过的 [sessionGeneration]，保证同一会话只中止一次
     * （队列满之后采集线程还可能投递一两个包）。
     */
    private val overflowAbortGeneration = AtomicInteger(-1)

    /** 慢调用告警限频时间戳；只在 feed 协程（单消费者）里读写。 */
    private var lastSlowFeedWarnElapsed = 0L

    private val actions = buildSet {
        val appId = BuildConfig.APPLICATION_ID
        add(appId)
        val releaseLikeId = appId.removeSuffix(".debug")
        add(releaseLikeId)
        add("$releaseLikeId.debug")
        add("org.fxboomk.fcitx5.android")
        add("org.fxboomk.fcitx5.android.debug")
        // Compatibility with AIDL voice providers written for upstream Fcitx5 Android.
        // These lines match the original package actions already declared in the manifest
        // <queries> block; without them Package Visibility hides the provider service.
        add("org.fcitx.fcitx5.android")
        add("org.fcitx.fcitx5.android.debug")
        add("com.fxliang.foxy")
        add("com.fxliang.foxy.debug")
    }.map { it + VoiceInputIpc.SERVICE_ACTION_SUFFIX }

    private var activeConnection: ServiceConnection? = null
    private var activeProvider: IVoiceInputProvider? = null
    private val providerDeathLink = VoiceProviderDeathLink()
    private var activeCallback: IVoiceInputCallback? = null
    private var activeEditorSession: EditorSessionTracker.Token? = null
    private val floatingResultGate = FloatingVoiceResultGate()

    internal fun deliverFloatingResult(token: String?, final: Boolean, deliver: () -> Unit): Boolean =
        floatingFallbackActive && floatingResultGate.deliver(token, final) {
            if (final) stopFloatingFallback(appContext, token)
            deliver()
        }
    private var activeCapture: VoiceInputAudioCapture? = null
    private var activeAudioFeedJob: Job? = null
    @Volatile private var activeAudioFeedQueue: Channel<QueuedAudio>? = null
    private var activeSessionConfig = SessionConfig()
    private var activeProviderId: String? = null
    /**
     * Volatile like the other session flags: [armFinishWatchdog] reads it from a coroutine and
     * the binder callbacks clear it, so it must not be cached per thread.
     */
    @Volatile
    private var finishing = false
    private var sessionActive = false
    @Volatile private var sessionReady = false
    private var floatingFallbackActive = false
    private var floatingFallbackComponent: ComponentName? = null
    private var warmConnectionReleaseGeneration = 0
    private var keepAliveConnection: ServiceConnection? = null
    private var keepAliveLastAttemptElapsed = 0L
    @Volatile private var voiceSessionTerminalized = false

    /**
     * Monotonic id of the current session, bumped on every start.
     *
     * The finish watchdog captures it so a late fire cannot tear down a session the user
     * started in the meantime. Volatile for the same reason as [finishing].
     */
    @Volatile
    private var sessionGeneration = 0

    private fun isCurrentVoiceSession(generation: Int, callback: IVoiceInputCallback?): Boolean =
        generation == sessionGeneration && callback != null && activeCallback === callback &&
            sessionActive && !voiceSessionTerminalized && activeEditorSession?.isCurrent() == true

    /** Watchdog for the endStream -> onSessionEnded handshake; see [FINISH_WATCHDOG_MS]. */
    private var finishWatchdogJob: Job? = null

    /**
     * [sessionGeneration] whose onReady has already been handled.
     *
     * A provider may report onReady more than once per session. The duplicate was handled for the
     * "pre-roll config matches" branch only; on the other branch it stopped the capture that was
     * already recording and built a new AudioRecord, restarting the microphone mid-session and
     * discarding whatever had been buffered (see C4).
     */
    private var readyHandledGeneration = -1
    @Volatile private var preRollActive: Boolean = false
    private val preRollBuffer = ArrayDeque<QueuedAudio>()
    private val preRollLock = Any()
    var floatingCommitListener: ((String) -> Unit)? = null
    // Default UI callbacks for voice status, shared across all toggle() callers.
    var voiceStatusCallback: ((String) -> Unit)? = null    // show/hide/update bar text
    var voiceLevelCallback: ((Int) -> Unit)? = null        // volume level
    var voiceReadyCallback: (() -> Unit)? = null           // set "listening" status
    var voiceFinishedCallback: (() -> Unit)? = null        // hide status on finish
    var voiceErrorCallback: ((String) -> Unit)? = null     // show error toast
    // Layout preservation around a voice session (see KeyboardWindow.onVoiceInputStarted/Finished).
    var sessionStartedCallback: (() -> Unit)? = null
    var sessionEndedCallback: (() -> Unit)? = null

    /**
     * Identifies the owner of the UI callbacks above, so a stale owner cannot clear the
     * callbacks of a newer one.
     *
     * These fields live on a process-scoped object but the lambdas capture an InputView's
     * views. Every InputView recreation (theme change, rotation, a `recreateInputView` pref)
     * used to overwrite them and leak the previous view tree, and — worse — a torn-down
     * component clearing them unconditionally would silence the *current* keyboard: voice
     * status and level feedback simply stopped appearing.
     */
    @Volatile
    private var uiCallbackOwner: Any? = null

    /**
     * Install the shared voice UI callbacks on behalf of [owner].
     *
     * Pass the same [owner] to [releaseUiCallbacks] on teardown. A null argument leaves that
     * particular callback untouched.
     */
    fun setUiCallbacks(
        owner: Any,
        status: ((String) -> Unit)? = null,
        level: ((Int) -> Unit)? = null,
        ready: (() -> Unit)? = null,
        finished: (() -> Unit)? = null,
        error: ((String) -> Unit)? = null,
        sessionStarted: (() -> Unit)? = null,
        sessionEnded: (() -> Unit)? = null,
        floatingCommit: ((String) -> Unit)? = null
    ) {
        uiCallbackOwner = owner
        status?.let { voiceStatusCallback = it }
        level?.let { voiceLevelCallback = it }
        ready?.let { voiceReadyCallback = it }
        finished?.let { voiceFinishedCallback = it }
        error?.let { voiceErrorCallback = it }
        sessionStarted?.let { sessionStartedCallback = it }
        sessionEnded?.let { sessionEndedCallback = it }
        floatingCommit?.let { floatingCommitListener = it }
    }

    /**
     * Drop the shared voice UI callbacks, but only if [owner] still owns them.
     *
     * The identity check matters during an InputView swap: the new component installs its
     * callbacks before the old one is detached, so an unconditional clear would wipe the live
     * ones and leave the new keyboard with no voice feedback at all.
     */
    fun releaseUiCallbacks(owner: Any) {
        if (uiCallbackOwner !== owner) return
        uiCallbackOwner = null
        voiceStatusCallback = null
        voiceLevelCallback = null
        voiceReadyCallback = null
        voiceFinishedCallback = null
        voiceErrorCallback = null
        sessionStartedCallback = null
        sessionEndedCallback = null
        floatingCommitListener = null
    }

    private data class QueuedAudio(
        val pcm: ByteArray,
        val ptsMs: Long,
    )

    // Verbose tracing is debug-only. Timber still records important warnings
    // in release, so user-reported issues remain debuggable via the file log.
    // R8 folds away the call in release builds where BuildConfig.DEBUG is false.
    private fun logI(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.i(TAG, msg)
    }

    private fun logW(msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, msg)
    }

    private fun logW(msg: String, e: Throwable) {
        if (BuildConfig.DEBUG) android.util.Log.w(TAG, msg, e)
    }

    // Timber.i is verbose tracing of normal flow; gate it like logI so release
    // builds don't pay the string-template + tree-iteration cost. Timber.w
    // stays unconditional — release file logs need warnings for bug reports.
    private fun tlogI(msg: String) {
        if (BuildConfig.DEBUG) Timber.i(msg)
    }

    fun isActive(): Boolean =
        floatingFallbackActive || sessionActive || activeCallback != null || activeCapture != null

    /**
     * A toolbar tap always means "toggle": while a session exists it must end
     * the session rather than start a second request. Kept as a pure helper so
     * the UI decision is testable without Android services.
     */
    fun shouldStopOnTap(isActive: Boolean): Boolean = isActive

    fun hasAudioPermission(context: Context = appContext): Boolean =
        VoiceInputAudioCapture.hasPermission(context)

    // Toggle helper for UI buttons that should "stop if running, start otherwise".
    // Returns true if a new session was started, false if the previous one was stopped.
    fun toggle(
        service: FcitxInputMethodService,
        id: String,
        onReady: () -> Unit = { voiceReadyCallback?.invoke() },
        onPartialResult: (String) -> Unit = {},
        onError: (String) -> Unit = { voiceErrorCallback?.invoke(it) },
        onLevel: (Int) -> Unit = { voiceLevelCallback?.invoke(it) },
        onFinished: () -> Unit = { voiceFinishedCallback?.invoke() },
        onStatus: (String) -> Unit = { voiceStatusCallback?.invoke(it) },
    ): Boolean {
        if (isActive()) {
            if (floatingFallbackActive) {
                stopFloatingFallback(service)
                return false
            }
            if (finishing) {
                logI("toggle ignored: voice input is finishing")
                return false
            }
            finish(service)
            return false
        }
        val spec = JsVoiceProviders.specOf(id)
        if (spec != null && !JsVoiceHostPermissions.isApproved(id, spec.hosts)) {
            // Consent never starts recording. A hold key may already have been released
            // while the dialog was open; the next explicit press starts the session.
            service.showDialog(JsVoiceHostPermissionDialog.build(service, spec, spec.hosts) {
                android.widget.Toast.makeText(service, org.fxboomk.fcitx5.android.R.string.js_voice_network_approved_hint,
                    android.widget.Toast.LENGTH_SHORT).show()
            })
            return false
        }
        start(service, id, onReady, onPartialResult, onError, onLevel, onFinished, onStatus)
        return true
    }

    fun listProviders(context: Context = appContext): List<VoiceInputProviderInfo> {
        JsVoiceProviders.install()
        val registered = VoiceInputProviderRegistry.all().map {
            VoiceInputProviderInfo(
                id = it.id,
                label = it.label,
                packageName = it.packageName,
                serviceName = "",
                action = "registered",
            )
        }
        val services = actions.flatMap { action ->
            val intent = Intent(action)
            val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, 0)
            }
            results.map { action to it }
        }
        val queried = services.mapNotNull { (action, info) ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
            VoiceInputProviderInfo(
                id = ID_PREFIX + component.flattenToShortString(),
                label = serviceInfo.loadLabel(context.packageManager),
                packageName = serviceInfo.packageName,
                serviceName = serviceInfo.name,
                action = action,
            )
        }
        return (registered + queried).distinctBy { it.id }
            .sortedBy { it.label.toString() }
            .also {
                logI(
                    "Detected voice input providers: " +
                        it.joinToString { p ->
                            "${p.id}@${p.action}[${p.packageName}/${p.serviceName}]"
                        }
                )
                tlogI(
                    "Detected voice input providers: " +
                        it.joinToString { p -> "${p.id}@${p.action}" }
                )
            }
    }

    /**
     * 进程内插件（JS 宿主）与 AIDL 插件共用同一个 id 空间：[ID_PREFIX] 前缀给外部服务，
     * 注册表里存在的 id 给进程内 provider。两者必须都认，否则麦克风按钮会绕过插件路径。
     */
    fun isProviderId(id: String): Boolean {
        if (id.startsWith(ID_PREFIX)) return true
        if (VoiceInputProviderRegistry.get(id) != null) return true
        // 进程内插件（JS 宿主）只有 install 之后才出现在注册表里。这里先装再认：
        // 冷启动时语音按钮的可见性判断会先于任何 listProviders 调用执行，
        // 少了这一步，用户选了 JS 引擎却看不到麦克风按钮。
        JsVoiceProviders.install()
        return VoiceInputProviderRegistry.get(id) != null
    }

    fun hasProvider(id: String, context: Context = appContext): Boolean =
        listProviders(context).any { it.id == id }

    /**
     * Best-effort prewarm for external voice input plugins when the virtual keyboard is shown.
     *
     * Binding with BIND_AUTO_CREATE recreates the plugin service if Android killed its process in
     * the background. We immediately call isAvailable() so heavy providers can lazily load their
     * model before the user taps the mic button, then unbind after a short delay.
     */
    fun ensureProviderAvailable(service: FcitxInputMethodService, id: String) {
        // 进程内 provider 不需要 bind 预热，它的模型/脚本按需在会话里加载。
        if (VoiceInputProviderRegistry.get(id) != null) return
        if (!isProviderId(id)) return
        if (isActive()) return
        val now = SystemClock.elapsedRealtime()
        if (now - keepAliveLastAttemptElapsed < KEEPALIVE_MIN_INTERVAL_MS) return
        keepAliveLastAttemptElapsed = now

        val providerComponent = ComponentName.unflattenFromString(id.removePrefix(ID_PREFIX))
        if (providerComponent == null) {
            logW("keepalive invalid provider id=$id")
            return
        }
        val action = listProviders(service).firstOrNull { it.id == id }?.action
        if (action == null) {
            logI("keepalive skipped: provider unavailable id=$id")
            return
        }

        keepAliveConnection?.let {
            runCatching { service.unbindService(it) }
                .onFailure { Timber.w(it, "keepalive unbind previous") }
        }
        keepAliveConnection = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                logI("keepalive provider connected: $name")
                val provider = IVoiceInputProvider.Stub.asInterface(binder)
                val connection = this
                service.lifecycleScope.launch(Dispatchers.IO) {
                    val available = runCatching { provider.isAvailable() }
                        .onFailure { Timber.w(it, "keepalive isAvailable failed") }
                        .getOrDefault(false)
                    logI("keepalive provider available=$available name=$name")
                    delay(KEEPALIVE_BIND_TIMEOUT_MS)
                    withContext(Dispatchers.Main.immediate) {
                        releaseKeepAlive(service, connection)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                logI("keepalive provider disconnected: $name")
                if (keepAliveConnection === this) {
                    keepAliveConnection = null
                }
            }

            override fun onBindingDied(name: ComponentName) {
                logW("keepalive binding died: $name")
                releaseKeepAlive(service, this)
            }

            override fun onNullBinding(name: ComponentName) {
                logW("keepalive null binding: $name")
                releaseKeepAlive(service, this)
            }
        }

        keepAliveConnection = connection
        logI("keepalive binding provider component=$providerComponent action=$action")
        val bound = try {
            service.bindService(
                Intent(action).apply {
                    component = providerComponent
                    // Allow waking the provider even if the user swiped it away or
                    // force-stopped it (package in stopped state). Without this flag
                    // Android rejects the bind and the provider can't be prewarmed.
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                },
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: SecurityException) {
            Timber.w(e, "keepalive bind denied"); false
        } catch (e: RuntimeException) {
            Timber.w(e, "keepalive bind failed"); false
        }
        if (!bound) {
            logW("keepalive bindService returned false")
            // Even when bindService returns false the ServiceConnection is
            // registered; it must be unbound or it leaks (ServiceConnectionLeaked).
            // This is the common case on OEMs that force-stopped the plugin
            // process: the bind is rejected but the connection still lingers.
            runCatching { service.unbindService(connection) }
                .onFailure { Timber.w(it, "keepalive unbind after failed bind") }
            keepAliveConnection = null
            return
        }
        service.lifecycleScope.launch {
            delay(KEEPALIVE_BIND_TIMEOUT_MS)
            if (keepAliveConnection === connection) {
                logW("keepalive bind timed out without onServiceConnected")
                releaseKeepAlive(service, connection)
            }
        }
    }

    // MVP integration point. Caller wires this to whatever UI starts voice
    // input (long-press of a key, dedicated mic button, etc.). For the MVP
    // segment text is committed via service.commitText; later this can be
    // replaced with a candidate-bar push.
    fun start(
        service: FcitxInputMethodService,
        id: String,
        onReady: () -> Unit,
        onPartialResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit = {},
        onFinished: () -> Unit = {},
        onStatus: (String) -> Unit = {},
    ) {
        logI("start requested id=$id")
        JsVoiceProviders.install()
        onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_connecting))
        tlogI("Starting voice input provider: $id")

        if (!VoiceInputAudioCapture.hasPermission(service)) {
            logW("RECORD_AUDIO permission not granted; requesting")
            requestAudioPermissionAndRetry(
                service = service,
                id = id,
                onReady = onReady,
                onPartialResult = onPartialResult,
                onError = onError,
                onLevel = onLevel,
                onFinished = onFinished,
                onStatus = onStatus,
            )
            return
        }

        val editor = service.editorSession.capture() ?: run {
            onError(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_not_available))
            return
        }
        if (floatingFallbackActive) stopFloatingFallback(service)
        else if (sessionActive || activeCallback != null || activeCapture != null) {
            stopSession(service, keepConnectionWarm = true)
        }
        cancelWarmConnectionRelease()
        floatingFallbackActive = false
        floatingResultGate.clear()
        floatingFallbackComponent = null
        if (activeProvider != null && activeProviderId != id) {
            releaseProviderConnection(service, callStopSession = true)
        }

        // Callback state is owned on Main, including the final partial on user finish.
        val generation = ++sessionGeneration
        var pendingPartialResult: String? = null
        lateinit var callback: SessionBoundVoiceCallback
        callback = SessionBoundVoiceCallback(
            dispatch = { work -> service.lifecycleScope.launch { work() } },
            isCurrent = { isCurrentVoiceSession(generation, callback) },
            delegate = object : IVoiceInputCallback.Stub() {
                override fun onReady() {
                    logI("provider ready")
                    // Releasing the microphone while a model is loading must not start
                    // recording again when its delayed onReady finally arrives.
                    if (finishing) {
                        finishRequest(service, generation, callback).complete()
                        return
                    }
                    sessionReady = true
                    onProviderReady(service, { callback.onVolumeLevel(it) },
                        { callback.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, it) })
                    if (isCurrentVoiceSession(generation, callback)) onReady()
                }

                override fun onVolumeLevel(rms: Int) {
                    onLevel(rms)
                }

                override fun onPartialResult(text: String?) {
                    val t = text.orEmpty()
                    if (t.isNotBlank()) {
                        // 只记长度：识别文本属 AGENTS.md §8.5 禁止进入日志的输入内容
                        // （logI/tlogI 虽只在 debug 输出，但内容一旦落日志文件就留在设备上）。
                        logI("partial result len=${t.length}")
                        pendingPartialResult = t
                        service.updateVoiceComposingText(t)
                        onPartialResult(t)
                    }
                }

                override fun onSegmentFinal(text: String?) {
                    val t = text.orEmpty()
                    // A final (including an explicit empty final) closes the current hypothesis.
                    pendingPartialResult = null
                    if (t.isNotBlank()) {
                        // 同上：只记长度，不记识别文本。
                        logI("segment final len=${t.length}")
                        tlogI("Voice segment final len=${t.length}")
                        service.commitText(t)
                    } else {
                        logI("segment final blank")
                        service.clearVoiceComposingText()
                    }
                }

                override fun onSessionEnded() {
                    logI("provider session ended")
                    tlogI("Voice provider session ended")
                    // endStream is the normal user-finish path (mic toggle / hold-space release).
                    // Some providers end the session without sending their final hypothesis; in
                    // that case, commit the latest partial so the text does not vanish on release.
                    // A spontaneous end/cancel still clears it, as does an explicit empty final.
                    val wasUserFinished = finishing
                    val partialToCommit = pendingPartialResult.also { pendingPartialResult = null }
                    if (wasUserFinished && !partialToCommit.isNullOrBlank()) {
                        logI("committing last partial on user finish len=${partialToCommit.length}")
                        service.commitText(partialToCommit)
                    } else {
                        service.clearVoiceComposingText()
                    }
                    if (isCurrentVoiceSession(generation, callback)) {
                        stopSession(service, keepConnectionWarm = true)
                        if (sessionGeneration == generation && editor.isCurrent()) onFinished()
                    }
                }

                override fun onError(code: Int, message: String?) {
                    logW("provider error $code")
                    Timber.w("Voice provider error code=%d", code)
                    pendingPartialResult = null
                    service.clearVoiceComposingText()
                    stopSession(service, keepConnectionWarm = false)
                    if (sessionGeneration != generation || !editor.isCurrent()) return
                    onFinished()
                    if (sessionGeneration == generation && editor.isCurrent()) {
                        onError(message ?: appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_failed))
                    }
                }
            }
        )

        activeCallback = callback
        activeEditorSession = editor
        finishing = false
        voiceSessionTerminalized = false
        sessionReady = false
        sessionActive = true
        readyHandledGeneration = -1
        // 新会话：清空上一个会话的队列统计与过载中止标记。
        pcmQueueMetrics.reset()
        lastSlowFeedWarnElapsed = 0L
        // A new session supersedes any pending finish watchdog from the previous one.
        finishWatchdogJob?.cancel()
        finishWatchdogJob = null
        ContextCompat.getMainExecutor(appContext).execute {
            if (isCurrentVoiceSession(generation, callback)) sessionStartedCallback?.invoke()
        }

        startPreRollCapture(service, { callback.onVolumeLevel(it) },
            { callback.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, it) })
        if (!isCurrentVoiceSession(generation, callback)) return

        VoiceInputProviderRegistry.get(id)?.let { entry ->
            tlogI("Starting registered voice input provider: $id")
            activeProviderId = id
            activeProvider = entry.provider
            activeSessionConfig = getPreferredSessionConfig(entry.provider)
            if (!callProviderSafely({ callback.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, it) }) {
                    configureProvider(entry.provider, activeSessionConfig)
                    entry.provider.startSession(callback)
                }) {
                return
            }
            service.lifecycleScope.launch {
                delay(ACTIVE_READY_TIMEOUT_MS)
                if (isCurrentVoiceSession(generation, callback) && activeProvider === entry.provider && !sessionReady) {
                    callback.onError(VoiceInputIpc.ErrorCodes.NOT_AVAILABLE,
                        appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_disconnected))
                }
            }
            return
        }

        activeProvider?.let { provider ->
            logI("reusing warm provider connection id=$id")
            activeSessionConfig = getPreferredSessionConfig(provider)
            if (!callProviderSafely({ callback.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, it) }) {
                    configureProvider(provider, activeSessionConfig)
                    provider.startSession(callback)
                }) {
                return
            }
            // A warm connection can still stall on onReady if the provider had
            // idle-released its model and the reload hangs. Fall back to a clean fresh
            // bind (which starts a new provider session generation) instead of leaving
            // the IME stuck in "connecting".
            service.lifecycleScope.launch {
                delay(ACTIVE_READY_TIMEOUT_MS)
                if (isCurrentVoiceSession(generation, callback) && activeProvider === provider && !sessionReady) {
                    logW("warm provider ready timed out; falling back to fresh bind")
                    releaseProviderConnection(service, callStopSession = true)
                    // activeProvider is now null, so this re-entry takes the cold-bind
                    // path (with its own bounded retry), not the warm-reuse path.
                    start(service, id, onReady, onPartialResult, onError, onLevel, onFinished, onStatus)
                }
            }
            return
        }

        val providerComponent = ComponentName.unflattenFromString(id.removePrefix(ID_PREFIX))
        if (providerComponent == null) {
            logW("invalid provider id=$id")
            callback.onError(VoiceInputIpc.ErrorCodes.NOT_AVAILABLE,
                appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_invalid_provider))
            return
        }
        val action = listProviders(service).firstOrNull { it.id == id }?.action
        if (action == null) {
            logW("provider unavailable id=$id")
            callback.onError(VoiceInputIpc.ErrorCodes.NOT_AVAILABLE,
                appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_not_available))
            return
        }

        var bindAttempt = 0

        fun abortActiveVoiceSession(expectedConnection: ServiceConnection, message: String) {
            if (voiceSessionTerminalized || activeConnection !== expectedConnection) return
            val currentCallback = activeCallback
            if (currentCallback != null && sessionActive) {
                currentCallback.onError(VoiceInputIpc.ErrorCodes.NOT_AVAILABLE, message)
            } else releaseProviderConnection(service, callStopSession = false)
        }

        // Rebind to the (already running) provider after a ready timeout. Keeps the
        // pre-roll capture and session state intact; only the AIDL connection is
        // recreated so the provider starts a fresh session generation.
        fun rebindForRetry(previous: ServiceConnection) {
            providerDeathLink.clear()
            runCatching { service.unbindService(previous) }
                .onFailure { Timber.w(it, "unbind before rebind") }
            activeConnection = null
            activeProvider = null
        }

        lateinit var attemptBind: () -> Unit
        attemptBind = fun() {
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    // A late callback from a replaced/unbound attempt must never publish
                    // its binder/provider over the current connection.
                    if (activeConnection !== this) {
                        runCatching { service.unbindService(this) }
                        return
                    }
                    logI("provider connected: $name")
                    tlogI("Voice input provider connected: $name")
                    val expectedConnection = this
                    try {
                        providerDeathLink.attach(
                            binder,
                            dispatch = { work -> service.lifecycleScope.launch { work() } },
                            onDeath = {
                                logW("provider binder died: $name")
                                abortActiveVoiceSession(expectedConnection, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_disconnected))
                            }
                        )
                    } catch (e: RemoteException) {
                        Timber.w(e, "provider binder already dead")
                        abortActiveVoiceSession(this, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_disconnected))
                        return
                    }
                    val provider = IVoiceInputProvider.Stub.asInterface(binder)
                    activeProvider = provider
                    activeProviderId = id
                    activeSessionConfig = getPreferredSessionConfig(provider)
                    val stillActiveSession = activeConnection === this && isCurrentVoiceSession(generation, callback)
                    if (!stillActiveSession) {
                        logI("provider connected while no active voice session; keeping warm only")
                        return
                    }
                    // The provider is connected, but the session is not usable until it
                    // reports onReady (model loads asynchronously). Reflect that in the UI
                    // so the user isn't left staring at "connecting".
                    if (!sessionReady) {
                        onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_loading))
                    }
                    val attemptCallback = callback.guardedBy {
                        activeConnection === expectedConnection && activeProvider === provider
                    }
                    if (!callProviderSafely({ callback.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, it) }) {
                            configureProvider(provider, activeSessionConfig)
                            provider.startSession(attemptCallback)
                        }) {
                        return
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    logI("provider disconnected: $name")
                    abortActiveVoiceSession(this, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_disconnected))
                }

                override fun onBindingDied(name: ComponentName) {
                    logW("provider binding died: $name")
                    abortActiveVoiceSession(this, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_disconnected))
                }

                override fun onNullBinding(name: ComponentName) {
                    logW("provider null binding: $name")
                    abortActiveVoiceSession(this, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_null_binding))
                }
            }
            activeConnection = connection
            bindAttempt++
            logI("binding provider component=$providerComponent action=$action attempt=$bindAttempt")
            onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_connecting))
            tlogI("Binding voice input provider: component=$providerComponent action=$action")
            val bound = try {
                service.bindService(
                    Intent(action).apply {
                        component = providerComponent
                        // Wake the provider even from a stopped state (user swiped it
                        // away or force-stopped it), otherwise the bind is rejected.
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    },
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            } catch (e: SecurityException) {
                Timber.w(e, "bind denied"); false
            } catch (e: RuntimeException) {
                Timber.w(e, "bind failed"); false
            }
            logI("bindService returned $bound")
            if (!bound) {
                // Even when bindService returns false the ServiceConnection is
                // registered and must be unbound, or it leaks (ServiceConnectionLeaked
                // at IMS destroy). This is the common OEM case where the plugin process
                // was force-stopped: the bind is rejected but the connection lingers.
                runCatching { service.unbindService(connection) }
                    .onFailure { Timber.w(it, "unbind after failed bind") }
                activeConnection = null
                activeCallback = null
                providerDeathLink.clear()
                logW("bindService returned false")
                onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_opening))
                // Pre-roll is no longer useful (no AIDL provider to feed); release the mic.
                activeCapture?.let {
                    runCatching { it.stop() }.onFailure { Timber.w(it, "stop pre-roll on bind-failed") }
                }
                activeCapture = null
                resetPreRoll()
                val floatingComponent = startFloatingFallback(service, providerComponent)
                if (floatingComponent != null) {
                    sessionActive = false
                    finishing = false
                    floatingFallbackActive = true
                    floatingFallbackComponent = floatingComponent
                    onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_loading))
                    service.lifecycleScope.launch {
                        delay(1200)
                        if (sessionGeneration != generation || !editor.isCurrent() || !floatingFallbackActive) return@launch
                        onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_floating_ready))
                    }
                } else {
                    onStatus("")
                    onError(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_cannot_bind))
                    stopSession(service, keepConnectionWarm = false)
                }
            } else {
                service.lifecycleScope.launch {
                    delay(ACTIVE_BIND_TIMEOUT_MS)
                    if (isCurrentVoiceSession(generation, callback) && activeConnection === connection && activeProvider == null) {
                        logW("provider bind timed out without onServiceConnected")
                        abortActiveVoiceSession(connection, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_bind_timeout))
                    }
                }
                // Second guard: the provider may connect quickly but never emit onReady
                // (e.g. a cold-start model load hangs). Retry the bind once before giving
                // up so the IME never stays stuck in "connecting"/"loading" indefinitely.
                service.lifecycleScope.launch {
                    delay(ACTIVE_READY_TIMEOUT_MS)
                    if (isCurrentVoiceSession(generation, callback) && activeConnection === connection && !sessionReady) {
                        if (bindAttempt < MAX_BIND_ATTEMPTS) {
                            logW("provider ready timed out; rebinding (attempt $bindAttempt)")
                            onStatus(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_status_connecting))
                            rebindForRetry(connection)
                            attemptBind()
                        } else {
                            logW("provider ready timed out without onReady after retry")
                            abortActiveVoiceSession(connection, appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_bind_timeout))
                        }
                    }
                }
            }
        }
        attemptBind()
    }

    private fun startFloatingFallback(
        service: FcitxInputMethodService,
        providerComponent: ComponentName,
    ): ComponentName? {
        val action = BuildConfig.APPLICATION_ID + VoiceInputIpc.START_FLOATING_ACTION_SUFFIX
        val serviceClass = providerComponent.className.substringBeforeLast('.') + ".FloatingService"
        val component = ComponentName(providerComponent.packageName, serviceClass)
        val editor = activeEditorSession?.takeIf { it.isCurrent() } ?: return null
        // A no-token legacy broadcast cannot be attributed to a particular editor. Only
        // use the optional fallback when the plugin explicitly advertises this protocol.
        val supportsToken = runCatching {
            service.packageManager.getServiceInfo(component, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getBoolean(VoiceInputIpc.META_FLOATING_SESSION_TOKEN, false) == true
        }.getOrDefault(false)
        if (!supportsToken) {
            logW("Floating fallback requires session-token support; use the AIDL provider")
            return null
        }
        val token = floatingResultGate.begin(editor)
        val intent = Intent(action).apply {
            this.component = component
            putExtra(VoiceInputIpc.EXTRA_FLOATING_SESSION_TOKEN, token)
            putExtra(VoiceInputIpc.EXTRA_HOST_PACKAGE, service.packageName)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return try {
            ContextCompat.startForegroundService(service, intent)
            logI("started floating fallback component=${intent.component} action=$action")
            component
        } catch (e: Throwable) {
            floatingResultGate.clear()
            logW("floating fallback failed", e)
            null
        }
    }

    private data class SessionConfig(
        val audio: VoiceInputAudioCapture.Config = VoiceInputAudioCapture.Config(),
        val silenceMs: Long = DEFAULT_SILENCE_MS,
    )

    private data class PendingPermissionStart(
        val service: java.lang.ref.WeakReference<FcitxInputMethodService>,
        val id: String,
        val onReady: () -> Unit,
        val onPartialResult: (String) -> Unit,
        val onError: (String) -> Unit,
        val onLevel: (Int) -> Unit,
        val onFinished: () -> Unit,
        val onStatus: (String) -> Unit,
    )

    private var pendingPermissionStart: PendingPermissionStart? = null

    fun onAudioPermissionResult(granted: Boolean) {
        val pending = pendingPermissionStart
        pendingPermissionStart = null
        val service = pending?.service?.get()
        if (pending == null || service == null) return
        if (!granted) {
            pending.onError(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_no_permission))
            return
        }
        start(
            service = service,
            id = pending.id,
            onReady = pending.onReady,
            onPartialResult = pending.onPartialResult,
            onError = pending.onError,
            onLevel = pending.onLevel,
            onFinished = pending.onFinished,
            onStatus = pending.onStatus,
        )
    }

    private fun requestAudioPermissionAndRetry(
        service: FcitxInputMethodService,
        id: String,
        onReady: () -> Unit,
        onPartialResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit,
        onFinished: () -> Unit,
        onStatus: (String) -> Unit,
    ) {
        pendingPermissionStart = PendingPermissionStart(
            service = java.lang.ref.WeakReference(service),
            id = id,
            onReady = onReady,
            onPartialResult = onPartialResult,
            onError = onError,
            onLevel = onLevel,
            onFinished = onFinished,
            onStatus = onStatus,
        )
        val intent = Intent(service, VoiceInputPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(intent) }
            .onFailure {
                pendingPermissionStart = null
                Timber.w(it, "Launch voice input permission activity failed")
                onError(appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_no_permission))
            }
    }

    private fun getPreferredSessionConfig(provider: IVoiceInputProvider): SessionConfig {
        val params = runCatching { provider.preferredConfig }
            .onFailure { Timber.w(it, "getPreferredConfig failed; using default voice input config") }
            .getOrNull()
        params?.classLoader = VoiceInputProviderManager::class.java.classLoader
        val defaults = VoiceInputAudioCapture.Config()
        val audio = VoiceInputAudioCapture.Config(
            sampleRate = params?.getInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, defaults.sampleRate)
                ?.takeIf { it > 0 } ?: defaults.sampleRate,
            bitsPerSample = params?.getInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, defaults.bitsPerSample)
                ?.takeIf { it == 16 } ?: defaults.bitsPerSample,
            channels = params?.getInt(VoiceInputIpc.ConfigKeys.CHANNELS, defaults.channels)
                ?.takeIf { it == 1 } ?: defaults.channels,
        )
        return SessionConfig(
            audio = audio,
            silenceMs = params?.getLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, DEFAULT_SILENCE_MS)
                ?.takeIf { it > 0 } ?: DEFAULT_SILENCE_MS,
        ).also {
            logI("provider preferred session config: $it")
        }
    }

    private fun configureProvider(provider: IVoiceInputProvider, sessionConfig: SessionConfig) {
        val params = Bundle().apply {
            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, sessionConfig.audio.sampleRate)
            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, sessionConfig.audio.bitsPerSample)
            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, sessionConfig.audio.channels)
            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, sessionConfig.silenceMs)
        }
        provider.configure(params)
    }

    private fun releaseKeepAlive(context: Context, connection: ServiceConnection) {
        if (keepAliveConnection !== connection) return
        runCatching { context.unbindService(connection) }
            .onFailure { Timber.w(it, "keepalive unbind") }
        keepAliveConnection = null
    }

    private inline fun callProviderSafely(
        onError: (String) -> Unit,
        block: () -> Unit,
    ): Boolean = try {
        block(); true
    } catch (e: Throwable) {
        Timber.w(e, "provider call failed")
        onError(e.message ?: appContext.getString(org.fxboomk.fcitx5.android.R.string.voice_error_call_failed))
        false
    }

    private fun startPreRollCapture(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val generation = sessionGeneration
        if (activeCapture != null) return
        synchronized(preRollLock) {
            preRollBuffer.clear()
            preRollActive = true
        }
        val preRollConfig = VoiceInputAudioCapture.Config()
        val capture = VoiceInputAudioCapture(
            context = service,
            config = preRollConfig,
            onPcm = pcm@{ buf, off, len, pts ->
                if (generation != sessionGeneration || voiceSessionTerminalized) return@pcm
                val packet = QueuedAudio(buf.copyOfRange(off, off + len), pts)
                val pushDirect = synchronized(preRollLock) {
                    if (generation != sessionGeneration || voiceSessionTerminalized) return@pcm
                    if (preRollActive) {
                        preRollBuffer.addLast(packet)
                        // 滚动窗口：预算窗口内最多保留首尾两个端点包，超出的最旧包被淘汰
                        // （预滚语义天然允许丢弃最旧音频，这与 feed 队列的"不丢包"不同）。
                        while (preRollBuffer.size > 1 &&
                            pts - preRollBuffer.first().ptsMs > PRE_ROLL_MAX_MS
                        ) {
                            preRollBuffer.removeFirst()
                        }
                        // 硬上限兜底：pts 不前进时上面的按时间淘汰会失效，只保留最新的一段。
                        while (preRollBuffer.size > PRE_ROLL_MAX_PACKETS) {
                            preRollBuffer.removeFirst()
                        }
                        false
                    } else {
                        true
                    }
                }
                if (pushDirect) {
                    // 生产端绝不阻塞：队列满即判过载并中止会话，不在这里丢包。
                    offerAudioPacket(service, packet, generation)
                }
            },
            onLevel = onLevel,
            onError = onError,
        )
        if (!capture.start()) {
            logW("pre-roll capture start failed; will fall back to onReady-time capture")
            resetPreRoll()
            return
        }
        activeCapture = capture
        logI("pre-roll capture started config=$preRollConfig")
    }

    private fun onProviderReady(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val duplicateReady = readyHandledGeneration == sessionGeneration
        readyHandledGeneration = sessionGeneration
        val preRollCapture = activeCapture
        val configMatches = activeSessionConfig.audio == VoiceInputAudioCapture.Config()
        if (preRollCapture == null || !configMatches) {
            if (duplicateReady && preRollCapture != null) {
                // Already capturing for this session: a repeated onReady must not tear the
                // microphone down and start it again (see C4).
                logW("duplicate onReady: keeping the running capture")
                return
            }
            if (preRollCapture != null) {
                logI("discarding pre-roll: config mismatch desired=${activeSessionConfig.audio}")
                resetPreRoll()
                runCatching { preRollCapture.stop() }
                    .onFailure { Timber.w(it, "stop pre-roll capture") }
                activeCapture = null
            }
            startCapture(service, onLevel, onError)
            return
        }
        // A provider may report onReady more than once for a single session (a retry after a
        // ready timeout does exactly that). Overwriting the fields without closing the
        // previous ones left an orphaned channel and a feed coroutine reading from it for the
        // rest of the process, one pair per duplicate callback.
        val previousQueue = activeAudioFeedQueue
        val previousJob = activeAudioFeedJob
        // 有界队列：容量按 MAX_BUFFERED_AUDIO_MS 的时长预算推导（见该常量）。
        val queue = Channel<QueuedAudio>(capacity = MAX_BUFFERED_AUDIO_PACKETS)
        val drained: List<QueuedAudio>
        synchronized(preRollLock) {
            activeAudioFeedQueue = queue
            drained = preRollBuffer.toList()
            preRollBuffer.clear()
            preRollActive = false
        }
        if (previousQueue != null || previousJob != null) {
            logW("duplicate onReady: closing the previous audio feed")
            previousQueue?.close()
            previousJob?.cancel()
        }
        logI("pre-roll flush: packets=${drained.size}")
        // 容量 ≥ PRE_ROLL_MAX_PACKETS 且此刻 feed 协程尚未启动（无人消费），回灌必然全部成功，
        // 不会因为容量不足在这里丢包。
        drained.forEach { queue.trySend(it) }
        activeAudioFeedJob = launchAudioFeedJob(service, queue)
    }

    private fun launchAudioFeedJob(
        service: FcitxInputMethodService,
        queue: Channel<QueuedAudio>,
    ): Job {
        val provider = activeProvider
        val generation = sessionGeneration
        val callback = activeCallback
        return service.lifecycleScope.launch(Dispatchers.IO) {
            for (packet in queue) {
                if (generation != sessionGeneration || voiceSessionTerminalized) break
                if (provider == null) {
                    pcmQueueMetrics.onFedSkipped()
                    continue
                }
                // 单次 Binder 调用耗时（观测用）：只做一次 nanoTime 差值，不改变提交顺序与参数。
                val startedNanos = System.nanoTime()
                try {
                    provider.feedAudio(
                        packet.pcm, 0, packet.pcm.size, packet.ptsMs,
                    )
                } catch (e: RemoteException) {
                    // Binder is dead — provider crashed or was killed. Don't keep
                    // looping silently; surface via the callback so the UI clears
                    // its "listening" state and stopSession unwinds.
                    Timber.w(e, "feedAudio failed — provider gone, aborting session")
                    withContext(Dispatchers.Main) {
                        if (isCurrentVoiceSession(generation, callback)) {
                            runCatching {
                                callback?.onError(
                                    VoiceInputIpc.ErrorCodes.UNKNOWN,
                                    "Provider disconnected",
                                )
                            }
                        }
                    }
                    return@launch
                }
                if (generation != sessionGeneration || voiceSessionTerminalized) break
                val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
                pcmQueueMetrics.onFed(elapsedMs)
                if (elapsedMs >= FEED_SLOW_WARN_MS) {
                    pcmQueueMetrics.onSlowFeed()
                    // logW 与慢调用告警一致：只记日志、不抛异常，provider 变慢时 feedAudio 仍然正常返回。
                    logW("feedAudio slow: ${elapsedMs}ms (threshold ${FEED_SLOW_WARN_MS}ms)")
                    warnSlowFeed(elapsedMs)
                }
            }
        }
    }

    /**
     * 生产端（AudioRecord 采集线程，见 [VoiceInputAudioCapture]）唯一的入队入口，绝不阻塞。
     *
     * 过载语义固定为"不丢单个音频包"：队列满说明 provider 处理不过来，积压音频已经在变成
     * 高延迟错误识别，此时中止当前会话并报告 provider-too-slow，而不是把包丢掉继续跑。
     *
     * 队列已关闭（会话结束 / 队列被新一轮 onReady 替换）不算过载——这时丢包是既有设计
     * （见 [startCapture] 里对读取字段而非捕获 channel 的说明），保持原行为。
     */
    private fun offerAudioPacket(service: FcitxInputMethodService, packet: QueuedAudio, generation: Int) {
        val queue = activeAudioFeedQueue ?: return
        if (generation != sessionGeneration || voiceSessionTerminalized) return
        val result = queue.trySend(packet)
        if (result.isSuccess) {
            pcmQueueMetrics.onEnqueued(packet.ptsMs)
            return
        }
        if (result.isClosed) return
        // 队列满：provider 处理不过来。
        onAudioQueueOverflow(service, packet.ptsMs, generation)
    }

    /**
     * 队列满（provider 处理不过来）时的中止路径。
     *
     * 复用既有的会话失败/停止路径：provider 还活着就走 [IVoiceInputCallback.onError]
     * →[start] 里注册的 callback→stopSession（与 Binder 死亡、provider 报错完全同一条链，
     * 会清 composing、回调 onFinished/onError、释放 AudioRecord、解绑 provider）；
     * 没有回调时直接走 stopSession 的兜底分支。
     *
     * 调用点在采集线程上，所以真正的清理放到主线程协程里做：这里只记一次统计和日志。
     */
    private fun onAudioQueueOverflow(service: FcitxInputMethodService, ptsMs: Long, generation: Int) {
        if (generation != sessionGeneration || voiceSessionTerminalized) return
        // 同一会话只中止一次：队列满之后采集线程可能还投递一两个包。
        if (overflowAbortGeneration.getAndSet(generation) == generation) return
        val overloads = pcmQueueMetrics.onOverflow()
        Timber.w(
            "Voice PCM queue full (provider too slow): generation=$generation " +
                "capacity=$MAX_BUFFERED_AUDIO_PACKETS packets " +
                "budgetMs=$MAX_BUFFERED_AUDIO_MS ptsMs=$ptsMs overloads=$overloads " +
                pcmQueueMetrics.summary()
        )
        service.lifecycleScope.launch { abortForOverload(service, generation) }
    }

    /** 主线程上的过载清理；[stopSession] 与 callback 都要求在主线程执行。 */
    private fun abortForOverload(service: FcitxInputMethodService, generation: Int) {
        if (generation != sessionGeneration) return
        val cb = activeCallback
        if (cb == null) {
            // 兜底：没有活跃回调时直接收尾（voiceFinished/voiceError 只做 UI 清理，不触碰会话状态）。
            service.clearVoiceComposingText()
            voiceFinishedCallback?.invoke()
            voiceErrorCallback?.invoke(PROVIDER_TOO_SLOW_MESSAGE)
        }
        if (generation != sessionGeneration) return
        // 立刻停止采集、释放 AudioRecord：队列里的残留包仍会被 feed 协程送完，
        // 随后 stopSession 取消该协程。
        activeCapture?.let {
            runCatching { it.stop() }.onFailure { e -> Timber.w(e, "capture stop on overflow") }
        }
        activeCapture = null
        resetPreRoll()
        if (cb != null) {
            runCatching {
                cb.onError(VoiceInputIpc.ErrorCodes.UNKNOWN, PROVIDER_TOO_SLOW_MESSAGE)
            }.onFailure { e -> Timber.w(e, "onError(provider too slow) failed") }
        } else {
            stopSession(service, keepConnectionWarm = false)
        }
    }

    /** 慢调用告警（限频）：provider 慢是过载的前兆，重新走一遍基线时靠它定位。 */
    private fun warnSlowFeed(elapsedMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSlowFeedWarnElapsed < SLOW_FEED_WARN_INTERVAL_MS) return
        lastSlowFeedWarnElapsed = now
        Timber.w(
            "feedAudio slow: ${elapsedMs}ms (threshold ${FEED_SLOW_WARN_MS}ms) " +
                pcmQueueMetrics.summary()
        )
    }

    /** 会话结束时的队列统计汇总（一次字符串拼接，不影响正常路径）。 */
    private fun logPcmQueueSummary(reason: String) {
        logI("PCM queue stats ($reason): ${pcmQueueMetrics.summary()}")
        if (pcmQueueMetrics.overloads > 0) {
            Timber.w("Voice PCM queue overloaded ($reason): ${pcmQueueMetrics.summary()}")
        }
    }

    private fun resetPreRoll() {
        synchronized(preRollLock) {
            preRollBuffer.clear()
            preRollActive = false
        }
    }

    private fun startCapture(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val generation = sessionGeneration
        if (activeCapture != null) return
        activeProvider ?: return
        // Same reason as in onProviderReady: never overwrite a live feed without closing it.
        activeAudioFeedQueue?.close()
        activeAudioFeedJob?.cancel()
        // 有界队列：容量按 MAX_BUFFERED_AUDIO_MS 的时长预算推导（见该常量）。
        val audioQueue = Channel<QueuedAudio>(capacity = MAX_BUFFERED_AUDIO_PACKETS)
        activeAudioFeedQueue = audioQueue
        activeAudioFeedJob = launchAudioFeedJob(service, audioQueue)
        val capture = VoiceInputAudioCapture(
            context = service,
            config = activeSessionConfig.audio,
            onPcm = { buf, off, len, pts ->
                // Read the field, do not capture `audioQueue`: a later onProviderReady may
                // replace activeAudioFeedQueue and close the old channel while this capture is
                // still running, and a closed channel makes trySend fail silently — the
                // microphone stays on but not one packet reaches the provider. The pre-roll
                // capture already reads the field for the same reason.
                //
                // offerAudioPacket 内部是 trySend 语义（绝不阻塞采集线程）；队列满即判过载
                // 并中止会话，不在这里丢包。
                offerAudioPacket(
                    service,
                    QueuedAudio(
                        pcm = buf.copyOfRange(off, off + len),
                        ptsMs = pts,
                    ),
                    generation,
                )
            },
            onLevel = onLevel,
            onError = onError,
        )
        activeCapture = capture
        if (!capture.start()) {
            logW("capture start failed")
            activeCapture = null
            activeAudioFeedQueue?.close()
            activeAudioFeedQueue = null
            activeAudioFeedJob?.cancel()
            activeAudioFeedJob = null
        } else {
            logI("capture started")
        }
    }

    /** Finish the active recording and wait for its final result; never starts a new session. */
    fun finish(context: Context = appContext) {
        if (!isActive()) return
        if (floatingFallbackActive) {
            stopFloatingFallback(context)
            return
        }
        if (finishing) {
            logI("finish ignored: already finishing")
            return
        }
        finishing = true
        val request = finishRequest(context, sessionGeneration, activeCallback)
        logI(
            "finish provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null}"
        )
        tlogI(
            "Finishing voice input provider: provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null}"
        )
        activeCapture?.let {
            runCatching { it.stop() }.onFailure { Timber.w(it, "capture stop") }
        }
        activeCapture = null
        resetPreRoll()

        val queue = activeAudioFeedQueue
        val feedJob = activeAudioFeedJob
        val scope = (context as? FcitxInputMethodService)?.lifecycleScope
        if (queue != null && feedJob != null && scope != null) {
            scope.launch {
                queue.close()
                val drained = withTimeoutOrNull(FEED_DRAIN_TIMEOUT_MS) { feedJob.join() } != null
                if (!drained) logW("feed queue drain timed out before endStream")
                request.complete()
                // Arm after the frame is actually out: draining may take up to
                // FEED_DRAIN_TIMEOUT_MS, and arming beforehand spent that time out of the
                // provider's response budget.
            }
        } else {
            request.complete()
        }
    }

    /**
     * Force the session closed if the provider never answers endStream.
     *
     * Without this, a provider that dies (or hangs) after endStream leaves `finishing = true`
     * permanently and [toggle] refuses every subsequent press — the microphone button stays
     * dead for the rest of the process. The generation check keeps a late fire from killing a
     * session the user started in the meantime.
     */
    private fun armFinishWatchdog(context: Context, generation: Int, callback: IVoiceInputCallback?) {
        val service = context as? FcitxInputMethodService
        if (service == null) {
            // finish() defaults to appContext, so a caller that does not pass the IME service
            // gets no watchdog — and with it the original "microphone button dead forever" bug.
            logW("no IME service context: finish watchdog not armed")
            return
        }
        // 进程内 JS 插件自带更长的收尾预算（flash 模式要把音频 POST 到云端）；IPC 插件仍用 3s。
        val budget = if (activeProvider is org.fxboomk.fcitx5.android.input.voice.js.JsVoiceInputProvider) {
            org.fxboomk.fcitx5.android.input.voice.js.JsVoiceInputProvider.FINISH_WATCHDOG_MS
        } else {
            FINISH_WATCHDOG_MS
        }
        finishWatchdogJob?.cancel()
        finishWatchdogJob = service.lifecycleScope.launch {
            delay(budget)
            // A newer session already started, or the provider answered and cleared
            // `finishing` — either way there is nothing to force.
            if (!isCurrentVoiceSession(generation, callback) || !finishing) return@launch
            logW("provider did not acknowledge endStream in ${budget}ms; forcing teardown")
            Timber.w("Voice provider did not acknowledge endStream; forcing teardown")
            runCatching { service.clearVoiceComposingText() }
            val editor = activeEditorSession
            stopSession(service, keepConnectionWarm = false)
            if (sessionGeneration == generation && editor?.isCurrent() == true) voiceFinishedCallback?.invoke()
        }
    }

    private fun finishRequest(context: Context, generation: Int, callback: IVoiceInputCallback?): SessionBoundVoiceFinish {
        val provider = activeProvider
        return SessionBoundVoiceFinish(
            isCurrent = { isCurrentVoiceSession(generation, callback) && activeProvider === provider },
            endStream = { provider?.endStream(); logI("endStream sent") },
            armWatchdog = { armFinishWatchdog(context, generation, callback) },
            onFailure = { Timber.w(it, "endStream"); stopSession(context, keepConnectionWarm = false) }
        )
    }

    fun stop(context: Context = appContext) {
        if (floatingFallbackActive) {
            stopFloatingFallback(context)
            return
        }
        // 取消会话不经过 finish()，但既然不再采集，就把队列统计收尾一次。
        // 统计字段本身不会被清零（reset 只在新会话开始时做），重复记录不会丢信息。
        logPcmQueueSummary("stop")
        stopSession(context, keepConnectionWarm = false)
    }

    private fun stopSession(context: Context = appContext, keepConnectionWarm: Boolean) {
        pendingPermissionStart = null
        val generation = sessionGeneration
        voiceSessionTerminalized = true
        // 会话收尾：把本会话的队列高水位/最旧包年龄/feedAudio 耗时/过载次数落到日志。
        logPcmQueueSummary("stopSession")
        // The handshake completed (or is being forced): the watchdog has nothing left to do.
        finishWatchdogJob?.cancel()
        finishWatchdogJob = null
        logI(
            "stop provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null} " +
                "keepWarm=$keepConnectionWarm"
        )
        tlogI(
            "Stopping voice input provider: provider=${activeProvider != null} " +
                "connection=${activeConnection != null} capture=${activeCapture != null} " +
                "keepWarm=$keepConnectionWarm"
        )
        activeCapture?.let {
            runCatching { it.stop() }.onFailure { Timber.w(it, "capture stop") }
        }
        activeCapture = null
        resetPreRoll()
        activeAudioFeedQueue?.close()
        activeAudioFeedQueue = null
        activeAudioFeedJob?.cancel()
        activeAudioFeedJob = null
        try {
            activeProvider?.stopSession()
        } catch (e: Throwable) {
            Timber.w(e, "stopSession")
        }
        activeCallback = null
        activeEditorSession = null
        finishing = false
        sessionActive = false
        floatingFallbackActive = false
        floatingResultGate.clear()
        floatingFallbackComponent = null
        if (keepConnectionWarm && activeProvider != null && activeConnection != null) {
            scheduleWarmConnectionRelease(context)
        } else {
            releaseProviderConnection(context, callStopSession = false)
        }
        keepAliveConnection?.let {
            runCatching { context.unbindService(it) }
                .onFailure { Timber.w(it, "keepalive unbind on stop") }
        }
        keepAliveConnection = null
        ContextCompat.getMainExecutor(appContext).execute {
            if (sessionGeneration == generation) sessionEndedCallback?.invoke()
        }
    }

    private fun scheduleWarmConnectionRelease(context: Context) {
        val generation = ++warmConnectionReleaseGeneration
        logI("schedule warm provider release in ${WARM_CONNECTION_HOLD_MS}ms")
        if (context is FcitxInputMethodService) {
            context.lifecycleScope.launch {
                delay(WARM_CONNECTION_HOLD_MS)
                if (warmConnectionReleaseGeneration == generation && !isActive()) {
                    releaseProviderConnection(context, callStopSession = true)
                }
            }
        }
    }

    private fun cancelWarmConnectionRelease() {
        warmConnectionReleaseGeneration++
    }

    private fun releaseProviderConnection(context: Context, callStopSession: Boolean) {
        voiceSessionTerminalized = true
        cancelWarmConnectionRelease()
        activeAudioFeedQueue?.close()
        activeAudioFeedQueue = null
        activeAudioFeedJob?.cancel()
        activeAudioFeedJob = null
        if (callStopSession) {
            try {
                activeProvider?.stopSession()
            } catch (e: Throwable) {
                Timber.w(e, "stopSession")
            }
        }
        providerDeathLink.clear()
        activeProvider = null
        activeProviderId = null
        activeConnection?.let {
            runCatching { context.unbindService(it) }
                .onFailure { Timber.w(it, "unbind") }
        }
        activeConnection = null
    }

    private fun stopFloatingFallback(context: Context, token: String? = floatingResultGate.currentToken) {
        val generation = sessionGeneration
        voiceSessionTerminalized = true
        activeEditorSession = null
        val component = floatingFallbackComponent
        floatingFallbackActive = false
        floatingResultGate.clear()
        floatingFallbackComponent = null
        finishing = false
        sessionActive = false
        if (component != null) {
            val intent = Intent("stop_service").apply {
                this.component = component
                putExtra(VoiceInputIpc.EXTRA_FLOATING_SESSION_TOKEN, token)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Timber.w(it, "stop floating fallback failed") }
        }
        // Both callbacks touch views, and this function can be reached from a binder thread
        // (provider death / onError). Post them together instead of invoking voiceFinished
        // inline on whatever thread we happen to be on.
        ContextCompat.getMainExecutor(appContext).execute {
            if (sessionGeneration != generation) return@execute
            sessionEndedCallback?.invoke()
            voiceFinishedCallback?.invoke()
        }
    }
}
