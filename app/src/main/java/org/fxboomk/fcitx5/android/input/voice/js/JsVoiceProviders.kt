/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import okhttp3.OkHttpClient
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderRegistry
import org.fxboomk.fcitx5.android.utils.appContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * JS 语音插件清单：内置插件 + 用户导入的若干条。
 *
 * 这里是「插件是什么」的唯一权威：注册到 [VoiceInputProviderRegistry] 的 provider、设置页渲染的
 * 字段、会话下发的设置、设置页列表的条目，全都从同一份 [Spec] 派生，避免各处各读一套数据。
 *
 * 列表会缓存，因为 [org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderManager] 在按键热路径上
 * 反复查 id；导入/删除插件后必须调 [reload] 让缓存与注册表一起重建。
 *
 * 脚本在**会话启动时**才读（asset 或文件），不进输入法启动热路径。
 */
internal object JsVoiceProviders {

    const val QWEN_ASR_ID = "js:qwen-asr"
    const val DOUBAO_ASR_ID = "js:doubao-asr"
    const val TENCENT_ASR_ID = "js:tencent-asr"
    const val TENCENT_TRANSLATE_ID = "js:tencent-translate"

    /** 脚本正文的来源。内置读 asset，用户导入的读私有目录文件。 */
    internal sealed interface Source {
        class Asset(val path: String) : Source
        class Imported(val file: File) : Source
    }

    internal data class Spec(
        val id: String,
        val label: String,
        val version: String,
        val hosts: Set<String>,
        val sampleRate: Int,
        val source: Source,
        val builtIn: Boolean,
        /** 界面展示的来源：内置是 asset 名，导入是用户选中的文件名。 */
        val originName: String,
    )

    private val lock = Any()
    private var cached: List<Spec>? = null
    private var cachedById: Map<String, Spec> = emptyMap()
    private var installedRevision = -1L

    /** 本对象注册进注册表的 id。删除插件时只回收自己注册的那些，不动别的进程内 provider。 */
    private val registeredIds = LinkedHashSet<String>()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            // flash 模式把整段音频 POST 上去等文本，默认 10s 读超时会在云端慢的时候先失败，
            // 而 Foxy 给 stop 回调的预算是 35s。
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun builtInSpecs(): List<Spec> = listOf(
        Spec(
            id = QWEN_ASR_ID,
            label = appContext.getString(R.string.js_voice_provider_qwen_asr),
            version = "1.6.3",
            hosts = setOf(
                "dashscope.aliyuncs.com",
                "maas.qianwenaiapi.com",
                "cn-beijing.maas.qianwenaiapi.com",
            ),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/qwen-asr.js"),
            builtIn = true,
            originName = "qwen-asr.js",
        ),
        Spec(
            id = DOUBAO_ASR_ID,
            label = appContext.getString(R.string.js_voice_provider_doubao_asr),
            version = "1.2.0",
            // 火山引擎语音识别只开这两个口子：bigmodel_async（流式）与 bigmodel_nostream（单次）。
            hosts = setOf("openspeech.bytedance.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/doubao-asr.js"),
            builtIn = true,
            originName = "doubao-asr.js",
        ),
        Spec(
            id = TENCENT_ASR_ID,
            label = appContext.getString(R.string.js_voice_provider_tencent_asr),
            version = "1.0.7",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/tencent-asr.js"),
            builtIn = true,
            originName = "tencent-asr.js",
        ),
        Spec(
            id = TENCENT_TRANSLATE_ID,
            label = appContext.getString(R.string.js_voice_provider_tencent_translate),
            version = "1.0.0",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/tencent-translate.js"),
            builtIn = true,
            originName = "tencent-translate.js",
        ),
    )

    private fun importedSpecs(): List<Spec> = JsVoicePluginStore.list().map { record ->
        Spec(
            id = record.meta.providerId,
            label = record.meta.displayName,
            version = record.meta.version,
            hosts = record.meta.hosts.toSet(),
            sampleRate = record.meta.sampleRate,
            source = Source.Imported(JsVoicePluginStore.scriptFile(record.meta.id)),
            builtIn = false,
            originName = record.sourceName,
        )
    }

    // A previously imported plugin can become bundled. Prefer the bundled implementation while
    // retaining the imported file and the settings/secrets under the same provider ID.
    private fun buildSpecs(): List<Spec> = (builtInSpecs() + importedSpecs()).distinctBy { it.id }

    fun all(): List<Spec> = synchronized(lock) { cached ?: buildSpecs().also { cached = it; cachedById = it.associateBy(Spec::id) } }

    fun builtInIds(): Set<String> = builtInSpecs().map { it.id }.toSet()

    fun specOf(pluginId: String): Spec? = synchronized(lock) {
        all()
        cachedById[pluginId]
    }

    fun readScript(spec: Spec): String = when (val source = spec.source) {
        is Source.Asset -> appContext.assets.open(source.path).bufferedReader().use { it.readText() }
        is Source.Imported -> if (source.file.isFile) source.file.readText() else ""
    }

    /** 读取插件声明的设置项；设置页与会话共用这一份声明。 */
    suspend fun describe(pluginId: String, locale: String = Locale.getDefault().toLanguageTag()): List<JsVoiceField> {
        val spec = specOf(pluginId) ?: return emptyList()
        return JsVoiceScript.describe(readScript(spec), locale)
    }

    /**
     * 把当前清单同步进注册表：缺的注册、多的回收。
     *
     * 幂等且便宜（[all] 走缓存），所以 [VoiceInputProviderManager] 可以在热路径上直接调。
     */
    @Synchronized
    fun install() {
        if (installedRevision == VoiceInputProviderRegistry.revision) return
        val specs = all()
        val ids = specs.map { it.id }.toSet()
        if (registeredIds.any { it !in ids }) {
            (registeredIds - ids).forEach { VoiceInputProviderRegistry.unregister(it) }
            registeredIds.retainAll(ids)
        }
        specs.forEach { spec ->
            if (VoiceInputProviderRegistry.get(spec.id) != null) {
                registeredIds += spec.id
                return@forEach
            }
            val provider = JsVoiceInputProvider(
                pluginId = spec.id,
                scriptSource = { readScript(spec) },
                declaredHosts = spec.hosts,
                declaredSampleRate = spec.sampleRate,
                locale = Locale.getDefault().toLanguageTag(),
                http = http,
            )
            VoiceInputProviderRegistry.register(
                VoiceInputProviderRegistry.Entry(spec.id, spec.label, appContext.packageName, provider)
            )
            registeredIds += spec.id
        }
        installedRevision = VoiceInputProviderRegistry.revision
    }

    /**
     * 导入/删除插件后重建：清缓存、回收旧 provider、按新清单重新注册。
     *
     * 必须重建而不是只注册新的：同名插件升级时 hosts 可能变了，而 provider 把 hosts 与脚本来源
     * 都握在构造参数里，复用旧实例会让「新脚本 + 旧白名单」这种错配一直活到进程重启。
     */
    @Synchronized
    fun reload() {
        registeredIds.forEach { VoiceInputProviderRegistry.unregister(it) }
        registeredIds.clear()
        installedRevision = -1L
        synchronized(lock) { cached = null }
        install()
    }
}
