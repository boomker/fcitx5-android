/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPromptTest {

    @Test
    fun systemPromptReflectsConfiguredCandidateLimit() {
        val prompt = LlmPrompt.systemPrompt(maxPredictionCandidates = 7)

        assertTrue(prompt.contains("<persona>"))
        assertTrue(prompt.contains("<output_contract>"))
        assertTrue(prompt.contains("<rules>"))
        assertTrue(prompt.contains("给出 1-7 个自然续写候选"))
        assertTrue(prompt.contains("尽量给满 7 个候选"))
        assertTrue(prompt.contains("最多只允许 1 条候选超过这个长度"))
        assertTrue(prompt.contains("不要重复已有标点"))
        assertTrue(prompt.contains("如果无法预测，也输出空数组"))
    }

    @Test
    fun systemPromptUsesEnglishGuidanceForEnglishPrefix() {
        val prompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = 4,
            beforeCursor = "Let's meet at",
        )

        assertTrue(prompt.contains("<persona>"))
        assertTrue(prompt.contains("<task>\ndialogue continuation/autocomplete\n</task>"))
        assertTrue(prompt.contains("English continuation assistant"))
        assertTrue(prompt.contains("Output JSON only"))
        assertTrue(prompt.contains("within 30 characters"))
        assertTrue(prompt.contains("leading space only when"))
        assertTrue(prompt.contains("candidate 1"))
    }

    @Test
    fun systemPromptIncludesConfiguredPersonaStyleHint() {
        val prompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = 4,
            beforeCursor = "今晚吃点什么",
            personaPreset = LlmPrefs.PersonaPreset.SocialStar,
        )

        assertTrue(prompt.contains("<style_hint>"))
        assertTrue(prompt.contains("情商在线，幽默风趣的社交达人。"))
    }

    @Test
    fun systemPromptSwitchesToQuestionAnswerMode() {
        val prompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "今天吃什么？",
            taskMode = LlmTaskMode.QuestionAnswer,
        )

        assertTrue(prompt.contains("输入法应答助手"))
        assertTrue(prompt.contains("<task>\n问答回复候选\n</task>"))
        assertTrue(prompt.contains("只输出 1 条最终回答文本本身"))
        assertTrue(prompt.contains("将 INSTRUCTION 视为完整的问题或请求本身"))
        assertTrue(prompt.contains("不要为了简短而省略关键信息"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun systemPromptUsesLongFormAnswerLimitWhenQuestionAnswerAndLongFormEnabled() {
        val prompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "帮我回个稍微详细一点的答复",
            outputMode = LlmOutputMode.LongForm,
            taskMode = LlmTaskMode.QuestionAnswer,
        )

        assertTrue(prompt.contains("输入法应答助手"))
        assertTrue(prompt.contains("<task>\n问答短文回复\n</task>"))
        assertTrue(prompt.contains("如果问题需要展开说明"))
        assertTrue(prompt.contains("尽量完整回答"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun systemPromptSwitchesToTranslateMode() {
        val prompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "今天晚上一起吃饭吗？",
            taskMode = LlmTaskMode.Translate,
        )

        assertTrue(prompt.contains("输入法翻译助手"))
        assertTrue(prompt.contains("<task>\n整段文本翻译\n</task>"))
        assertTrue(prompt.contains("只输出最终译文文本本身"))
        assertTrue(prompt.contains("将 INSTRUCTION 视为完整待翻译文本"))
        assertTrue(prompt.contains("英文译文中的单词之间必须保留正常空格"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun completionPromptWithoutRecentBiasBuildsInstructionProtocolPrompt() {
        val prompt = LlmPrompt.completionPrompt(
            beforeCursor = "光标前文本内容",
            recentCommittedText = "最近上屏",
            historyText = "历史内容",
            useRecentCommitBias = false,
            maxPredictionCandidates = 4,
        )

        assertTrue(prompt.startsWith("<|im_start|>system"))
        assertTrue(prompt.contains("<history>\n历史内容\n</history>"))
        assertTrue(prompt.contains("<last_msg>\n无\n</last_msg>"))
        assertTrue(prompt.contains("<memory>\n无\n</memory>"))
        assertTrue(prompt.contains("<instruction>\n请基于上下文，自然地续写我的输入"))
        assertTrue(prompt.endsWith("</think>\n\n光标前文本内容"))
    }

    @Test
    fun completionPromptWithRecentBiasIncludesRecentCommitMemory() {
        val prompt = LlmPrompt.completionPrompt(
            beforeCursor = "继续写",
            recentCommittedText = "上一句",
            historyText = "更早内容",
            useRecentCommitBias = true,
            maxPredictionCandidates = 4,
        )

        assertTrue(prompt.contains("<history>\n更早内容\n</history>"))
        assertTrue(prompt.contains("<memory>\n最近一次上屏：上一句\n</memory>"))
        assertTrue(prompt.endsWith("</think>\n\n继续写"))
    }

    @Test
    fun completionPromptPartsExposeStructuredMessagesForCompatibleChatApis() {
        val system = LlmPrompt.completionSystemPrompt(maxPredictionCandidates = 4)
        val user = LlmPrompt.completionUserPrompt(
            beforeCursor = "今晚一起",
            recentCommittedText = "要不要",
            historyText = "我们在约饭",
            useRecentCommitBias = true,
        )
        val assistant = LlmPrompt.completionAssistantPrefill("今晚一起")

        assertTrue(system.contains("<persona>\n自然、得体、贴近上下文的中文续写助手\n</persona>"))
        assertTrue(system.contains("<task>\n对话续写/补全\n</task>"))
        assertTrue(system.contains("<input_fields>"))
        assertTrue(user.contains("<history>\n我们在约饭\n</history>"))
        assertTrue(user.contains("<memory>\n最近一次上屏：要不要\n</memory>"))
        assertTrue(!user.contains("<persona>"))
        assertTrue(user.endsWith("今晚一起\n</instruction>"))
        assertEquals("<think>\n\n</think>\n\n今晚一起", assistant)
        assertTrue(system.contains("只输出 1-4 条续写候选"))
        assertTrue(system.contains("尽量给满 4 条"))
    }

    @Test
    fun completionPromptPartsSwitchToEnglishWhenPrefixLooksEnglish() {
        val system = LlmPrompt.completionSystemPrompt(
            maxPredictionCandidates = 3,
            beforeCursor = "Let's grab",
        )
        val user = LlmPrompt.completionUserPrompt(
            beforeCursor = "Let's grab",
            recentCommittedText = "Dinner?",
            historyText = "We are planning tonight",
            useRecentCommitBias = true,
        )

        assertTrue(system.contains("<task>\ndialogue continuation/autocomplete\n</task>"))
        assertTrue(system.contains("Provide 1-3 natural continuation candidates"))
        assertTrue(system.contains("allow at most one candidate to exceed that limit"))
        assertTrue(system.contains("leading space only when"))
        assertTrue(system.contains("<persona>\na natural, context-aware English continuation assistant\n</persona>"))
        assertTrue(!user.contains("<persona>"))
        assertTrue(user.contains("<history>\nWe are planning tonight\n</history>"))
        assertTrue(user.contains("Output only the continuation after the current prefix"))
        assertTrue(user.endsWith("Let's grab\n</instruction>"))
        assertTrue(system.contains("Output 1-3 continuation candidates only"))
        assertTrue(system.contains("Try to provide all 3 candidates"))
    }

    @Test
    fun completionPromptPartsSwitchToQuestionAnswerMode() {
        val system = LlmPrompt.completionSystemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "今天午饭吃什么？",
            taskMode = LlmTaskMode.QuestionAnswer,
        )
        val user = LlmPrompt.completionUserPrompt(
            beforeCursor = "今天午饭吃什么？",
            recentCommittedText = "早上刚开完会",
            historyText = "同事在群里讨论吃饭",
            useRecentCommitBias = true,
            taskMode = LlmTaskMode.QuestionAnswer,
        )
        val assistant = LlmPrompt.completionAssistantPrefill(
            beforeCursor = "今天午饭吃什么？",
            taskMode = LlmTaskMode.QuestionAnswer,
        )

        assertTrue(system.contains("输入法应答助手"))
        assertTrue(system.contains("<task>\n问答回复候选\n</task>"))
        assertTrue(system.contains("将 INSTRUCTION 视为完整的问题或请求本身"))
        assertTrue(!user.contains("<persona>"))
        assertTrue(user.contains("问题或请求"))
        assertTrue(user.contains("不要把它当成待续写前缀"))
        assertTrue(user.contains("不要为了简短而省略关键信息"))
        assertTrue(!user.contains("中文续写助手"))
        assertEquals("<think>\n\n</think>\n\n", assistant)
    }

    @Test
    fun completionPromptPartsUseLongFormLimitsWhenQuestionAnswerAndLongFormEnabled() {
        val system = LlmPrompt.completionSystemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "帮我回一句稍微详细一点的话",
            outputMode = LlmOutputMode.LongForm,
            taskMode = LlmTaskMode.QuestionAnswer,
        )
        val user = LlmPrompt.completionUserPrompt(
            beforeCursor = "帮我回一句稍微详细一点的话",
            recentCommittedText = "对方刚发来邀请",
            historyText = "朋友在约周末活动",
            useRecentCommitBias = true,
            outputMode = LlmOutputMode.LongForm,
            taskMode = LlmTaskMode.QuestionAnswer,
        )

        assertTrue(system.contains("输入法应答助手"))
        assertTrue(system.contains("<task>\n问答短文回复\n</task>"))
        assertTrue(system.contains("如果问题需要展开说明"))
        assertTrue(user.contains("更展开回答"))
        assertTrue(!user.contains("中文续写助手"))
    }

    @Test
    fun completionPromptPartsSwitchToTranslateMode() {
        val system = LlmPrompt.completionSystemPrompt(
            maxPredictionCandidates = 5,
            beforeCursor = "Let's meet after lunch.",
            taskMode = LlmTaskMode.Translate,
        )
        val user = LlmPrompt.completionUserPrompt(
            beforeCursor = "Let's meet after lunch.",
            recentCommittedText = "",
            historyText = "",
            useRecentCommitBias = false,
            taskMode = LlmTaskMode.Translate,
        )
        val assistant = LlmPrompt.completionAssistantPrefill(
            beforeCursor = "Let's meet after lunch.",
            taskMode = LlmTaskMode.Translate,
        )

        assertTrue(system.contains("<task>\nfull-text translation\n</task>"))
        assertTrue(system.contains("Treat INSTRUCTION as the complete source text to translate"))
        assertTrue(system.contains("Use [[BR]] instead of actual line breaks."))
        assertTrue(system.contains("Keep the item order fixed as: 释义 -> 音标 -> n. -> v. -> adj. -> adv. -> prep. -> phr."))
        assertTrue(system.contains("never reorder the remaining items"))
        assertTrue(user.contains("Translate the full text below into natural Chinese"))
        assertTrue(user.contains("literal separator `[[BR]]`"))
        assertTrue(user.contains("Do not use real line breaks"))
        assertTrue(!user.contains("<persona>"))
        assertTrue(!user.contains("English continuation assistant"))
        assertEquals("<think>\n\n</think>\n\n", assistant)
    }

    @Test
    fun completionAssistantPrefillSkipsForcedThinkBlockWhenThinkingEnabled() {
        val assistant = LlmPrompt.completionAssistantPrefill(
            beforeCursor = "今晚一起",
            enableThinking = true,
        )

        assertEquals("今晚一起", assistant)
    }

    @Test
    fun localOnDevicePromptIsShorterThanStructuredCompletionPrompt() {
        val compact = LlmPrompt.localOnDevicePrompt(
            beforeCursor = "今天天气真好",
            recentCommittedText = "",
            historyText = "",
            maxPredictionCandidates = 1,
        )
        val structured = LlmPrompt.completionPrompt(
            beforeCursor = "今天天气真好",
            recentCommittedText = "",
            historyText = "",
            useRecentCommitBias = false,
            maxPredictionCandidates = 1,
        )

        assertTrue(compact.length < structured.length)
        assertTrue(compact.contains("中文输入法续写助手"))
        assertTrue(!compact.contains("HISTORY:"))
        assertTrue(!compact.contains("<|im_start|>user"))
        assertTrue(!compact.contains("<think>"))
        assertTrue(!compact.contains("</think>"))
        assertTrue(compact.endsWith("<|im_start|>assistant\n今天天气真好"))
    }

    @Test
    fun localOnDeviceQuestionAnswerPromptAlsoOmitsThinkTags() {
        val prompt = LlmPrompt.localOnDevicePrompt(
            beforeCursor = "今晚吃什么？",
            recentCommittedText = "中午刚吃了面",
            historyText = "同事在讨论晚饭",
            maxPredictionCandidates = 1,
            taskMode = LlmTaskMode.QuestionAnswer,
        )

        assertTrue(!prompt.contains("<think>"))
        assertTrue(!prompt.contains("</think>"))
        assertTrue(!prompt.contains("<|im_start|>assistant"))
        assertTrue(!prompt.contains("<|im_start|>user"))
        assertTrue(!prompt.contains("历史："))
        assertTrue(!prompt.contains("最近上屏："))
        assertTrue(prompt.endsWith("今晚吃什么？"))
    }

    @Test
    fun userPromptUsesTranslatorPersonaAndEnglishSpacingInstructionForChineseTranslateMode() {
        val prompt = LlmPrompt.userPrompt(
            beforeCursor = "今天晚上一起吃饭吗？",
            recentCommittedText = "",
            historyText = "",
            useRecentCommitBias = false,
            taskMode = LlmTaskMode.Translate,
        )

        assertTrue(!prompt.contains("<persona>"))
        assertTrue(prompt.contains("英文单词之间必须保留正常空格"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun localOnDevicePromptUsesEnglishSpacingInstructionForChineseTranslateMode() {
        val prompt = LlmPrompt.localOnDevicePrompt(
            beforeCursor = "今天晚上一起吃饭吗？",
            recentCommittedText = "",
            historyText = "",
            maxPredictionCandidates = 1,
            taskMode = LlmTaskMode.Translate,
        )

        assertTrue(prompt.contains("英文单词之间必须保留正常空格"))
        assertTrue(!prompt.contains("中文续写助手"))
    }

    @Test
    fun localOnDevicePromptAppendsCustomPersonaGuidance() {
        val prompt = LlmPrompt.localOnDevicePrompt(
            beforeCursor = "今天晚上一起吃饭吗",
            recentCommittedText = "",
            historyText = "",
            maxPredictionCandidates = 1,
            personaPreset = LlmPrefs.PersonaPreset.Custom,
            customPersona = "语气温柔一点，像很会照顾人。",
        )

        assertTrue(prompt.contains("额外口吻要求"))
        assertTrue(prompt.contains("语气温柔一点，像很会照顾人。"))
    }

    @Test
    fun systemPromptMergesBuiltInPersonaAndDetailedDescription() {
        val prompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = 4,
            beforeCursor = "今晚吃点什么",
            personaPreset = LlmPrefs.PersonaPreset.SocialStar,
            customPersona = "说话再俏皮一点，但别太油。",
        )

        assertTrue(prompt.contains("情商在线，幽默风趣的社交达人。"))
        assertTrue(prompt.contains("说话再俏皮一点，但别太油。"))
    }

    @Test
    fun userPromptUsesStructuredContinuationContextForChatBackend() {
        val prompt = LlmPrompt.userPrompt(
            beforeCursor = "今晚一起去",
            recentCommittedText = "上条刚发完",
            historyText = "我们在约饭",
            useRecentCommitBias = true,
        )

        assertTrue(!prompt.contains("<persona>"))
        assertTrue(prompt.contains("<history>\n我们在约饭\n</history>"))
        assertTrue(prompt.contains("<memory>\n最近一次上屏：上条刚发完\n</memory>"))
        assertTrue(prompt.contains("只输出当前前缀后面的续写部分"))
        assertTrue(prompt.contains("今晚一起去\n</instruction>"))
    }

    @Test
    fun completionSystemPromptMentionsChineseLeadingPunctuationDecision() {
        val system = LlmPrompt.completionSystemPrompt(
            maxPredictionCandidates = 4,
            beforeCursor = "翩若惊鸿",
        )

        assertTrue(system.contains("<task>\n对话续写/补全\n</task>"))
        assertTrue(system.contains("不要重复已有标点"))
        assertTrue(system.contains("自然判断是否需要以中文全角标点开头"))
    }

    @Test
    fun userPromptUsesChineseContinuationInstructionForLeadingPunctuationDecision() {
        val prompt = LlmPrompt.userPrompt(
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
        val prompt = LlmPrompt.userPrompt(
            beforeCursor = "Please send me",
            recentCommittedText = "the draft",
            historyText = "Need a quick follow-up",
            useRecentCommitBias = true,
        )

        assertTrue(prompt.contains("Continue my input naturally based on the context"))
        assertTrue(prompt.endsWith("Please send me\n</instruction>"))
    }

    @Test
    fun userPromptUsesQuestionAnswerInstructionWhenEnabled() {
        val prompt = LlmPrompt.userPrompt(
            beforeCursor = "帮我回一句礼貌一点的话",
            recentCommittedText = "对方刚发来邀请",
            historyText = "朋友约我周末见面",
            useRecentCommitBias = true,
            taskMode = LlmTaskMode.QuestionAnswer,
        )

        assertTrue(!prompt.contains("<persona>"))
        assertTrue(prompt.contains("问题或请求"))
        assertTrue(prompt.contains("给出一条自然、完整、可直接发送的回答"))
        assertTrue(!prompt.contains("中文续写助手"))
        assertTrue(prompt.endsWith("帮我回一句礼貌一点的话\n</instruction>"))
    }
}
