package org.fxboomk.fcitx5.android.input.predict

internal object LanLlmPrompt {
    private const val STYLE_PERSONA_ZH = "自然、得体、贴近上下文的中文续写助手"
    private const val STYLE_PERSONA_EN = "a natural, context-aware English continuation assistant"
    private const val STYLE_ANSWERER_ZH = "自然、得体、贴近上下文的输入法应答助手"
    private const val STYLE_ANSWERER_EN = "a natural, context-aware IME response assistant"
    private const val STYLE_TRANSLATOR_ZH = "自然、准确、贴近上下文的输入法翻译助手"
    private const val STYLE_TRANSLATOR_EN = "a natural, accurate, context-aware IME translation assistant"

    private const val EMPTY_CONTEXT = "无"

    fun systemPrompt(
        maxPredictionCandidates: Int,
        beforeCursor: String = "",
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String {
        val candidateLimit = if (
            taskMode == LanLlmTaskMode.QuestionAnswer ||
            taskMode == LanLlmTaskMode.Translate
        ) {
            1
        } else {
            maxPredictionCandidates.coerceIn(1, 8)
        }
        return when (LanLlmLanguageDetector.detect(beforeCursor)) {
            LanLlmLanguage.Chinese -> when {
                outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_ANSWERER_ZH。
你的任务是像输入法一样，根据用户刚输入的问题或请求，生成 1 条可直接上屏的完整回答。
1. 只输出最终回答文本本身，不要输出 JSON，不要输出标签。
2. 将当前输入视为完整的问题/请求本身，不要把它当成待续写前缀。
3. 回答要自然、完整、可直接发送；如果问题需要展开说明，就尽量完整回答。
4. 不要解释你的思考过程，不要寒暄，不要自我介绍，不要输出 markdown 符号。
5. 如果当前输入以中文为主，默认输出中文回答；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

                taskMode == LanLlmTaskMode.Translate -> """
你是$STYLE_TRANSLATOR_ZH。
你的任务是像输入法一样，将用户当前输入框里的整段中文文本翻译成自然、准确、可直接发送的英文。
1. 只输出最终译文文本本身，不要输出 JSON，不要输出标签。
2. 将当前输入视为完整待翻译文本，不要续写，不要总结，不要解释。
3. 保持原意，保留必要的人名、专有名词、数字、URL、邮箱和代码片段；不确定时优先保留原样。
4. 译文要自然、简洁、符合英文表达习惯，可分句，但不要补充原文没有的信息。
5. 英文译文中的单词之间必须保留正常空格，不要把多个英文单词连写在一起。
6. 不要输出 markdown 符号、引号、前缀说明或额外注释。
""".trim()

                outputMode == LanLlmOutputMode.LongForm -> """
你是$STYLE_PERSONA_ZH。
你的任务是像输入法一样，根据上下文生成 1 条可直接上屏的短句候选。
1. 只输出最终候选文本本身，不要输出 JSON，不要输出标签。
2. 候选应当是当前前缀后面的自然续写，不要重复当前前缀。
3. 候选要尽量写得完整、自然、贴近上下文；如果上下文需要更长的续写，不要为了缩短而截断意思。
4. 不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
5. 如果当前上下文以中文为主，默认输出中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()

                taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_ANSWERER_ZH。
你的任务是像输入法一样，根据用户刚输入的问题或请求给出 1 条可直接上屏的回答。
1. 只输出最终回答文本本身，不要输出 JSON，不要输出标签。
2. 将当前输入视为完整的问题或请求本身，不要把它当成待续写前缀。
3. 只返回 1 条回答，且回答必须自然、完整、可直接发送；不要为了简短而省略关键信息。
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
You are $STYLE_ANSWERER_EN.
Act like an IME and return exactly one complete answer to the user's latest question or request.
1. Output only the final answer text itself, not JSON or labels.
2. Treat the current input as a complete question/request, not as a prefix to continue.
3. Make the answer natural, complete, and ready to send. If the question needs more detail, do not cut the answer short.
4. Do not explain, greet, introduce yourself, or output markdown symbols.
""".trim()

                taskMode == LanLlmTaskMode.Translate -> """
You are $STYLE_TRANSLATOR_EN.
Act like an IME and translate the entire current English text into natural, accurate Chinese.
1. Output only the final translation text itself, not JSON or labels.
2. Treat the current input as the complete source text to translate, not as a prefix to continue.
3. Preserve the original meaning and keep names, numbers, URLs, emails, and code snippets when needed.
4. Make the Chinese translation natural, concise, and ready to send directly without adding new information.
5. Do not explain, annotate, quote, or output markdown symbols.
""".trim()

                outputMode == LanLlmOutputMode.LongForm -> """
You are $STYLE_PERSONA_EN.
Act like an IME and return exactly one longer continuation candidate.
1. Output only the final continuation text itself, not JSON or labels.
2. Return exactly one candidate.
3. Output only the continuation after the current prefix without repeating the prefix.
4. Keep the continuation natural and complete. If the context clearly needs a longer continuation, do not shorten it just to fit a target length.
5. Do not explain, greet, introduce yourself, or output markdown symbols.
""".trim()

                taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_ANSWERER_EN.
Act like an IME and provide exactly one complete answer to the user's latest question or request.
1. Output only the final answer text itself, not JSON or labels.
2. Treat the current input as a complete question/request, not as a prefix to continue.
3. Return only one answer, and make it natural, complete, and ready to send directly without dropping important detail.
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
        append(persona(language, taskMode))
        append("\n</persona>\n")
        append("</env>\n")
        append("<instruction>\n")
        append(
            when (language) {
                LanLlmLanguage.Chinese -> when {
                    outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，给出一条自然、完整、可直接发送的更展开回答。不要续写前缀本身：\n"
                    taskMode == LanLlmTaskMode.Translate ->
                        "请把下面整段文本翻译成自然、准确、可直接发送的英文。英文单词之间必须保留正常空格，不要把多个英文单词连写在一起。只输出译文本身，不要解释：\n"
                    outputMode == LanLlmOutputMode.LongForm ->
                        "请基于上下文，为我续写一条自然、完整、可直接上屏的内容。只输出当前前缀后面的续写部分，不要重复前缀；如果上下文需要更长的续写，不要刻意缩短：\n"
                    taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，给出一条自然、完整、可直接发送的回答。不要把它当成待续写前缀，也不要为了简短而省略关键信息：\n"
                    else ->
                        "请基于上下文，自然地续写我的输入。只输出当前前缀后面的续写部分，不要重复前缀：\n"
                }

                LanLlmLanguage.English -> when {
                    outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "Treat the text below as the user's latest question or request and answer with one natural, fuller reply. Do not continue the prefix itself, and do not cut the answer short if more detail is needed:\n"
                    taskMode == LanLlmTaskMode.Translate ->
                        "Translate the full text below into natural Chinese. Output only the translation itself without explanation:\n"
                    outputMode == LanLlmOutputMode.LongForm ->
                        "Continue my input with one natural, complete continuation. Output only the continuation after the current prefix without repeating the prefix, and do not shorten it if the context clearly needs more detail:\n"
                    taskMode == LanLlmTaskMode.QuestionAnswer ->
                        "Treat the text below as the user's latest question or request and provide one natural, complete answer. Do not treat it as a prefix to continue, and do not omit important detail just to stay brief:\n"
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
你是$STYLE_ANSWERER_ZH。
当前任务是“问答短文回复”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 将 <instruction> 中的当前输入视为用户刚提出的问题或请求，不要把它当作待续写前缀。
- 输出 1 条自然、完整、可直接发送的更展开回答；如果问题需要展开说明，就尽量完整回答。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 如果问题以中文为主，默认回答中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

            taskMode == LanLlmTaskMode.Translate -> """
你是$STYLE_TRANSLATOR_ZH。
当前任务是“整段文本翻译”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 将 <instruction> 中的整段文本翻译成自然、准确、可直接发送的英文。
- 只输出译文本身，不要解释，不要续写，不要输出标签，不要输出 JSON。
- 保留必要的人名、专有名词、数字、URL、邮箱和代码片段；不确定时优先保留原样。
- 不要补充原文没有的信息。
- 英文译文中的单词之间必须保留正常空格，不要把多个英文单词连写在一起。
""".trim()

            outputMode == LanLlmOutputMode.LongForm -> """
你是$STYLE_PERSONA_ZH。
当前任务是“长一点的短句续写”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 只输出当前前缀后面的续写部分，不要复述前缀。
- 输出 1 条自然、完整、可直接上屏的续写内容；如果上下文需要更长的续写，不要为了缩短而截断意思。
- 优先延续当前语气、节奏和表达习惯，像自然接着上一句往下写。
- 不要解释，不要寒暄，不要输出标签，不要输出 JSON。
- 如果当前上下文以中文为主，默认续写中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()

            taskMode == LanLlmTaskMode.QuestionAnswer -> """
你是$STYLE_ANSWERER_ZH。
当前任务是“问答回复候选”。
- 你会看到 <history>、<last_msg>、<memory> 和 <instruction>。
- 将 <instruction> 中的当前输入视为用户刚提出的问题或请求，不要把它当作待续写前缀。
- 输出 1 条自然、完整、可直接发送的回答候选；不要为了简短而省略关键信息。
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
- 即使上下文不完整，也优先给出一个合理、自然、可直接上屏的续写。
- 如果当前上下文以中文为主，默认续写中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()
        }

        LanLlmLanguage.English -> when {
            outputMode == LanLlmOutputMode.LongForm && taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_ANSWERER_EN.
The current task is a slightly longer direct answer.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Treat the current input as the user's latest question or request, not as a prefix to continue.
- Output one natural, fuller answer. If the question needs more detail, do not cut the answer short.
- Do not explain, greet, add labels, or output JSON.
""".trim()

            taskMode == LanLlmTaskMode.Translate -> """
You are $STYLE_TRANSLATOR_EN.
The current task is full-text translation.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Translate the full input text into natural Chinese.
- Output only the translation itself, without explanation, labels, or JSON.
- Preserve the original meaning and keep names, numbers, URLs, emails, and code snippets when needed.
- Do not add information that is not present in the source text.
""".trim()

            outputMode == LanLlmOutputMode.LongForm -> """
You are $STYLE_PERSONA_EN.
The current task is a slightly longer single-sentence continuation.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Output only the continuation after the typed prefix and do not repeat the prefix.
- Return one natural, complete continuation. If the context needs more detail, do not shorten it just to fit a target length.
- Match the tone, rhythm, and style of the context.
- Do not explain, greet, add labels, or output JSON.
""".trim()

            taskMode == LanLlmTaskMode.QuestionAnswer -> """
You are $STYLE_ANSWERER_EN.
The current task is direct answer generation.
- You will see <history>, <last_msg>, <memory>, and <instruction>.
- Treat the current input as the user's latest question or request, not as a prefix to continue.
- Output one natural, complete answer ready to send directly without dropping important detail.
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
        enableThinking: Boolean = false,
    ): String = buildString {
        if (beforeCursor.isNotBlank() && !enableThinking) {
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
        enableThinking: Boolean = false,
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
        append(
            completionAssistantPrefill(
                beforeCursor = beforeCursor,
                taskMode = taskMode,
                enableThinking = enableThinking,
            )
        )
    }.trim()

    internal fun localOnDevicePrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        maxPredictionCandidates: Int,
        outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = buildString {
        append("<|im_start|>system\n")
        append(
            localOnDeviceSystemPrompt(
                beforeCursor = beforeCursor,
                maxPredictionCandidates = maxPredictionCandidates,
                outputMode = outputMode,
                taskMode = taskMode,
            )
        )
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(
            localOnDeviceUserPrompt(
                beforeCursor = beforeCursor,
                recentCommittedText = recentCommittedText,
                historyText = historyText,
                outputMode = outputMode,
                taskMode = taskMode,
            )
        )
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append(localOnDeviceAssistantPrefill(beforeCursor, taskMode))
    }.trim()

    private fun localOnDeviceAssistantPrefill(
        beforeCursor: String,
        taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
    ): String = buildString {
        if (taskMode == LanLlmTaskMode.Completion) {
            append(beforeCursor)
        }
    }

    private fun localOnDeviceSystemPrompt(
        beforeCursor: String,
        maxPredictionCandidates: Int,
        outputMode: LanLlmOutputMode,
        taskMode: LanLlmTaskMode,
    ): String {
        val candidateLimit = maxPredictionCandidates.coerceIn(1, 8)
        return when (LanLlmLanguageDetector.detect(beforeCursor)) {
            LanLlmLanguage.Chinese -> when {
                taskMode == LanLlmTaskMode.Translate ->
                    "你是输入法翻译助手。把输入翻译成自然英文，只输出译文本身，不解释。"

                taskMode == LanLlmTaskMode.QuestionAnswer && outputMode == LanLlmOutputMode.LongForm ->
                    "你是输入法应答助手。把输入当作问题或请求，输出 1 条自然、完整、可直接发送的中文回答；如果问题需要展开说明，就尽量完整回答，不解释。"

                taskMode == LanLlmTaskMode.QuestionAnswer ->
                    "你是输入法应答助手。把输入当作问题或请求，输出 1 条自然、完整、可直接发送的中文回答；不要为了简短而省略关键信息，不解释。"

                outputMode == LanLlmOutputMode.LongForm ->
                    "你是中文输入法续写助手。只输出前缀后的一条自然续写，不重复前缀，不解释。"

                else ->
                    "你是中文输入法续写助手。根据前缀补全后续内容。输出最多 $candidateLimit 个候选，每个候选至少要有两个字符, 每行一个，不解释。"
            }

            LanLlmLanguage.English -> when {
                taskMode == LanLlmTaskMode.Translate ->
                    "You are an IME translator. Translate the input into natural Chinese and output only the translation."

                taskMode == LanLlmTaskMode.QuestionAnswer && outputMode == LanLlmOutputMode.LongForm ->
                    "You are an IME reply assistant. Treat the input as a request and output one natural, fuller reply without cutting important detail short."

                taskMode == LanLlmTaskMode.QuestionAnswer ->
                    "You are an IME reply assistant. Treat the input as a request and output one natural, complete reply without omitting important detail."

                outputMode == LanLlmOutputMode.LongForm ->
                    "You are an English IME continuation assistant. Output one natural continuation after the prefix only."

                else ->
                    "You are an English IME continuation assistant. Output up to $candidateLimit continuations after the prefix, one per line, with no explanation."
            }
        }
    }

    private fun localOnDeviceUserPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        outputMode: LanLlmOutputMode,
        taskMode: LanLlmTaskMode,
    ): String = buildString {
        if (historyText.isNotBlank()) {
            append("历史：").append(historyText.trim()).append('\n')
        }
        if (recentCommittedText.isNotBlank()) {
            append("最近上屏：").append(recentCommittedText.trim()).append('\n')
        }
        append(
            when {
                taskMode == LanLlmTaskMode.Translate -> "输入："
                taskMode == LanLlmTaskMode.QuestionAnswer -> "问题："
                else -> "前缀："
            }
        )
        append(beforeCursor.trim())
    }

    private fun persona(
        language: LanLlmLanguage,
        taskMode: LanLlmTaskMode,
    ): String = when (taskMode) {
        LanLlmTaskMode.QuestionAnswer -> when (language) {
            LanLlmLanguage.Chinese -> STYLE_ANSWERER_ZH
            LanLlmLanguage.English -> STYLE_ANSWERER_EN
        }

        LanLlmTaskMode.Translate -> when (language) {
            LanLlmLanguage.Chinese -> STYLE_TRANSLATOR_ZH
            LanLlmLanguage.English -> STYLE_TRANSLATOR_EN
        }

        else -> when (language) {
            LanLlmLanguage.Chinese -> STYLE_PERSONA_ZH
            LanLlmLanguage.English -> STYLE_PERSONA_EN
        }
    }
}
