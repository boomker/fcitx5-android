package org.fxboomk.fcitx5.android.input.predict

internal object LanLlmPrompt {
    private const val STYLE_PERSONA_ZH = "自然、得体、贴近上下文的中文续写助手"
    private const val STYLE_PERSONA_EN = "a natural, context-aware English continuation assistant"

    private const val EMPTY_CONTEXT = "无"

    fun systemPrompt(
        maxPredictionCandidates: Int,
        beforeCursor: String = "",
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String {
        val candidateLimit = if (taskMode == LanLlmTaskMode.QuestionAnswer) {
            1
        } else {
            maxPredictionCandidates.coerceIn(1, 8)
        }
        return when (LanLlmLanguageDetector.detect(beforeCursor)) {
            LanLlmLanguage.Chinese -> when {
                outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_PERSONA_ZH。
你的任务是像输入法一样，根据用户刚输入的问题或请求，生成 1 条可直接上屏的简短回答。
1. 只输出最终回答文本本身，不要输出 JSON，不要输出标签。
2. 将当前输入视为完整的问题/请求本身，不要把它当成待续写前缀。
3. 回答长度控制在 30 到 50 个中文字符之间，尽量写成一条自然、完整、可直接发送的短句。
4. 不要解释你的思考过程，不要寒暄，不要自我介绍，不要输出 markdown 符号。
5. 如果当前输入以中文为主，默认输出中文回答；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

                outputMode == LanLlmOutputMode.LongForm -> """
你是$STYLE_PERSONA_ZH。
你的任务是像输入法一样，根据上下文生成 1 条可直接上屏的短句候选。
1. 只输出最终候选文本本身，不要输出 JSON，不要输出标签。
2. 候选应当是当前前缀后面的自然续写，不要重复当前前缀。
3. 候选长度控制在 30 到 50 个中文字符之间，尽量写成一条完整、自然、贴近上下文的短句。
4. 不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
5. 如果当前上下文以中文为主，默认输出中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()

                taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_PERSONA_ZH。
你的任务是像输入法一样，根据用户刚输入的问题或请求给出 1 条可直接上屏的回答。
1. 只输出最终回答文本本身，不要输出 JSON，不要输出标签。
2. 将当前输入视为完整的问题或请求本身，不要把它当成待续写前缀。
3. 只返回 1 条回答，且回答必须简短、自然、可直接发送，尽量控制在 24 个中文字符以内。
4. 不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
5. 如果当前输入以中文为主，默认输出中文回答；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

                else -> """
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
11. 如果当前上下文以中文为主，默认输出中文候选；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()
            }

            LanLlmLanguage.English -> when {
                outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_PERSONA_EN.
Act like an IME and return exactly one concise answer to the user's latest question or request.
1. Output only the final answer text itself, not JSON or labels.
2. Treat the current input as a complete question/request, not as a prefix to continue.
3. Keep the answer within about 40 to 90 English characters as one natural sentence ready to commit.
4. Do not explain, greet, introduce yourself, or output markdown symbols.
""".trim()

                outputMode == LanLlmOutputMode.LongForm -> """
You are $STYLE_PERSONA_EN.
Act like an IME and return exactly one longer continuation candidate.
1. Output only the final continuation text itself, not JSON or labels.
2. Return exactly one candidate.
3. Output only the continuation after the current prefix without repeating the prefix.
4. Keep the continuation within about 40 to 90 English characters, as one natural short sentence ready to commit.
5. Do not explain, greet, introduce yourself, or output markdown symbols.
""".trim()

                taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_PERSONA_EN.
Act like an IME and provide exactly one concise answer to the user's latest question or request.
1. Output only the final answer text itself, not JSON or labels.
2. Treat the current input as a complete question/request, not as a prefix to continue.
3. Return only one answer, and keep it concise and ready to send directly, preferably within 36 characters.
4. Do not explain, greet, introduce yourself, or output markdown symbols.
""".trim()

                else -> """
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
        outputMode: LanLlmOutputMode,
        taskMode: LanLlmTaskMode,
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
                LanLlmLanguage.Chinese -> when {
                    outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，给出一条 30 到 50 字之间、可直接发送的自然回答。不要续写前缀本身：\n"
                    outputMode == LanLlmOutputMode.LongForm ->
                        "请基于上下文，为我续写一条 30 到 50 字之间、可直接上屏的自然短句。只输出当前前缀后面的续写部分，不要重复前缀：\n"
                    taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，给出一条简短、自然、可直接发送的回答。不要把它当成待续写前缀：\n"
                    else ->
                        "请基于上下文，自然地续写我的输入。只输出当前前缀后面的续写部分，不要重复前缀：\n"
                }

                LanLlmLanguage.English -> when {
                    outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "Treat the text below as the user's latest question or request and answer with one natural short sentence of about 40 to 90 English characters. Do not continue the prefix itself:\n"
                    outputMode == LanLlmOutputMode.LongForm ->
                        "Continue my input with one natural short sentence of about 40 to 90 English characters. Output only the continuation after the current prefix without repeating the prefix:\n"
                    taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "Treat the text below as the user's latest question or request and provide one concise answer. Do not treat it as a prefix to continue:\n"
                    else ->
                        "Continue my input naturally based on the context. Output only the continuation after the current prefix without repeating the prefix:\n"
                }
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
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = buildStructuredUserPrompt(
        beforeCursor = beforeCursor,
        recentCommittedText = recentCommittedText,
        historyText = historyText,
        useRecentCommitBias = useRecentCommitBias,
        outputMode = outputMode,
        taskMode = taskMode,
    ).trim()

    fun completionSystemPrompt(
        beforeCursor: String = "",
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = when (LanLlmLanguageDetector.detect(beforeCursor)) {
        LanLlmLanguage.Chinese -> when {
            outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_PERSONA_ZH。
当前任务是“问答短文回复”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 将 <instruction> 中的当前输入视为用户刚提出的问题或请求，不要把它当作待续写前缀。
- 输出 1 条自然、完整、可直接发送的短句回答，长度控制在 30 到 50 个中文字符之间。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 如果问题以中文为主，默认回答中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

            outputMode == LanLlmOutputMode.LongForm -> """
你是$STYLE_PERSONA_ZH。
当前任务是“长一点的短句续写”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 只输出当前前缀后面的续写部分，不要复述前缀。
- 输出 1 条自然、完整、可直接上屏的短句，长度控制在 30 到 50 个中文字符之间。
- 优先延续当前语气、节奏和表达习惯，像自然接着上一句往下写。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 如果当前上下文以中文为主，默认续写中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()

            taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_PERSONA_ZH。
当前任务是“问答回复候选”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 将 <instruction> 中的当前输入视为用户刚提出的问题或请求，不要把它当作待续写前缀。
- 输出 1 条简短、自然、可直接发送的回答候选。
- 优先利用上下文保持语气自然，但不要复述问题。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 如果问题以中文为主，默认回答中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

            else -> """
你是$STYLE_PERSONA_ZH。
当前任务是“对话续写/补全”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 如果 assistant 前缀里已经给出当前输入前缀，你只输出续写部分，不要复述前缀。
- 优先延续当前语气、节奏和表达习惯，像自然接着上一句往下写。
- 大多数续写尽量控制在 20 个中文字符以内；最多只允许 1 条续写超过这个长度。
- 根据前缀自然判断是否需要以中文全角标点开头，例如 ， 。 ？ ！；不要重复已有标点。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 即使上下文不完整，也优先给出一个合理、简短、可直接上屏的续写。
- 如果当前上下文以中文为主，默认续写中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()
        }

        LanLlmLanguage.English -> when {
            outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_PERSONA_EN.
The current task is a slightly longer direct answer.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Treat the current input as the user's latest question or request, not as a prefix to continue.
- Output one natural answer sentence of about 40 to 90 English characters.
- Do not explain, greet, add labels, or output JSON.
""".trim()

            outputMode == LanLlmOutputMode.LongForm -> """
You are $STYLE_PERSONA_EN.
The current task is a slightly longer single-sentence continuation.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Output only the continuation after the typed prefix and do not repeat the prefix.
- Return one natural short sentence of about 40 to 90 English characters.
- Match the tone, rhythm, and style of the context.
- Do not explain, greet, add labels, or output JSON.
""".trim()

            taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_PERSONA_EN.
The current task is direct answer generation.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Treat the current input as the user's latest question or request, not as a prefix to continue.
- Output one concise, natural answer ready to send directly.
- Do not explain, greet, add labels, or output JSON.
""".trim()

            else -> """
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
    }

    fun completionUserPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = buildStructuredUserPrompt(
        beforeCursor = beforeCursor,
        recentCommittedText = recentCommittedText,
        historyText = historyText,
        useRecentCommitBias = useRecentCommitBias,
        outputMode = outputMode,
        taskMode = taskMode,
    ).trim()

    fun completionAssistantPrefill(
        beforeCursor: String,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = buildString {
        if (beforeCursor.isNotBlank()) {
            append("<think>\n\n</think>\n\n")
        }
        if (taskMode == LanLlmTaskMode.Completion) {
            append(beforeCursor)
        }
    }

    fun completionPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = buildString {
        append("<|im_start|>system\n")
        append(
            completionSystemPrompt(
                beforeCursor = beforeCursor,
                outputMode = outputMode,
                taskMode = taskMode,
            )
        )
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(
            completionUserPrompt(
                beforeCursor = beforeCursor,
                recentCommittedText = recentCommittedText,
                historyText = historyText,
                useRecentCommitBias = useRecentCommitBias,
                outputMode = outputMode,
                taskMode = taskMode,
            )
        )
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append(completionAssistantPrefill(beforeCursor, taskMode))
    }.trim()

    private fun persona(language: LanLlmLanguage): String = when (language) {
        LanLlmLanguage.Chinese -> STYLE_PERSONA_ZH
        LanLlmLanguage.English -> STYLE_PERSONA_EN
    }
}
