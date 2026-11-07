/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.appContext
import java.io.File
import java.util.Locale

/** Shared catalog of bundled and imported plugins, independent of capability execution. */
internal object JsPluginCatalog {

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
    @Volatile
    var revision = 0L
        private set

    private fun builtInSpecs(): List<Spec> = listOf(
        Spec(
            id = QWEN_ASR_ID,
            label = JsPluginStore.displayName(
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
            tags = JsPluginStore.tags("qwen-asr", setOf(VOICE_TAG)),
        ),
        Spec(
            id = DOUBAO_ASR_ID,
            label = JsPluginStore.displayName(
                "doubao-asr", appContext.getString(R.string.js_voice_provider_doubao_asr)
            ),
            version = "1.2.0",
            // 火山引擎语音识别只开这两个口子：bigmodel_async（流式）与 bigmodel_nostream（单次）。
            hosts = setOf("openspeech.bytedance.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/doubao-asr.js"),
            builtIn = true,
            originName = "doubao-asr.js",
            tags = JsPluginStore.tags("doubao-asr", setOf(VOICE_TAG)),
        ),
        Spec(
            id = TENCENT_ASR_ID,
            label = JsPluginStore.displayName(
                "tencent-asr", appContext.getString(R.string.js_voice_provider_tencent_asr)
            ),
            version = "1.0.7",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/tencent-asr.js"),
            builtIn = true,
            originName = "tencent-asr.js",
            tags = JsPluginStore.tags("tencent-asr", setOf(VOICE_TAG)),
        ),
        Spec(
            id = TENCENT_TRANSLATE_ID,
            label = JsPluginStore.displayName(
                "tencent-translate", appContext.getString(R.string.js_voice_provider_tencent_translate)
            ),
            version = "1.0.0",
            hosts = setOf("asr.cloud.tencent.com"),
            sampleRate = 16000,
            source = Source.Asset("bundled/js-voice/tencent-translate.js"),
            builtIn = true,
            originName = "tencent-translate.js",
            tags = JsPluginStore.tags("tencent-translate", setOf(VOICE_TAG)),
        ),
    )

    private fun builtInAppSpecs(): List<Spec> {
        val meta = appContext.assets.open("bundled/js-apps/llm-assistant.json").bufferedReader().use {
            requireNotNull(JsPluginMeta.parse(it.readText()))
        }
        require(meta.apps.isNotEmpty()) { "Bundled JS app plugin has no apps" }
        return listOf(
            Spec(
                id = meta.providerId,
                label = JsPluginStore.displayName(meta.id, appContext.getString(R.string.ai_assistant)),
                version = meta.version,
                hosts = meta.hosts.toSet(),
                sampleRate = meta.sampleRate,
                source = Source.Asset("bundled/js-apps/llm-assistant.js"),
                builtIn = true,
                originName = "llm-assistant.js",
                tags = JsPluginStore.tags(meta.id, setOf("AI")),
                hasVoice = meta.voiceAsr != null,
                apps = meta.apps,
            ),
        )
    }

    private fun importedSpecs(): List<Spec> = JsPluginStore.list().map { record ->
        Spec(
            id = record.meta.providerId,
            label = JsPluginStore.displayName(record.meta.id, record.meta.displayName),
            version = record.meta.version,
            hosts = record.meta.hosts.toSet(),
            sampleRate = record.meta.sampleRate,
            source = Source.Imported(JsPluginStore.scriptFile(record.meta.id)),
            builtIn = false,
            originName = record.sourceName,
            tags = JsPluginStore.tags(
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
        val migratedLegacyAssistant = JsPluginStore.migrateLegacyAssistantState(
            hasSupersededImport = hasSupersededAssistantImport,
        )
        val effectiveBuiltInApps = if (migratedLegacyAssistant) builtInAppSpecs() else builtInApps
        return activeSpecs(
            builtInSpecs = builtInSpecs() + effectiveBuiltInApps,
            importedSpecs = imported,
            disabledBuiltInIds = JsPluginStore.disabledBuiltInIds(),
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
    suspend fun describe(pluginId: String, locale: String = Locale.getDefault().toLanguageTag()): List<JsPluginField> {
        val spec = specOf(pluginId) ?: return emptyList()
        return JsScript.describe(readScript(spec), locale)
    }

    /** Import, removal or tag changes invalidate all cached plugin declarations. */
    fun invalidate() = synchronized(lock) {
        cached = null
        cachedById = emptyMap()
        revision++
    }
}
