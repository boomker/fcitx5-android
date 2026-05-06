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
        assertTrue(prompt.contains("最多只允许 1 条候选超过这个长度"))
        assertTrue(prompt.contains("不要重复已有标点"))
        assertTrue(prompt.contains("， 。 ？ ！"))
        assertTrue(prompt.contains("如果无法预测，也输出空数组"))
    }

    @Test
    fun systemPromptUsesEnglishGuidanceForEnglishPrefix() {
        val prompt = LanLlmPrompt.systemPrompt(
            maxPredictionCandidates = 4,
            beforeCursor = "Let's meet at",
        )

        assertTrue(prompt.contains("English continuation assistant"))
        assertTrue(prompt.contains("Output JSON only"))
        assertTrue(prompt.contains("within 30 characters"))
        assertTrue(prompt.contains("leading space only when"))
        assertTrue(prompt.contains("candidate 1"))
    }

    @Test
    fun systemPromptSwitchesToQuestionAnswerMode() {
        val prompt = LanLlmPrompt.systemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "今天吃什么？",
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )

        assertTrue(prompt.contains("输入法应答助手"))
        assertTrue(prompt.contains("问题或请求"))
        assertTrue(prompt.contains("可直接上屏的回答"))
        assertTrue(prompt.contains("不要把它当成待续写前缀"))
        assertTrue(prompt.contains("只输出最终回答文本本身"))
        assertTrue(prompt.contains("不要为了简短而省略关键信息"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun systemPromptUsesLongFormAnswerLimitWhenQuestionAnswerAndLongFormEnabled() {
        val prompt = LanLlmPrompt.systemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "帮我回个稍微详细一点的答复",
            outputMode = LanLlmOutputMode.LongForm,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )

        assertTrue(prompt.contains("输入法应答助手"))
        assertTrue(prompt.contains("如果问题需要展开说明"))
        assertTrue(prompt.contains("尽量完整回答"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun systemPromptSwitchesToTranslateMode() {
        val prompt = LanLlmPrompt.systemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "今天晚上一起吃饭吗？",
            taskMode = LanLlmTaskMode.Translate,
        )

        assertTrue(prompt.contains("输入法翻译助手"))
        assertTrue(prompt.contains("整段中文文本翻译成自然、准确"))
        assertTrue(prompt.contains("只输出最终译文文本本身"))
        assertTrue(prompt.contains("不要续写，不要总结"))
        assertTrue(prompt.contains("英文译文中的单词之间必须保留正常空格"))
        assertTrue(!prompt.contains("中文续写助手"))
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
    fun completionPromptPartsSwitchToEnglishWhenPrefixLooksEnglish() {
        val system = LanLlmPrompt.completionSystemPrompt("Let's grab")
        val user = LanLlmPrompt.completionUserPrompt(
            beforeCursor = "Let's grab",
            recentCommittedText = "Dinner?",
            historyText = "We are planning tonight",
            useRecentCommitBias = true,
        )

        assertTrue(system.contains("Continue mainly in English"))
        assertTrue(system.contains("allow at most one continuation to exceed that limit"))
        assertTrue(system.contains("leading space only when"))
        assertTrue(user.contains("<persona>"))
        assertTrue(user.contains("English continuation assistant"))
        assertTrue(user.contains("Output only the continuation after the current prefix"))
        assertTrue(user.endsWith("Let's grab\n</instruction>"))
    }

    @Test
    fun completionPromptPartsSwitchToQuestionAnswerMode() {
        val system = LanLlmPrompt.completionSystemPrompt(
            beforeCursor = "今天午饭吃什么？",
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )
        val user = LanLlmPrompt.completionUserPrompt(
            beforeCursor = "今天午饭吃什么？",
            recentCommittedText = "早上刚开完会",
            historyText = "同事在群里讨论吃饭",
            useRecentCommitBias = true,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )
        val assistant = LanLlmPrompt.completionAssistantPrefill(
            beforeCursor = "今天午饭吃什么？",
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )

        assertTrue(system.contains("输入法应答助手"))
        assertTrue(system.contains("问答回复候选"))
        assertTrue(system.contains("不要把它当作待续写前缀"))
        assertTrue(user.contains("输入法应答助手"))
        assertTrue(user.contains("问题或请求"))
        assertTrue(user.contains("不要把它当成待续写前缀"))
        assertTrue(user.contains("不要为了简短而省略关键信息"))
        assertTrue(!user.contains("中文续写助手"))
        assertEquals("<think>\n\n</think>\n\n", assistant)
    }

    @Test
    fun completionPromptPartsUseLongFormLimitsWhenQuestionAnswerAndLongFormEnabled() {
        val system = LanLlmPrompt.completionSystemPrompt(
            beforeCursor = "帮我回一句稍微详细一点的话",
            outputMode = LanLlmOutputMode.LongForm,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )
        val user = LanLlmPrompt.completionUserPrompt(
            beforeCursor = "帮我回一句稍微详细一点的话",
            recentCommittedText = "对方刚发来邀请",
            historyText = "朋友在约周末活动",
            useRecentCommitBias = true,
            outputMode = LanLlmOutputMode.LongForm,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )

        assertTrue(system.contains("输入法应答助手"))
        assertTrue(user.contains("输入法应答助手"))
        assertTrue(system.contains("更展开回答"))
        assertTrue(user.contains("更展开回答"))
        assertTrue(!user.contains("中文续写助手"))
    }

    @Test
    fun completionPromptPartsSwitchToTranslateMode() {
        val system = LanLlmPrompt.completionSystemPrompt(
            beforeCursor = "Let's meet after lunch.",
            taskMode = LanLlmTaskMode.Translate,
        )
        val user = LanLlmPrompt.completionUserPrompt(
            beforeCursor = "Let's meet after lunch.",
            recentCommittedText = "",
            historyText = "",
            useRecentCommitBias = false,
            taskMode = LanLlmTaskMode.Translate,
        )
        val assistant = LanLlmPrompt.completionAssistantPrefill(
            beforeCursor = "Let's meet after lunch.",
            taskMode = LanLlmTaskMode.Translate,
        )

        assertTrue(system.contains("full-text translation"))
        assertTrue(system.contains("Translate the full input text into natural Chinese"))
        assertTrue(user.contains("Translate the full text below into natural Chinese"))
        assertTrue(user.contains("IME translation assistant"))
        assertTrue(!user.contains("English continuation assistant"))
        assertEquals("<think>\n\n</think>\n\n", assistant)
    }

    @Test
    fun completionAssistantPrefillSkipsForcedThinkBlockWhenThinkingEnabled() {
        val assistant = LanLlmPrompt.completionAssistantPrefill(
            beforeCursor = "今晚一起",
            enableThinking = true,
        )

        assertEquals("今晚一起", assistant)
    }

    @Test
    fun localOnDevicePromptIsShorterThanStructuredCompletionPrompt() {
        val compact = LanLlmPrompt.localOnDevicePrompt(
            beforeCursor = "今天是五一劳动节",
            recentCommittedText = "",
            historyText = "",
            maxPredictionCandidates = 1,
        )
        val structured = LanLlmPrompt.completionPrompt(
            beforeCursor = "今天是五一劳动节",
            recentCommittedText = "",
            historyText = "",
            useRecentCommitBias = false,
        )

        assertTrue(compact.length < structured.length)
        assertTrue(compact.contains("中文输入法续写助手"))
        assertTrue(!compact.contains("<history>"))
        assertTrue(!compact.contains("<think>"))
        assertTrue(!compact.contains("</think>"))
        assertTrue(compact.contains("前缀：今天是五一劳动节"))
    }

    @Test
    fun localOnDeviceQuestionAnswerPromptAlsoOmitsThinkTags() {
        val prompt = LanLlmPrompt.localOnDevicePrompt(
            beforeCursor = "今晚吃什么？",
            recentCommittedText = "中午刚吃了面",
            historyText = "同事在讨论晚饭",
            maxPredictionCandidates = 1,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )

        assertTrue(!prompt.contains("<think>"))
        assertTrue(!prompt.contains("</think>"))
        assertTrue(prompt.contains("问题：今晚吃什么？"))
    }

    @Test
    fun userPromptUsesTranslatorPersonaAndEnglishSpacingInstructionForChineseTranslateMode() {
        val prompt = LanLlmPrompt.userPrompt(
            beforeCursor = "今天晚上一起吃饭吗？",
            recentCommittedText = "",
            historyText = "",
            useRecentCommitBias = false,
            taskMode = LanLlmTaskMode.Translate,
        )

        assertTrue(prompt.contains("<persona>"))
        assertTrue(prompt.contains("输入法翻译助手"))
        assertTrue(prompt.contains("英文单词之间必须保留正常空格"))
        assertTrue(!prompt.contains("中文续写助手"))
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
        assertTrue(prompt.contains("今晚一起去\n</instruction>"))
    }

    @Test
    fun completionSystemPromptMentionsChineseLeadingPunctuationDecision() {
        val system = LanLlmPrompt.completionSystemPrompt("翩若惊鸿")

        assertTrue(system.contains("不要重复已有标点"))
        assertTrue(system.contains("自然判断是否需要以中文全角标点开头"))
    }

    @Test
    fun userPromptUsesChineseContinuationInstructionForLeadingPunctuationDecision() {
        val prompt = LanLlmPrompt.userPrompt(
            beforeCursor = "翩若惊鸿",
            recentCommittedText = "",
            historyText = "洛神赋",
            useRecentCommitBias = false,
        )

        assertTrue(prompt.contains("请基于上下文，自然地续写我的输入"))
        assertTrue(prompt.endsWith("翩若惊鸿\n</instruction>"))
    }

    @Test
    fun userPromptUsesEnglishContinuationInstructionForEnglishPrefix() {
        val prompt = LanLlmPrompt.userPrompt(
            beforeCursor = "Please send me",
            recentCommittedText = "the draft",
            historyText = "Need a quick follow-up",
            useRecentCommitBias = true,
        )

        assertTrue(prompt.contains("English continuation assistant"))
        assertTrue(prompt.contains("Continue my input naturally based on the context"))
        assertTrue(prompt.endsWith("Please send me\n</instruction>"))
    }

    @Test
    fun userPromptUsesQuestionAnswerInstructionWhenEnabled() {
        val prompt = LanLlmPrompt.userPrompt(
            beforeCursor = "帮我回一句礼貌一点的话",
            recentCommittedText = "对方刚发来邀请",
            historyText = "朋友约我周末见面",
            useRecentCommitBias = true,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )

        assertTrue(prompt.contains("输入法应答助手"))
        assertTrue(prompt.contains("问题或请求"))
        assertTrue(prompt.contains("给出一条自然、完整、可直接发送的回答"))
        assertTrue(!prompt.contains("中文续写助手"))
        assertTrue(prompt.endsWith("帮我回一句礼貌一点的话\n</instruction>"))
    }
}
