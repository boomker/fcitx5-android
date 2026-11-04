/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceField
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceFieldOption
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceFields
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputChoicesTest {

    @Test
    fun shuoDianLabelsDistinguishImeForegroundFromAidlBackground() {
        assertTrue(VoiceInputChoices.isShuoDian("说点啥"))
        assertTrue(VoiceInputChoices.isShuoDian("说点啥联动"))
        assertEquals("说点啥(前台)", VoiceInputChoices.shuoDianLabel(background = false))
        assertEquals("说点啥(后台)", VoiceInputChoices.shuoDianLabel(background = true))
    }

    @Test
    fun unrelatedLabelsAreNotRewritten() {
        assertFalse(VoiceInputChoices.isShuoDian("Fcitx Voice"))
        assertFalse(VoiceInputChoices.isShuoDian("BiBi Keyboard Link"))
    }

    @Test
    fun preferredPickerPutsShuoDianBackgroundAndDoubaoAfterShuoDianForeground() {
        fun entry(id: String, label: String) = VoiceInputChoices.Entry(id, label, "test")

        val unrelatedBefore = entry("js:qwen-asr", "通义千问语音 (JS)")
        val unrelatedAfter = entry("plugin:example", "Tencent ASR")
        val input = listOf(
            unrelatedBefore,
            entry(JsVoiceProviders.DOUBAO_ASR_ID, "豆包语音 (JS)"),
            entry("plugin:shuodian", VoiceInputChoices.shuoDianLabel(background = true)),
            unrelatedAfter,
            entry("ime:shuodian", VoiceInputChoices.shuoDianLabel(background = false)),
            entry("", "系统默认"),
        )

        assertEquals(
            listOf(
                "系统默认",
                "说点啥(前台)",
                "说点啥(后台)",
                "豆包语音 (JS)",
                "通义千问语音 (JS)",
                "Tencent ASR",
            ),
            VoiceInputChoices.sortedForDisplay(input).map { it.label },
        )
    }

    @Test
    fun bundledPluginsUseCanonicalApiWithLegacyAlias() {
        val prelude = File("src/main/java/org/fxboomk/fcitx5/android/input/voice/js/JsVoicePrelude.kt").readText()
        assertEquals(true, prelude.contains("var swan = {"))
        assertEquals(true, prelude.contains("var foxy = swan;"))

        val scripts = File("src/main/assets/bundled/js-voice").listFiles()
            ?.filter { it.extension == "js" }
            .orEmpty()
        assertEquals(4, scripts.size)
        scripts.forEach { script ->
            val source = script.readText()
            assertEquals(false, source.contains("foxy."))
            assertEquals(true, source.contains("swan."))
        }
    }

    @Test
    fun selectSummaryUsesLocalizedOptionLabelInsteadOfStoredValue() {
        val output = JsVoiceField(
            kind = JsVoiceField.KIND_SELECT,
            key = "output",
            label = "上屏文本",
            description = "选择输入法插入译文还是识别原文。",
            defaultValue = "target",
            options = listOf(
                JsVoiceFieldOption("译文", "target"),
                JsVoiceFieldOption("原文", "source"),
            ),
        )
        assertEquals("译文", JsVoiceFields.displayValue(output, "target"))
        assertEquals("原文", JsVoiceFields.displayValue(output, "source"))
        assertEquals("legacy", JsVoiceFields.displayValue(output, "legacy"))
    }
}
