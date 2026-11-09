/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import org.fxboomk.fcitx5.android.input.assistant.AiAssistant
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsPluginCatalogTest {

    @Test
    fun importedScriptOverridesBundledPluginAndDisabledBundledPluginStaysHidden() {
        val bundledOverride = spec("js:qwen-asr", builtIn = true)
        val bundledDisabled = spec("js:doubao-asr", builtIn = true)
        val bundledActive = spec("js:tencent-asr", builtIn = true)
        val userOverride = spec("js:qwen-asr", builtIn = false)
        val imported = spec("js:custom", builtIn = false)

        val active = JsPluginCatalog.activeSpecs(
            builtInSpecs = listOf(bundledOverride, bundledDisabled, bundledActive),
            importedSpecs = listOf(userOverride, imported),
            disabledBuiltInIds = setOf("doubao-asr"),
        )

        assertEquals(listOf(userOverride, imported, bundledActive), active)
    }

    @Test
    fun bundledAssistantReplacesAnOlderImportedCopyButKeepsNewerUpgrades() {
        val bundled = spec("js:llm-assistant", builtIn = true, tags = setOf("AI")).copy(
            version = "1.23.5",
            hasVoice = false,
            apps = listOf(JsPluginApp("llm_assistant", "AI Assistant", emptySet())),
        )
        val oldImport = bundled.copy(
            builtIn = false,
            version = "1.23.3-fx",
            source = JsPluginCatalog.Source.Imported(File("old-import.js")),
        )
        val upgrade = oldImport.copy(version = "1.24.0", source = JsPluginCatalog.Source.Imported(File("upgrade.js")))

        assertEquals(listOf(bundled), JsPluginCatalog.activeSpecs(listOf(bundled), listOf(oldImport), emptySet()))
        assertEquals(listOf(upgrade), JsPluginCatalog.activeSpecs(listOf(bundled), listOf(upgrade), emptySet()))
    }

    @Test
    fun legacyAssistantMigrationRestoresItsDefaultTagOnceAndKeepsOtherUninstalls() {
        val migrated = JsPluginStore.legacyAssistantMigrationState(
            currentTags = emptySet(),
            disabledBuiltInIds = setOf("llm-assistant", "qwen-asr"),
            hasSupersededImport = false,
            migrationCompleted = false,
        )!!

        assertEquals(setOf("AI"), migrated.tags)
        assertEquals(setOf("qwen-asr"), migrated.disabledBuiltInIds)
        assertNull(
            JsPluginStore.legacyAssistantMigrationState(
                currentTags = emptySet(),
                disabledBuiltInIds = emptySet(),
                hasSupersededImport = false,
                migrationCompleted = true,
            ),
        )
    }

    @Test
    fun migrationDoesNotOverrideAnExplicitlyRemovedAiTag() {
        assertNull(
            JsPluginStore.legacyAssistantMigrationState(
                currentTags = setOf("custom"),
                disabledBuiltInIds = setOf("llm-assistant"),
                hasSupersededImport = false,
                migrationCompleted = false,
            ),
        )
        assertNull(
            JsPluginStore.legacyAssistantMigrationState(
                currentTags = emptySet(),
                disabledBuiltInIds = emptySet(),
                hasSupersededImport = false,
                migrationCompleted = false,
            ),
        )
        assertNull(
            JsPluginStore.legacyAssistantMigrationState(
                currentTags = null,
                disabledBuiltInIds = setOf("llm-assistant"),
                hasSupersededImport = false,
                migrationCompleted = false,
            ),
        )
    }

    @Test
    fun supersededAssistantImportMigratesTagsWithoutChangingOtherTombstones() {
        val migrated = JsPluginStore.legacyAssistantMigrationState(
            currentTags = setOf("custom"),
            disabledBuiltInIds = setOf("qwen-asr"),
            hasSupersededImport = true,
            migrationCompleted = false,
        )!!

        assertEquals(setOf("AI", "custom"), migrated.tags)
        assertEquals(setOf("qwen-asr"), migrated.disabledBuiltInIds)
    }

    @Test
    fun upgradeCanReplaceItsBundledIdWithoutAllowingOtherBundledIds() {
        val meta = JsPluginMeta.parse(
            """{"id":"qwen-asr","hosts":["example.com"],"features":[{"type":"voice.asr"}]}"""
        )!!

        assertEquals(
            JsPluginImportResult.Failure.Reason.RESERVED_ID,
            JsPluginImportPolicy.validate(meta, setOf("js:qwen-asr", "js:doubao-asr")),
        )
        val importableBuiltIns = JsPluginCatalog.importableBuiltInIds(
            builtInIds = setOf("js:qwen-asr", "js:doubao-asr"),
            disabledBuiltInIds = emptySet(),
            upgradingPluginId = "js:qwen-asr",
        )
        assertEquals(setOf("js:doubao-asr"), importableBuiltIns)
        assertNull(JsPluginImportPolicy.validate(meta, importableBuiltIns))
    }

    @Test
    fun uninstalledBundledIdsCanBeImportedAgain() {
        assertEquals(
            setOf("js:qwen-asr"),
            JsPluginCatalog.importableBuiltInIds(
                builtInIds = setOf("js:qwen-asr", "js:doubao-asr"),
                disabledBuiltInIds = setOf("doubao-asr"),
            ),
        )
    }

    @Test
    fun onlyPluginsTaggedForVoiceInputAreRegisteredAsVoiceProviders() {
        val voice = spec("js:voice", builtIn = false, tags = setOf(JsPluginCatalog.VOICE_TAG))
        val utility = spec("js:utility", builtIn = false, tags = setOf(JsPluginCatalog.VOICE_TAG)).copy(
            hasVoice = false,
            apps = listOf(JsPluginApp("llm_assistant", "LLM Assistant", emptySet())),
        )

        assertEquals(listOf(voice), JsVoiceProviders.voiceInputSpecs(listOf(voice, utility)))
    }

    @Test
    fun aPluginCanProvideVoiceInputAndAssistantAppsAtTheSameTime() {
        val combined = spec("js:combined", builtIn = false, tags = setOf(JsPluginCatalog.VOICE_TAG, "AI")).copy(
            apps = listOf(JsPluginApp("assistant", "AI Assistant", emptySet())),
        )

        assertEquals(listOf(combined), JsVoiceProviders.voiceInputSpecs(listOf(combined)))
        assertEquals(listOf(combined), AiAssistant.aiAssistantSpecs(listOf(combined)))
    }

    @Test
    fun appOnlyPluginMetadataIsAcceptedWithoutVoiceFeature() {
        val meta = JsPluginMeta.parse(
            """{"id":"llm-assistant","name":"LLM Assistant","version":"1.1","hosts":[],"features":[],"apps":[{"id":"llm_assistant","label":"LLM Assistant","permissions":["editor.read_selection","network.request"]}]}"""
        )!!

        assertEquals(null, meta.voiceAsr)
        assertEquals("llm_assistant", meta.apps.single().id)
        assertEquals(null, JsPluginImportPolicy.validate(meta, emptySet()))
        assertEquals(
            emptyList<JsPluginCatalog.Spec>(),
            JsVoiceProviders.voiceInputSpecs(listOf(spec("js:llm-assistant", false).copy(
                hasVoice = meta.voiceAsr != null,
                apps = meta.apps,
            ))),
        )
    }

    @Test
    fun aiAssistantClassificationRequiresAnAiPurposeTag() {
        assertEquals(true, AiAssistant.hasAiAssistantTag(setOf("LLM")))
        assertEquals(true, AiAssistant.hasAiAssistantTag(setOf("AI 助手")))
        assertEquals(true, AiAssistant.hasAiAssistantTag(setOf("大模型")))
        assertEquals(false, AiAssistant.hasAiAssistantTag(setOf("语音")))
        assertEquals(false, AiAssistant.hasAiAssistantTag(emptySet()))
    }

    @Test
    fun configuredHttpsUrlSettingsBecomeHostApprovalCandidates() {
        val fields = JsPluginFields.parse(
            """[{"key":"deepseekUrl","kind":"text"},{"key":"openaiUrl","kind":"text"},{"key":"note","kind":"text"}]"""
        )

        val hosts = JsHostHelpers.configuredHttpsHosts(
            declaredHosts = setOf("voice.example.com"),
            fields = fields,
            values = mapOf(
                "deepseekUrl" to " https://API.Example.com/v1/chat/completions ",
                "openaiUrl" to "http://insecure.example.com/v1",
                "note" to "https://not-an-endpoint.example.com",
            ),
        )

        assertEquals(setOf("voice.example.com", "api.example.com"), hosts)
    }

    @Test
    fun builtInAppPluginCanBeHiddenByUninstallTombstone() {
        val app = spec("js:llm-assistant", builtIn = true, tags = setOf("AI")).copy(
            hasVoice = false,
            apps = listOf(JsPluginApp("llm_assistant", "LLM Assistant", emptySet())),
        )

        assertEquals(listOf(app), JsPluginCatalog.activeSpecs(listOf(app), emptyList(), emptySet()))
        assertEquals(
            emptyList<JsPluginCatalog.Spec>(),
            JsPluginCatalog.activeSpecs(listOf(app), emptyList(), setOf("llm-assistant")),
        )
        assertEquals(
            setOf("js:llm-assistant"),
            JsPluginCatalog.importableBuiltInIds(setOf("js:llm-assistant"), emptySet()),
        )
    }

    @Test
    fun bundledLlmAssistantScriptLoadsAsAnAppOnlyPlugin() = runBlocking {
        val candidates = listOf(
            File("src/main/assets/bundled/js-apps/llm-assistant.js"),
            File("app/src/main/assets/bundled/js-apps/llm-assistant.js"),
        )
        val script = candidates.first(File::isFile).readText()
        val result = JsPluginImporter.inspect(
            script = script,
            locale = "zh-CN",
            builtInIds = emptySet(),
        )

        val success = result as JsPluginImportResult.Success
        assertEquals("llm-assistant", success.meta.id)
        assertEquals("1.23.7", success.meta.version)
        assertEquals("AI 助手", success.meta.name)
        assertEquals(emptyList<String>(), success.meta.hosts)
        assertEquals(null, success.meta.voiceAsr)
        assertEquals("llm_assistant", success.meta.apps.single().id)
        assertEquals(true, success.fieldCount > 0)

        val fields = JsScript.describe(script, "zh-CN")
        assertEquals(
            listOf("简答", "润色", "翻译", "中译英", "火星文", "调整语气", "成语接龙", "纠正错别字", "白话转文言"),
            fields.single { it.key == "defaultPurpose" }.options.map { it.label },
        )
        assertEquals("https://api.deepseek.com", fields.single { it.key == "deepseekUrl" }.defaultValue)
        assertEquals("deepseek-flash", fields.single { it.key == "deepseekModel" }.defaultValue)
        assertEquals("https://api.openai.com/v1", fields.single { it.key == "openaiUrl" }.defaultValue)
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            fields.single { it.key == "qwenUrl" }.defaultValue,
        )
        assertEquals("https://api.anthropic.com/v1", fields.single { it.key == "anthropicUrl" }.defaultValue)
        assertEquals("", fields.single { it.key == "customUrl" }.defaultValue)
        assertEquals("short", fields.single { it.key == "defaultPurpose" }.defaultValue)
        assertEquals("deepseek", fields.single { it.key == "defaultProvider" }.defaultValue)

        val keys = fields.map(JsPluginField::key)
        assertTrue(keys.indexOf("defaultPurpose") < keys.indexOf("defaultProvider"))
        listOf("deepseek", "openai", "qwen", "anthropic", "custom").forEach { provider ->
            val url = keys.indexOf("${provider}Url")
            val secret = keys.indexOf("${provider}Key")
            val protocol = keys.indexOf("${provider}Protocol")
            assertTrue("$provider URL must precede its key and protocol", url >= 0 && secret > url && protocol > secret)
            assertEquals("API 地址", fields.single { it.key == "${provider}Url" }.label)
            assertEquals("4096", fields.single { it.key == "${provider}MaxTokens" }.defaultValue)
            assertEquals(false, fields.single { it.key == "${provider}Key" }.resettable)
            assertEquals(false, fields.single { it.key == "${provider}Model" }.resettable)
            assertEquals("chat", fields.single { it.key == "${provider}Protocol" }.resetValue)
        }
    }

    @Test
    fun removingAiPurposeTagHidesPluginFromAssistantSettingsList() {
        val tagged = spec("js:llm", builtIn = false, tags = setOf("AI")).copy(
            hasVoice = false,
            apps = listOf(JsPluginApp("llm_assistant", "LLM Assistant", emptySet())),
        )
        val untagged = tagged.copy(tags = emptySet())

        assertEquals(listOf(tagged), AiAssistant.aiAssistantSpecs(listOf(tagged, untagged)))
        assertEquals(emptyList<JsPluginCatalog.Spec>(), AiAssistant.aiAssistantSpecs(listOf(untagged)))
    }

    private fun spec(id: String, builtIn: Boolean, tags: Set<String> = setOf(JsPluginCatalog.VOICE_TAG)) = JsPluginCatalog.Spec(
        id = id,
        label = id,
        version = "1.0",
        hosts = emptySet(),
        sampleRate = JsPluginMeta.DEFAULT_SAMPLE_RATE,
        source = if (builtIn) {
            JsPluginCatalog.Source.Asset("$id.js")
        } else {
            JsPluginCatalog.Source.Imported(File("$id.js"))
        },
        builtIn = builtIn,
        originName = "$id.js",
        tags = tags,
    )
}
