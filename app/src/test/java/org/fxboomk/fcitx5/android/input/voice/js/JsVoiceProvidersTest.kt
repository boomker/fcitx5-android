/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsVoiceProvidersTest {

    @Test
    fun importedScriptOverridesBundledPluginAndDisabledBundledPluginStaysHidden() {
        val bundledOverride = spec("js:qwen-asr", builtIn = true)
        val bundledDisabled = spec("js:doubao-asr", builtIn = true)
        val bundledActive = spec("js:tencent-asr", builtIn = true)
        val userOverride = spec("js:qwen-asr", builtIn = false)
        val imported = spec("js:custom", builtIn = false)

        val active = JsVoiceProviders.activeSpecs(
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
            source = JsVoiceProviders.Source.Imported(File("old-import.js")),
        )
        val upgrade = oldImport.copy(version = "1.24.0", source = JsVoiceProviders.Source.Imported(File("upgrade.js")))

        assertEquals(listOf(bundled), JsVoiceProviders.activeSpecs(listOf(bundled), listOf(oldImport), emptySet()))
        assertEquals(listOf(upgrade), JsVoiceProviders.activeSpecs(listOf(bundled), listOf(upgrade), emptySet()))
    }

    @Test
    fun legacyAssistantMigrationRestoresItsDefaultTagOnceAndKeepsOtherUninstalls() {
        val migrated = JsVoicePluginStore.legacyAssistantMigrationState(
            currentTags = emptySet(),
            disabledBuiltInIds = setOf("llm-assistant", "qwen-asr"),
            hasSupersededImport = false,
            migrationCompleted = false,
        )!!

        assertEquals(setOf("AI"), migrated.tags)
        assertEquals(setOf("qwen-asr"), migrated.disabledBuiltInIds)
        assertNull(
            JsVoicePluginStore.legacyAssistantMigrationState(
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
            JsVoicePluginStore.legacyAssistantMigrationState(
                currentTags = setOf("custom"),
                disabledBuiltInIds = setOf("llm-assistant"),
                hasSupersededImport = false,
                migrationCompleted = false,
            ),
        )
        assertNull(
            JsVoicePluginStore.legacyAssistantMigrationState(
                currentTags = emptySet(),
                disabledBuiltInIds = emptySet(),
                hasSupersededImport = false,
                migrationCompleted = false,
            ),
        )
        assertNull(
            JsVoicePluginStore.legacyAssistantMigrationState(
                currentTags = null,
                disabledBuiltInIds = setOf("llm-assistant"),
                hasSupersededImport = false,
                migrationCompleted = false,
            ),
        )
    }

    @Test
    fun supersededAssistantImportMigratesTagsWithoutChangingOtherTombstones() {
        val migrated = JsVoicePluginStore.legacyAssistantMigrationState(
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
        val meta = JsVoicePluginMeta.parse(
            """{"id":"qwen-asr","hosts":["example.com"],"features":[{"type":"voice.asr"}]}"""
        )!!

        assertEquals(
            JsVoiceImportResult.Failure.Reason.RESERVED_ID,
            JsVoiceImportPolicy.validate(meta, setOf("js:qwen-asr", "js:doubao-asr")),
        )
        val importableBuiltIns = JsVoiceProviders.importableBuiltInIds(
            builtInIds = setOf("js:qwen-asr", "js:doubao-asr"),
            disabledBuiltInIds = emptySet(),
            upgradingPluginId = "js:qwen-asr",
        )
        assertEquals(setOf("js:doubao-asr"), importableBuiltIns)
        assertNull(JsVoiceImportPolicy.validate(meta, importableBuiltIns))
    }

    @Test
    fun uninstalledBundledIdsCanBeImportedAgain() {
        assertEquals(
            setOf("js:qwen-asr"),
            JsVoiceProviders.importableBuiltInIds(
                builtInIds = setOf("js:qwen-asr", "js:doubao-asr"),
                disabledBuiltInIds = setOf("doubao-asr"),
            ),
        )
    }

    @Test
    fun onlyPluginsTaggedForVoiceInputAreRegisteredAsVoiceProviders() {
        val voice = spec("js:voice", builtIn = false, tags = setOf(JsVoiceProviders.VOICE_TAG))
        val utility = spec("js:utility", builtIn = false, tags = setOf(JsVoiceProviders.VOICE_TAG)).copy(
            hasVoice = false,
            apps = listOf(JsPluginApp("llm_assistant", "LLM Assistant", emptySet())),
        )

        assertEquals(listOf(voice), JsVoiceProviders.voiceInputSpecs(listOf(voice, utility)))
    }

    @Test
    fun appOnlyPluginMetadataIsAcceptedWithoutVoiceFeature() {
        val meta = JsVoicePluginMeta.parse(
            """{"id":"llm-assistant","name":"LLM Assistant","version":"1.1","hosts":[],"features":[],"apps":[{"id":"llm_assistant","label":"LLM Assistant","permissions":["editor.read_selection","network.request"]}]}"""
        )!!

        assertEquals(null, meta.voiceAsr)
        assertEquals("llm_assistant", meta.apps.single().id)
        assertEquals(null, JsVoiceImportPolicy.validate(meta, emptySet()))
        assertEquals(
            emptyList<JsVoiceProviders.Spec>(),
            JsVoiceProviders.voiceInputSpecs(listOf(spec("js:llm-assistant", false).copy(
                hasVoice = meta.voiceAsr != null,
                apps = meta.apps,
            ))),
        )
    }

    @Test
    fun aiAssistantClassificationRequiresAnAiPurposeTag() {
        assertEquals(true, JsVoiceProviders.hasAiAssistantTag(setOf("LLM")))
        assertEquals(true, JsVoiceProviders.hasAiAssistantTag(setOf("AI 助手")))
        assertEquals(true, JsVoiceProviders.hasAiAssistantTag(setOf("大模型")))
        assertEquals(false, JsVoiceProviders.hasAiAssistantTag(setOf("语音")))
        assertEquals(false, JsVoiceProviders.hasAiAssistantTag(emptySet()))
    }

    @Test
    fun configuredHttpsUrlSettingsBecomeHostApprovalCandidates() {
        val fields = JsVoiceFields.parse(
            """[{"key":"deepseekUrl","kind":"text"},{"key":"openaiUrl","kind":"text"},{"key":"note","kind":"text"}]"""
        )

        val hosts = JsVoiceHelpers.configuredHttpsHosts(
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

        assertEquals(listOf(app), JsVoiceProviders.activeSpecs(listOf(app), emptyList(), emptySet()))
        assertEquals(
            emptyList<JsVoiceProviders.Spec>(),
            JsVoiceProviders.activeSpecs(listOf(app), emptyList(), setOf("llm-assistant")),
        )
        assertEquals(
            setOf("js:llm-assistant"),
            JsVoiceProviders.importableBuiltInIds(setOf("js:llm-assistant"), emptySet()),
        )
    }

    @Test
    fun bundledLlmAssistantScriptLoadsAsAnAppOnlyPlugin() = runBlocking {
        val candidates = listOf(
            File("src/main/assets/bundled/js-apps/llm-assistant.js"),
            File("app/src/main/assets/bundled/js-apps/llm-assistant.js"),
        )
        val script = candidates.first(File::isFile).readText()
        val result = JsVoiceImporter.inspect(
            script = script,
            locale = "zh-CN",
            builtInIds = emptySet(),
        )

        val success = result as JsVoiceImportResult.Success
        assertEquals("llm-assistant", success.meta.id)
        assertEquals("1.23.5", success.meta.version)
        assertEquals("AI 助手", success.meta.name)
        assertEquals(emptyList<String>(), success.meta.hosts)
        assertEquals(null, success.meta.voiceAsr)
        assertEquals("llm_assistant", success.meta.apps.single().id)
        assertEquals(true, success.fieldCount > 0)

        val fields = JsVoiceScript.describe(script, "zh-CN")
        assertEquals(
            listOf("简答", "润色", "中译英", "火星文", "调整语气", "成语接龙", "纠正错别字", "白话转文言"),
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

        val keys = fields.map(JsVoiceField::key)
        assertTrue(keys.indexOf("defaultPurpose") < keys.indexOf("defaultProvider"))
        listOf("deepseek", "openai", "qwen", "anthropic", "custom").forEach { provider ->
            val url = keys.indexOf("${provider}Url")
            val secret = keys.indexOf("${provider}Key")
            val protocol = keys.indexOf("${provider}Protocol")
            assertTrue("$provider URL must precede its key and protocol", url >= 0 && secret > url && protocol > secret)
        }
    }

    @Test
    fun removingAiPurposeTagHidesPluginFromAssistantSettingsList() {
        val tagged = spec("js:llm", builtIn = false, tags = setOf("AI")).copy(
            hasVoice = false,
            apps = listOf(JsPluginApp("llm_assistant", "LLM Assistant", emptySet())),
        )
        val untagged = tagged.copy(tags = emptySet())

        assertEquals(listOf(tagged), JsVoiceProviders.aiAssistantSpecs(listOf(tagged, untagged)))
        assertEquals(emptyList<JsVoiceProviders.Spec>(), JsVoiceProviders.aiAssistantSpecs(listOf(untagged)))
    }

    private fun spec(id: String, builtIn: Boolean, tags: Set<String> = setOf(JsVoiceProviders.VOICE_TAG)) = JsVoiceProviders.Spec(
        id = id,
        label = id,
        version = "1.0",
        hosts = emptySet(),
        sampleRate = JsVoicePluginMeta.DEFAULT_SAMPLE_RATE,
        source = if (builtIn) {
            JsVoiceProviders.Source.Asset("$id.js")
        } else {
            JsVoiceProviders.Source.Imported(File("$id.js"))
        },
        builtIn = builtIn,
        originName = "$id.js",
        tags = tags,
    )
}
