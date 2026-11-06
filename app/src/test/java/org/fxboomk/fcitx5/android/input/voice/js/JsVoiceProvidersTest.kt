/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val utility = spec("js:utility", builtIn = false, tags = setOf("utility"))

        assertEquals(listOf(voice), JsVoiceProviders.voiceInputSpecs(listOf(voice, utility)))
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
