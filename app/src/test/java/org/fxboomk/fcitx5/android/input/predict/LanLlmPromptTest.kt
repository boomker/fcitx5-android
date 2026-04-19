/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmPromptTest {

    @Test
    fun completionPromptWithoutRecentBiasFallsBackToBeforeCursorOnly() {
        val prompt = LanLlmPrompt.completionPrompt(
            beforeCursor = "光标前文本内容",
            recentCommittedText = "最近上屏",
            historyText = "历史内容",
            useRecentCommitBias = false,
        )

        assertEquals("光标前文本：光标前文本内容", prompt)
    }

    @Test
    fun completionPromptWithRecentBiasIncludesRecentCommitAndHistory() {
        val prompt = LanLlmPrompt.completionPrompt(
            beforeCursor = "继续写",
            recentCommittedText = "上一句",
            historyText = "更早内容",
            useRecentCommitBias = true,
        )

        assertTrue(prompt.contains("最近一次上屏：上一句"))
        assertTrue(prompt.contains("近期上屏：更早内容"))
        assertTrue(prompt.endsWith("光标前文本：继续写"))
    }
}
