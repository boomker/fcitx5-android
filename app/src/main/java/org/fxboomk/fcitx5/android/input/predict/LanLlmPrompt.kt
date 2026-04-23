package org.fxboomk.fcitx5.android.input.predict

object LanLlmPrompt {
    private const val STYLE_PERSONA_ZH = "自然、得体、贴近上下文的中文续写助手"
    private const val STYLE_PERSONA_EN = "a natural, context-aware English continuation assistant"

    private const val EMPTY_CONTEXT = "无"

    fun systemPrompt(maxPredictionCandidates: Int, beforeCursor: String = ""): String {
        val candidateLimit = maxPredictionCandidates.coerceIn(1, 8)
        return when (LanLlmLanguageDetector.detect(beforeCursor)) {
            LanLlmLanguage.Chinese -> """
你是$STYLE_PERSONA_ZH。
你的任务是像输入法一样，根据上下文给出 1-$candidateLimit 个自然续写候选。
1. 只能输出 JSON。
2. 顶层格式必须且只能是 {"suggestions":["候选1","候选2"]}。
3. 不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
4. 不要重复当前前缀已经输入的文本。
5. 大多数候选尽量控制在 20 个中文字符以内；最多只允许 1 条候选超过这个长度。
6. 根据当前前缀自然判断是否需要以中文全角标点开头，例如 ， 。 ？ ！；不要重复已有标点。
7. 候选必须简短、自然、可直接上屏，像输入法联想，不要写整句说明文。
8. 尽量给满 $candidateLimit 个候选；如果确实无法给满，再输出更少的候选。
9. 不要输出 suggestions 字段以外的任何额外字段或文字。
10. 如果无法预测，也输出空数组：{"suggestions":[]}
""".trim()

            LanLlmLanguage.English -> """
You are $STYLE_PERSONA_EN.
Your task is to act like an IME and provide 1-$candidateLimit natural continuation candidates from context.
1. Output JSON only.
2. The top-level format must be exactly {"suggestions":["candidate 1","candidate 2"]}.
3. Do not explain, greet, introduce yourself, or output markdown symbols.
4. Do not repeat text already present in the typed prefix.
5. Keep most candidates within 30 characters; allow at most one candidate to exceed that limit.
6. Add a leading space only when the English continuation naturally needs it; do not duplicate spaces.
7. Candidates must feel natural, concise, and ready to commit, like IME suggestions.
8. Try to provide all $candidateLimit candidates; return fewer only when necessary.
9. Do not output any extra fields or text outside the suggestions field.
10. If you cannot predict anything, output {"suggestions":[]}.
""".trim()
        }
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
        val language = LanLlmLanguageDetector.detect(beforeCursor)
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
        append(persona(language))
        append("\n</persona>\n")
        append("</env>\n")
        append("<instruction>\n")
        append(
            when (language) {
                LanLlmLanguage.Chinese ->
                    "请基于上下文，自然地续写我的输入。只输出当前前缀后面的续写部分，不要重复前缀：\n"

                LanLlmLanguage.English ->
                    "Continue my input naturally based on the context. Output only the continuation after the current prefix without repeating the prefix:\n"
            }
        )
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

    fun completionSystemPrompt(beforeCursor: String = ""): String = when (LanLlmLanguageDetector.detect(beforeCursor)) {
        LanLlmLanguage.Chinese -> """
你是$STYLE_PERSONA_ZH。
当前任务是“对话续写/补全”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 如果 assistant 前缀里已经给出当前输入前缀，你只输出续写部分，不要复述前缀。
- 优先延续当前语气、节奏和表达习惯，像自然接着上一句往下写。
- 大多数续写尽量控制在 20 个中文字符以内；最多只允许 1 条续写超过这个长度。
- 根据前缀自然判断是否需要以中文全角标点开头，例如 ， 。 ？ ！；不要重复已有标点。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 即使上下文不完整，也优先给出一个合理、简短、可直接上屏的续写。
""".trim()

        LanLlmLanguage.English -> """
You are $STYLE_PERSONA_EN.
The current task is dialogue continuation/autocomplete.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- If the assistant prefix already includes the typed prefix, output only the continuation and do not repeat the prefix.
- Continue mainly in English while matching the tone, rhythm, and style of the context.
- Keep most continuations within 30 characters; allow at most one continuation to exceed that limit.
- Add a leading space only when the English continuation naturally needs it; do not duplicate spaces.
- Do not explain, greet, add labels, or output JSON.
- Even if the context is incomplete, still provide a brief, plausible continuation that can be committed directly.
""".trim()
    }

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
        append(completionSystemPrompt(beforeCursor))
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

    private fun persona(language: LanLlmLanguage): String = when (language) {
        LanLlmLanguage.Chinese -> STYLE_PERSONA_ZH
        LanLlmLanguage.English -> STYLE_PERSONA_EN
    }
}
