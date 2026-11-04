/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import org.junit.Assert.assertEquals
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
}
