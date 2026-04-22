/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmPromptTest {

    @Test
    fun systemPromptReflectsConfiguredCandidateLimit() {
        val prompt = LanLlmPrompt.systemPrompt(maxPredictionCandidates = 7)

        assertTrue(prompt.contains("给出 1-7 个自然续写候选"))
        assertTrue(prompt.contains("尽量给满 7 个候选"))
        assertTrue(prompt.contains("如果无法预测，也输出空数组"))
    }

    @Test
    fun completionPromptWithoutRecentBiasBuildsStructuredChatMlPrompt() {
        val prompt = LanLlmPrompt.completionPrompt(
            beforeCursor = "光标前文本内容",
            recentCommittedText = "最近上屏",
            historyText = "历史内容",
            useRecentCommitBias = false,
        )

        assertTrue(prompt.startsWith("<|im_start|>system"))
        assertTrue(prompt.contains("<history>\n历史内容\n</history>"))
        assertTrue(prompt.contains("<last_msg>\n无\n</last_msg>"))
        assertTrue(prompt.contains("<memory>\n无\n</memory>"))
        assertTrue(prompt.endsWith("</think>\n\n光标前文本内容"))
    }

    @Test
    fun completionPromptWithRecentBiasIncludesRecentCommitMemory() {
        val prompt = LanLlmPrompt.completionPrompt(
            beforeCursor = "继续写",
            recentCommittedText = "上一句",
            historyText = "更早内容",
            useRecentCommitBias = true,
        )

        assertTrue(prompt.contains("<history>\n更早内容\n</history>"))
        assertTrue(prompt.contains("<memory>\n最近一次上屏：上一句\n</memory>"))
        assertTrue(prompt.endsWith("</think>\n\n继续写"))
    }

    @Test
    fun completionPromptPartsExposeStructuredMessagesForCompatibleChatApis() {
        val system = LanLlmPrompt.completionSystemPrompt()
        val user = LanLlmPrompt.completionUserPrompt(
            beforeCursor = "今晚一起",
            recentCommittedText = "要不要",
            historyText = "我们在约饭",
            useRecentCommitBias = true,
        )
        val assistant = LanLlmPrompt.completionAssistantPrefill("今晚一起")

        assertTrue(system.contains("当前任务是“对话续写/补全”"))
        assertTrue(user.contains("<history>\n我们在约饭\n</history>"))
        assertTrue(user.contains("<memory>\n最近一次上屏：要不要\n</memory>"))
        assertTrue(user.endsWith("今晚一起\n</instruction>"))
        assertEquals("<think>\n\n</think>\n\n今晚一起", assistant)
    }

    @Test
    fun userPromptUsesStructuredContinuationContextForChatBackend() {
        val prompt = LanLlmPrompt.userPrompt(
            beforeCursor = "今晚一起去",
            recentCommittedText = "上条刚发完",
            historyText = "我们在约饭",
            useRecentCommitBias = true,
        )

        assertTrue(prompt.contains("<persona>"))
        assertTrue(prompt.contains("自然、得体、贴近上下文的中文续写助手"))
        assertTrue(prompt.contains("<history>\n我们在约饭\n</history>"))
        assertTrue(prompt.contains("<memory>\n最近一次上屏：上条刚发完\n</memory>"))
        assertTrue(prompt.contains("只输出当前前缀后面的续写部分"))
        assertTrue(prompt.endsWith("今晚一起去\n</instruction>"))
    }
}
