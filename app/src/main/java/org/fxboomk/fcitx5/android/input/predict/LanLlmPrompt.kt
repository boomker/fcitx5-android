package org.fxboomk.fcitx5.android.input.predict

object LanLlmPrompt {
    private const val STYLE_PERSONA = "自然、得体、贴近上下文的中文续写助手"

    private const val COMPLETION_SYSTEM_PROMPT = """
你是$STYLE_PERSONA。
当前任务是“对话续写/补全”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 如果 assistant 前缀里已经给出当前输入前缀，你只输出续写部分，不要复述前缀。
- 优先延续当前语气、节奏和表达习惯，像自然接着上一句往下写。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 即使上下文不完整，也优先给出一个合理、简短、可直接上屏的续写。
"""

    private const val EMPTY_CONTEXT = "无"

    fun systemPrompt(maxPredictionCandidates: Int): String {
        val candidateLimit = maxPredictionCandidates.coerceIn(1, 8)
        return """
你是$STYLE_PERSONA。
你的任务是像输入法一样，根据上下文给出 1-$candidateLimit 个自然续写候选。
1. 只能输出 JSON。
2. 顶层格式必须且只能是 {"suggestions":["候选1","候选2"]}。
3. 不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
4. 不要重复当前前缀已经输入的文本。
5. 每个候选尽量不超过 12 个汉字或 20 个字符。
6. 候选必须简短、自然、可直接上屏，像输入法联想，不要写整句说明文。
7. 尽量给满 $candidateLimit 个候选；如果确实无法给满，再输出更少的候选。
8. 不要输出 suggestions 字段以外的任何额外字段或文字。
9. 如果无法预测，也输出空数组：{"suggestions":[]}
""".trim()
    }

    private fun normalizeContext(value: String): String = value.trim().ifBlank { EMPTY_CONTEXT }

    private fun buildMemoryText(
        recentCommittedText: String,
        useRecentCommitBias: Boolean,
    ): String = if (useRecentCommitBias && recentCommittedText.isNotBlank()) {
        "最近一次上屏：${recentCommittedText.trim()}"
    } else {
        EMPTY_CONTEXT
    }

    private fun buildStructuredUserPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
    ): String = buildString {
        append("<env>\n")
        append("<history>\n")
        append(normalizeContext(historyText.ifBlank { recentCommittedText }))
        append("\n</history>\n")
        append("<last_msg>\n")
        append(EMPTY_CONTEXT)
        append("\n</last_msg>\n")
        append("<memory>\n")
        append(buildMemoryText(recentCommittedText, useRecentCommitBias))
        append("\n</memory>\n")
        append("<persona>\n")
        append(STYLE_PERSONA)
        append("\n</persona>\n")
        append("</env>\n")
        append("<instruction>\n")
        append("请基于上下文，自然地续写我的输入。只输出当前前缀后面的续写部分，不要重复前缀：\n")
        append(beforeCursor)
        append("\n</instruction>")
    }

    fun userPrompt(
        beforeCursor: String,
        recentCommittedText: String = "",
        historyText: String = "",
        useRecentCommitBias: Boolean = false,
    ): String = buildStructuredUserPrompt(
        beforeCursor = beforeCursor,
        recentCommittedText = recentCommittedText,
        historyText = historyText,
        useRecentCommitBias = useRecentCommitBias,
    ).trim()

    fun completionSystemPrompt(): String = COMPLETION_SYSTEM_PROMPT.trim()

    fun completionUserPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
    ): String = buildStructuredUserPrompt(
        beforeCursor = beforeCursor,
        recentCommittedText = recentCommittedText,
        historyText = historyText,
        useRecentCommitBias = useRecentCommitBias,
    ).trim()

    fun completionAssistantPrefill(beforeCursor: String): String = buildString {
        append("<think>\n\n</think>\n\n")
        append(beforeCursor)
    }

    fun completionPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
    ): String = buildString {
        append("<|im_start|>system\n")
        append(completionSystemPrompt())
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(
            completionUserPrompt(
                beforeCursor = beforeCursor,
                recentCommittedText = recentCommittedText,
                historyText = historyText,
                useRecentCommitBias = useRecentCommitBias,
            )
        )
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append(completionAssistantPrefill(beforeCursor))
    }.trim()
}
