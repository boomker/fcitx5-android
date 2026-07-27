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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import org.fcitx.fcitx5.android.common.ipc.VoiceInputIpc
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.utils.appContext
import timber.log.Timber
import java.lang.ref.WeakReference

data class VoiceInputProviderInfo(
    val id: String,
    val label: CharSequence,
    val packageName: String,
    val serviceName: String,
    val action: String,
)

object VoiceInputProviderManager {
    const val ID_PREFIX = "plugin:"

    private const val ACTIVE_BIND_TIMEOUT_MS = 8000L
    private const val DEFAULT_SAMPLE_RATE = 16000
    private const val DEFAULT_BITS_PER_SAMPLE = 16
    private const val DEFAULT_CHANNELS = 1
    private const val DEFAULT_SILENCE_MS = 3000L

    var voiceStatusCallback: ((String) -> Unit)? = null
    var voiceReadyCallback: (() -> Unit)? = null
    var voiceLevelCallback: ((Int) -> Unit)? = null
    var voiceFinishedCallback: (() -> Unit)? = null
    var voiceErrorCallback: ((String) -> Unit)? = null

    private val actions = buildSet {
        val appId = BuildConfig.APPLICATION_ID
        add(appId)
        val releaseLikeId = appId.removeSuffix(".debug")
        add(releaseLikeId)
        add("$releaseLikeId.debug")
        add("org.fcitx.fcitx5.android")
        add("org.fcitx.fcitx5.android.debug")
    }.map { it + VoiceInputIpc.SERVICE_ACTION_SUFFIX }

    private data class QueuedAudio(
        val pcm: ByteArray,
        val ptsMs: Long,
    )

    private data class SessionConfig(
        val audio: VoiceInputAudioCapture.Config = VoiceInputAudioCapture.Config(),
        val silenceMs: Long = DEFAULT_SILENCE_MS,
    )

    private data class PendingPermissionStart(
        val service: WeakReference<FcitxInputMethodService>,
        val id: String,
        val onReady: () -> Unit,
        val onPartialResult: (String) -> Unit,
        val onError: (String) -> Unit,
        val onLevel: (Int) -> Unit,
        val onFinished: () -> Unit,
        val onStatus: (String) -> Unit,
    )

    private var activeConnection: ServiceConnection? = null
    private var activeProvider: IVoiceInputProvider? = null
    private var activeCapture: VoiceInputAudioCapture? = null
    private var activeAudioFeedJob: Job? = null
    private var activeAudioFeedQueue: Channel<QueuedAudio>? = null
    private var activeBindTimeoutJob: Job? = null
    private var activeProviderId: String? = null
    private var activeSessionConfig = SessionConfig()
    private var pendingPermissionStart: PendingPermissionStart? = null
    @Volatile
    private var finishing = false

    fun isActive(): Boolean =
        activeConnection != null || activeProvider != null || activeCapture != null || activeProviderId != null

    fun isProviderId(id: String) = id.startsWith(ID_PREFIX)

    fun hasProvider(id: String, context: Context = appContext): Boolean =
        listProviders(context).any { it.id == id }

    fun hasAudioPermission(context: Context = appContext): Boolean =
        VoiceInputAudioCapture.hasPermission(context)

