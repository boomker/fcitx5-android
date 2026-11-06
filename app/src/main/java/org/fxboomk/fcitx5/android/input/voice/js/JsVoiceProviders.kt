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

    const val VOICE_TAG = "voice"

    const val QWEN_ASR_ID = "js:qwen-asr"
    const val DOUBAO_ASR_ID = "js:doubao-asr"
    const val TENCENT_ASR_ID = "js:tencent-asr"
    const val TENCENT_TRANSLATE_ID = "js:tencent-translate"
    const val LLM_ASSISTANT_ID = "js:llm-assistant"

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
        val tags: Set<String>,
        val hasVoice: Boolean = true,
        val apps: List<JsPluginApp> = emptyList(),
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
            // 而输入法程序给 stop 回调的预算是 35s。
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun builtInSpecs(): List<Spec> = listOf(
        Spec(
            id = QWEN_ASR_ID,
            label = JsVoicePluginStore.displayName(
                "qwen-asr", appContext.getString(R.string.js_voice_provider_qwen_asr)
            ),
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
            tags = JsVoicePluginStore.tags("qwen-asr", setOf(VOICE_TAG)),
        ),
        Spec(
            id = DOUBAO_ASR_ID,
            label = JsVoicePluginStore.displayName(
                "doubao-asr", appContext.getString(R.string.js_voice_provider_doubao_asr)
            ),
            version = "1.2.0",
            // 火山引擎语音识别只开这两个口子：bigmodel_async（流式）与 bigmodel_nostream（单次）。
            hosts = setOf("openspeech.bytedance.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/doubao-asr.js"),
            builtIn = true,
            originName = "doubao-asr.js",
            tags = JsVoicePluginStore.tags("doubao-asr", setOf(VOICE_TAG)),
        ),
        Spec(
            id = TENCENT_ASR_ID,
            label = JsVoicePluginStore.displayName(
                "tencent-asr", appContext.getString(R.string.js_voice_provider_tencent_asr)
            ),
            version = "1.0.7",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/tencent-asr.js"),
            builtIn = true,
            originName = "tencent-asr.js",
            tags = JsVoicePluginStore.tags("tencent-asr", setOf(VOICE_TAG)),
        ),
        Spec(
            id = TENCENT_TRANSLATE_ID,
            label = JsVoicePluginStore.displayName(
                "tencent-translate", appContext.getString(R.string.js_voice_provider_tencent_translate)
            ),
            version = "1.0.0",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/tencent-translate.js"),
            builtIn = true,
            originName = "tencent-translate.js",
            tags = JsVoicePluginStore.tags("tencent-translate", setOf(VOICE_TAG)),
        ),
    )

    private fun builtInAppSpecs(): List<Spec> {
        val meta = appContext.assets.open("bundled/js-apps/llm-assistant.json").bufferedReader().use {
            requireNotNull(JsVoicePluginMeta.parse(it.readText()))
        }
        require(meta.apps.isNotEmpty()) { "Bundled JS app plugin has no apps" }
        return listOf(
            Spec(
                id = meta.providerId,
                label = JsVoicePluginStore.displayName(meta.id, appContext.getString(R.string.ai_assistant)),
                version = meta.version,
                hosts = meta.hosts.toSet(),
                sampleRate = meta.sampleRate,
                source = Source.Asset("bundled/js-apps/llm-assistant.js"),
                builtIn = true,
                originName = "llm-assistant.js",
                tags = JsVoicePluginStore.tags(meta.id, setOf("AI")),
                hasVoice = meta.voiceAsr != null,
                apps = meta.apps,
            ),
        )
    }

    private fun importedSpecs(): List<Spec> = JsVoicePluginStore.list().map { record ->
        Spec(
            id = record.meta.providerId,
            label = JsVoicePluginStore.displayName(record.meta.id, record.meta.displayName),
            version = record.meta.version,
            hosts = record.meta.hosts.toSet(),
            sampleRate = record.meta.sampleRate,
            source = Source.Imported(JsVoicePluginStore.scriptFile(record.meta.id)),
            builtIn = false,
            originName = record.sourceName,
            tags = JsVoicePluginStore.tags(
                record.meta.id,
                if (record.meta.voiceAsr != null) setOf(VOICE_TAG) else emptySet(),
            ),
            hasVoice = record.meta.voiceAsr != null,
            apps = record.meta.apps,
        )
    }

    // Imported scripts can replace bundled plugins. Uninstalled bundled IDs stay hidden across
    // app upgrades until the user imports that ID again.
    private fun buildSpecs(): List<Spec> {
        val imported = importedSpecs()
        val builtInApps = builtInAppSpecs()
        val bundledAssistant = builtInApps.firstOrNull { it.id == LLM_ASSISTANT_ID }
        val hasSupersededAssistantImport = bundledAssistant != null && imported.any {
            it.id == bundledAssistant.id && !isNewerVersion(it.version, bundledAssistant.version)
        }
        val migratedLegacyAssistant = JsVoicePluginStore.migrateLegacyAssistantState(
            hasSupersededImport = hasSupersededAssistantImport,
        )
        val effectiveBuiltInApps = if (migratedLegacyAssistant) builtInAppSpecs() else builtInApps
        return activeSpecs(
            builtInSpecs = builtInSpecs() + effectiveBuiltInApps,
            importedSpecs = imported,
            disabledBuiltInIds = JsVoicePluginStore.disabledBuiltInIds(),
        )
    }

    internal fun activeSpecs(
        builtInSpecs: List<Spec>,
        importedSpecs: List<Spec>,
        disabledBuiltInIds: Set<String>,
    ): List<Spec> {
        val builtInById = builtInSpecs.associateBy { it.id }
        val activeImported = importedSpecs.filter { imported ->
            val bundled = builtInById[imported.id]
            bundled == null || bundled.apps.isEmpty() || isNewerVersion(imported.version, bundled.version)
        }
        val importedIds = activeImported.mapTo(mutableSetOf()) { it.id }
        val builtIn = builtInSpecs.filter {
            it.id !in importedIds && it.id.removePrefix("js:") !in disabledBuiltInIds
        }
        return activeImported + builtIn
    }

    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        fun parts(value: String): List<Long>? {
            val numeric = Regex("^v?(\\d+(?:\\.\\d+)*)(?:[-+].*)?$", RegexOption.IGNORE_CASE)
                .matchEntire(value.trim())?.groupValues?.get(1) ?: return null
            return numeric.split('.').map { it.toLongOrNull() ?: return null }
        }
        val candidateParts = parts(candidate) ?: return false
        val currentParts = parts(current) ?: return false
        for (index in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val newer = candidateParts.getOrElse(index) { 0L }
            val older = currentParts.getOrElse(index) { 0L }
            if (newer != older) return newer > older
        }
        return false
    }

    fun all(): List<Spec> = synchronized(lock) { cached ?: buildSpecs().also { cached = it; cachedById = it.associateBy(Spec::id) } }

    fun builtInIds(): Set<String> = (builtInSpecs() + builtInAppSpecs()).map { it.id }.toSet()

    fun voiceInputSpecs(): List<Spec> = voiceInputSpecs(all())

    internal fun voiceInputSpecs(specs: List<Spec>): List<Spec> =
        specs.filter { it.hasVoice && VOICE_TAG in it.tags }

    internal fun hasAiAssistantTag(tags: Set<String>): Boolean = tags.any { tag ->
        when (tag.trim().lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)) {
            "ai", "llm", "aillm", "ai助手", "llm助手", "aiassistant", "llmassistant",
            "人工智能", "大模型", "智能助手" -> true
            else -> false
        }
    }

    internal fun aiAssistantSpecs(specs: List<Spec>): List<Spec> =
        specs.filter { it.apps.isNotEmpty() && hasAiAssistantTag(it.tags) }

    internal fun importableBuiltInIds(
        builtInIds: Set<String>,
        disabledBuiltInIds: Set<String>,
        upgradingPluginId: String? = null,
    ): Set<String> = builtInIds.filterTo(mutableSetOf()) {
        it != upgradingPluginId && it.removePrefix("js:") !in disabledBuiltInIds
    }

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
        val specs = voiceInputSpecs()
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