    fun listProviders(context: Context = appContext): List<VoiceInputProviderInfo> {
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
        return services.mapNotNull { (action, info) ->
            val serviceInfo = info.serviceInfo ?: return@mapNotNull null
            val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
            VoiceInputProviderInfo(
                id = ID_PREFIX + component.flattenToShortString(),
                label = serviceInfo.loadLabel(context.packageManager),
                packageName = serviceInfo.packageName,
                serviceName = serviceInfo.name,
                action = action,
            )
        }.distinctBy { it.id }.sortedBy { it.label.toString() }
    }

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
            finish(service)
            return false
        }
        start(service, id, onReady, onPartialResult, onError, onLevel, onFinished, onStatus)
        return true
    }

    fun start(
        service: FcitxInputMethodService,
        id: String,
        onReady: () -> Unit,
        onPartialResult: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: (Int) -> Unit,
        onFinished: () -> Unit,
        onStatus: (String) -> Unit,
    ) {
        if (!isProviderId(id)) {
            onError(appContext.getString(R.string.voice_error_invalid_provider))
            return
        }
        if (!VoiceInputAudioCapture.hasPermission(service)) {
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

        val providerInfo = listProviders(service).firstOrNull { it.id == id }
        if (providerInfo == null) {
            onError(appContext.getString(R.string.voice_error_not_available))
            return
        }
        val component = ComponentName(providerInfo.packageName, providerInfo.serviceName)
        val intent = Intent(providerInfo.action).setComponent(component)
        onStatus(appContext.getString(R.string.voice_status_connecting))
        activeProviderId = id

        val callback = object : IVoiceInputCallback.Stub() {
            override fun onReady() {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    onReady()
                    startCapture(service, onLevel, onError)
                }
            }

            override fun onVolumeLevel(rms: Int) {
                service.lifecycleScope.launch(Dispatchers.Main) { onLevel(rms) }
            }

            override fun onPartialResult(text: String?) {
                val value = text.orEmpty()
                service.lifecycleScope.launch(Dispatchers.Main) {
                    onPartialResult(value)
                    service.updateVoiceComposingText(value)
                }
            }

            override fun onSegmentFinal(text: String?) {
                val value = text.orEmpty()
                service.lifecycleScope.launch(Dispatchers.Main) {
                    service.commitVoiceText(value)
                }
            }

            override fun onSessionEnded() {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    serviceFinished(service, onFinished)
                }
            }

            override fun onError(code: Int, message: String?) {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    onError(message ?: appContext.getString(R.string.voice_error_failed))
                    stopSession(service)
                }
            }
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                activeBindTimeoutJob?.cancel()
                val provider = IVoiceInputProvider.Stub.asInterface(binder)
                activeProvider = provider
                service.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (!provider.isAvailable()) {
                            withContext(Dispatchers.Main) {
                                onError(appContext.getString(R.string.voice_error_not_available))
                                stopSession(service)
                            }
                            return@launch
                        }
                        val config = provider.getPreferredConfig() ?: Bundle()
                        activeSessionConfig = parseConfig(config)
                        val params = Bundle(config).apply {
                            putInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, activeSessionConfig.audio.sampleRate)
                            putInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, activeSessionConfig.audio.bitsPerSample)
                            putInt(VoiceInputIpc.ConfigKeys.CHANNELS, activeSessionConfig.audio.channels)
                            putLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, activeSessionConfig.silenceMs)
                        }
                        provider.configure(params)
                        withContext(Dispatchers.Main) {
                            onStatus(appContext.getString(R.string.voice_status_loading))
                        }
                        provider.startSession(callback)
                    } catch (e: RemoteException) {
                        Timber.w(e, "Voice input provider call failed")
                        withContext(Dispatchers.Main) {
                            onError(appContext.getString(R.string.voice_error_call_failed))
                            stopSession(service)
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    onError(appContext.getString(R.string.voice_error_disconnected))
                    stopSession(service)
                }
            }

            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)

            override fun onNullBinding(name: ComponentName) {
                service.lifecycleScope.launch(Dispatchers.Main) {
                    onError(appContext.getString(R.string.voice_error_null_binding))
                    stopSession(service)
                }
            }
        }

        activeConnection = connection
        val bound = runCatching { service.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) {
            onError(appContext.getString(R.string.voice_error_cannot_bind))
            stopSession(service)
            return
        }
        activeBindTimeoutJob = service.lifecycleScope.launch {
            kotlinx.coroutines.delay(ACTIVE_BIND_TIMEOUT_MS)
            if (activeProvider == null && activeProviderId == id) {
                onError(appContext.getString(R.string.voice_error_bind_timeout))
                stopSession(service)
            }
        }
    }

    fun stop(context: Context = appContext) {
        pendingPermissionStart = null
        finish(context)
    }

    fun onAudioPermissionResult(granted: Boolean) {
        val pending = pendingPermissionStart
        pendingPermissionStart = null
        val service = pending?.service?.get()
        if (pending == null || service == null) return
        if (!granted) {
            pending.onError(appContext.getString(R.string.voice_error_no_permission))
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
            service = WeakReference(service),
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
                onError(appContext.getString(R.string.voice_error_no_permission))
            }
    }

    private fun parseConfig(bundle: Bundle): SessionConfig {
        val sampleRate = bundle.getInt(VoiceInputIpc.ConfigKeys.SAMPLE_RATE, DEFAULT_SAMPLE_RATE)
            .takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val bits = bundle.getInt(VoiceInputIpc.ConfigKeys.BITS_PER_SAMPLE, DEFAULT_BITS_PER_SAMPLE)
            .takeIf { it > 0 } ?: DEFAULT_BITS_PER_SAMPLE
        val channels = bundle.getInt(VoiceInputIpc.ConfigKeys.CHANNELS, DEFAULT_CHANNELS)
            .takeIf { it > 0 } ?: DEFAULT_CHANNELS
        val silenceMs = bundle.getLong(VoiceInputIpc.ConfigKeys.SILENCE_MS, DEFAULT_SILENCE_MS)
            .takeIf { it > 0L } ?: DEFAULT_SILENCE_MS
        return SessionConfig(
            audio = VoiceInputAudioCapture.Config(sampleRate, bits, channels),
            silenceMs = silenceMs,
        )
    }

    private fun startCapture(
        service: FcitxInputMethodService,
        onLevel: (Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (activeCapture != null) return
        val audioQueue = Channel<QueuedAudio>(capacity = Channel.UNLIMITED)
        activeAudioFeedQueue = audioQueue
        activeAudioFeedJob = service.lifecycleScope.launch(Dispatchers.IO) {
            for (packet in audioQueue) {
                val provider = activeProvider ?: continue
                try {
                    provider.feedAudio(packet.pcm, 0, packet.pcm.size, packet.ptsMs)
                } catch (e: RemoteException) {
                    Timber.w(e, "Voice input feedAudio failed")
                    withContext(Dispatchers.Main) {
                        onError(appContext.getString(R.string.voice_error_disconnected))
                        stopSession(service)
                    }
                    return@launch
                }
            }
        }
        val capture = VoiceInputAudioCapture(
            context = service,
            config = activeSessionConfig.audio,
            onPcm = { buffer, offset, length, ptsMs ->
                audioQueue.trySend(
                    QueuedAudio(
                        pcm = buffer.copyOfRange(offset, offset + length),
                        ptsMs = ptsMs,
                    ),
                )
            },
            onLevel = { rms -> service.lifecycleScope.launch(Dispatchers.Main) { onLevel(rms) } },
            onError = { msg ->
                service.lifecycleScope.launch(Dispatchers.Main) {
                    onError(msg)
                    stopSession(service)
                }
            },
        )
        activeCapture = capture
        if (!capture.start()) {
            activeCapture = null
            audioQueue.close()
            activeAudioFeedJob?.cancel()
            activeAudioFeedJob = null
            activeAudioFeedQueue = null
        }
    }

    private fun finish(context: Context) {
        if (finishing) return
        finishing = true
        activeCapture?.stop()
        activeCapture = null
        activeAudioFeedQueue?.close()
        activeAudioFeedQueue = null
        activeAudioFeedJob?.cancel()
        activeAudioFeedJob = null
        runCatching { activeProvider?.endStream() }
            .onFailure { Timber.w(it, "Voice input endStream failed") }
        if (activeProvider == null) {
            stopSession(context)
        }
    }

    private fun serviceFinished(context: Context, onFinished: (() -> Unit)?) {
        onFinished?.invoke()
        stopSession(context)
    }

    private fun stopSession(context: Context = appContext) {
        pendingPermissionStart = null
        activeBindTimeoutJob?.cancel()
        activeBindTimeoutJob = null
        activeCapture?.stop()
        activeCapture = null
        activeAudioFeedQueue?.close()
        activeAudioFeedQueue = null
        activeAudioFeedJob?.cancel()
        activeAudioFeedJob = null
        runCatching { activeProvider?.stopSession() }
            .onFailure { Timber.w(it, "Voice input stopSession failed") }
        val connection = activeConnection
        activeConnection = null
        if (connection != null) {
            runCatching { context.unbindService(connection) }
                .onFailure { Timber.w(it, "Voice input unbind failed") }
        }
        activeProvider = null
        activeProviderId = null
        activeSessionConfig = SessionConfig()
        finishing = false
    }
}
