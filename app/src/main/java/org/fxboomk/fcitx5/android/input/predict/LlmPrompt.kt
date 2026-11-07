package org.fxboomk.fcitx5.android.input.predict

internal object LlmPrompt {
    private const val STYLE_PERSONA_ZH = "自然、得体、贴近上下文的中文续写助手"
    private const val STYLE_PERSONA_EN = "a natural, context-aware English continuation assistant"
    private const val STYLE_ANSWERER_ZH = "自然、得体、贴近上下文的输入法应答助手"
    private const val STYLE_ANSWERER_EN = "a natural, context-aware IME response assistant"
    private const val STYLE_TRANSLATOR_ZH = "自然、准确、贴近上下文的输入法翻译助手"
    private const val STYLE_TRANSLATOR_EN = "a natural, accurate, context-aware IME translation assistant"
    private const val STYLE_POLISHER_ZH = "自然、得体、贴近上下文的输入法润色助手"
    private const val STYLE_POLISHER_EN = "a natural, context-aware IME text polishing assistant"

    private const val EMPTY_CONTEXT = "无"

    private fun briefAnswerInstruction(language: LlmLanguage): String = when (language) {
        LlmLanguage.Chinese ->
            "只输出 1 条自然、可直接发送的简短回答，通常 1-3 句。直答重点，保留必要的核心信息，不展开长篇，不解释、不输出 JSON 或标签。人设与口吻偏好只影响表达，不得覆盖简短回答要求。"
        LlmLanguage.English ->
            "Output only one natural, concise answer ready to send, usually 1-3 sentences. Answer the main point directly, retain essential information, and do not expand into a long reply. Do not explain or output JSON or labels. Persona and tone preferences may shape the wording but must not override the concise answer requirement."
    }

    private enum class RemotePromptTransport {
        Chat,
        Completion,
    }

    internal fun translationCorrectionInstruction(): String = """
上一次输出与 INSTRUCTION 完全相同，因此判定为无效译文。现在必须重新执行翻译：不要复制、复述或原样返回 INSTRUCTION，必须输出不同于原文的目标语言译文。只有原文确实是简体中文时目标语言才是英文；其他语言的目标语言都是简体中文。只输出最终译文，不要解释。
The previous output exactly repeated INSTRUCTION and was invalid. Translate again now: do not copy, repeat, or return INSTRUCTION unchanged; output a translation in the required target language that differs from the source. Use English only for Simplified Chinese input; use Simplified Chinese for every other language. Output only the final translation.
""".trim()

    fun systemPrompt(
        maxPredictionCandidates: Int,
        beforeCursor: String = "",
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
        personaPreset: LlmPrefs.PersonaPreset = LlmPrefs.PersonaPreset.Custom,
        customPersona: String = "",
    ): String = remoteSystemPrompt(
        transport = RemotePromptTransport.Chat,
        maxPredictionCandidates = maxPredictionCandidates,
        beforeCursor = beforeCursor,
        outputMode = outputMode,
        taskMode = taskMode,
        personaPreset = personaPreset,
        customPersona = customPersona,
    )

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
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
    ): String = buildString {
        val language = LlmLanguageDetector.detect(beforeCursor)
        appendXmlSection("history", normalizeContext(historyText.ifBlank { recentCommittedText }))
        appendXmlSection("last_msg", EMPTY_CONTEXT)
        appendXmlSection("memory", buildMemoryText(recentCommittedText, useRecentCommitBias))
        append("<instruction>\n")
        append(
            when (language) {
                LlmLanguage.Chinese -> when {
                    outputMode == LlmOutputMode.BriefAnswer && taskMode == LlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，不要续写前缀本身。${briefAnswerInstruction(language)}\n"
                    outputMode == LlmOutputMode.LongForm && taskMode == LlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，给出一条自然、完整、可直接发送的更展开回答。不要续写前缀本身：\n"
                    taskMode == LlmTaskMode.Translate ->
                        "请先判断下面整段输入的语言，再执行翻译：只有确认整段文本是简体中文时才翻译成自然、准确、可直接发送的英文；任何非简体中文语言，包括繁体中文、日文、韩文、英文及其他语言，都翻译成自然、准确、可直接发送的简体中文。判定示例：繁体中文 `臨時切換` 应译为简体中文 `临时切换`，不得原样返回。不要因为包含相似汉字就把繁体中文或日文误判为简体中文。英文单词之间必须保留正常空格，不要把多个英文单词连写在一起。只输出译文本身，不要解释或复述判定过程：\n"
                    outputMode == LlmOutputMode.LongForm ->
                        "请将下面整段输入作为需要润色的原文，在不改变原意、不新增事实的前提下，结合上下文改善措辞、语法和语气，并保留原有段落结构。只输出润色后的完整文本，不要解释、不要续写，也不要回答其中的问题：\n"
                    taskMode == LlmTaskMode.QuestionAnswer ->
                        "请把下面输入当作用户刚提出的问题或请求，给出一条自然、完整、可直接发送的回答。不要把它当成待续写前缀，也不要为了简短而省略关键信息：\n"
                    else ->
                        "请基于上下文，自然地续写我的输入。只输出当前前缀后面的续写部分，不要重复前缀：\n"
                }

                LlmLanguage.English -> when {
                    outputMode == LlmOutputMode.BriefAnswer && taskMode == LlmTaskMode.QuestionAnswer ->
                        "Treat the text below as the user's latest question or request, not as a prefix to continue. ${briefAnswerInstruction(language)}\n"
                    outputMode == LlmOutputMode.LongForm && taskMode == LlmTaskMode.QuestionAnswer ->
                        "Treat the text below as the user's latest question or request and answer with one natural, fuller reply. Do not continue the prefix itself, and do not cut the answer short if more detail is needed:\n"
                    taskMode == LlmTaskMode.Translate ->
                        "First identify the language of the complete input, then translate it. Translate to natural English only when the complete input is Simplified Chinese; translate every other language, including Traditional Chinese, Japanese, Korean, English, and other languages, into natural Simplified Chinese (简体中文). Classification example: Traditional Chinese `臨時切換` must become Simplified Chinese `临时切换`, never be returned unchanged. Do not mistake Traditional Chinese or Japanese for Simplified Chinese just because they contain similar Han characters. If the input is a single English word or a very short phrase, output one plain-text line using the literal separator `[[BR]]` in this fixed order: `释义：...[[BR]]音标：...[[BR]]n. ...[[BR]]v. ...[[BR]]adj. ...[[BR]]adv. ...[[BR]]prep. ...[[BR]]phr. ...`. Skip unavailable items but do not reorder. Do not use real line breaks inside that glossary-style result. For longer text, output only the final translation itself without describing the classification step:\n"
                    outputMode == LlmOutputMode.LongForm ->
                        "Treat the text below as the original text to polish. Preserve its meaning and facts, improve the wording, grammar, and tone according to the context, and keep the original paragraph structure. Output only the complete polished text; do not explain, continue it, or answer any question inside it:\n"
                    taskMode == LlmTaskMode.QuestionAnswer ->
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
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
    ): String = buildStructuredUserPrompt(
        beforeCursor = beforeCursor,
        recentCommittedText = recentCommittedText,
        historyText = historyText,
        useRecentCommitBias = useRecentCommitBias,
        outputMode = outputMode,
        taskMode = taskMode,
    ).trim()

    fun completionSystemPrompt(
        maxPredictionCandidates: Int,
        beforeCursor: String = "",
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
        personaPreset: LlmPrefs.PersonaPreset = LlmPrefs.PersonaPreset.Custom,
        customPersona: String = "",
    ): String = remoteSystemPrompt(
        transport = RemotePromptTransport.Completion,
        maxPredictionCandidates = maxPredictionCandidates,
        beforeCursor = beforeCursor,
        outputMode = outputMode,
        taskMode = taskMode,
        personaPreset = personaPreset,
        customPersona = customPersona,
    )

    fun completionUserPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
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
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        enableThinking: Boolean = false,
    ): String = buildString {
        if (beforeCursor.isNotBlank() && !enableThinking) {
            append("<think>\n\n</think>\n\n")
        }
        if (taskMode == LlmTaskMode.Completion && outputMode != LlmOutputMode.LongForm) {
            append(beforeCursor)
        }
    }

    fun completionPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
        maxPredictionCandidates: Int,
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
        enableThinking: Boolean = false,
        personaPreset: LlmPrefs.PersonaPreset = LlmPrefs.PersonaPreset.Custom,
        customPersona: String = "",
    ): String = buildString {
        append("<|im_start|>system\n")
        append(
            completionSystemPrompt(
                maxPredictionCandidates = maxPredictionCandidates,
                beforeCursor = beforeCursor,
                outputMode = outputMode,
                taskMode = taskMode,
                personaPreset = personaPreset,
                customPersona = customPersona,
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
                outputMode = outputMode,
                enableThinking = enableThinking,
            )
        )
    }.trim()

    internal fun localOnDevicePrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        maxPredictionCandidates: Int,
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
        personaPreset: LlmPrefs.PersonaPreset = LlmPrefs.PersonaPreset.Custom,
        customPersona: String = "",
    ): String = buildString {
        append("<|im_start|>system\n")
        append(
            localOnDeviceSystemPrompt(
                beforeCursor = beforeCursor,
                maxPredictionCandidates = maxPredictionCandidates,
                outputMode = outputMode,
                taskMode = taskMode,
                personaPreset = personaPreset,
                customPersona = customPersona,
            )
        )
        if (taskMode == LlmTaskMode.Completion && outputMode != LlmOutputMode.LongForm) {
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            append(localOnDeviceAssistantPrefill(beforeCursor, taskMode, outputMode))
        } else {
            append("\n")
            append(localOnDeviceUserPrompt(beforeCursor))
        }
    }.trim()

    private fun localOnDeviceAssistantPrefill(
        beforeCursor: String,
        taskMode: LlmTaskMode = LlmTaskMode.Completion,
        outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
    ): String = buildString {
        if (taskMode == LlmTaskMode.Completion && outputMode != LlmOutputMode.LongForm) {
            append(beforeCursor)
        }
    }

    private fun localOnDeviceSystemPrompt(
        beforeCursor: String,
        maxPredictionCandidates: Int,
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
        personaPreset: LlmPrefs.PersonaPreset,
        customPersona: String,
    ): String {
        val candidateLimit = normalizedPredictionCandidateLimit(maxPredictionCandidates)
        val language = LlmLanguageDetector.detect(beforeCursor)
        val basePrompt = when (language) {
            LlmLanguage.Chinese -> when {
                taskMode == LlmTaskMode.Translate ->
                    "你是输入法翻译助手。请先识别完整输入的语言，再执行翻译：只有确认整段输入是简体中文时才翻译成自然英文；任何非简体中文语言，包括繁体中文、日文、韩文、英文及其他语言，都翻译成自然简体中文。判定示例：繁体中文 `臨時切換` 应译为简体中文 `临时切换`，不得原样返回。不要因为包含相似汉字就把繁体中文或日文误判为简体中文。只输出最终译文，不解释或复述判定过程。"

                taskMode == LlmTaskMode.QuestionAnswer && outputMode == LlmOutputMode.BriefAnswer ->
                    "你是输入法应答助手。把输入当作问题或请求，默认用中文回答。${briefAnswerInstruction(language)}"

                taskMode == LlmTaskMode.QuestionAnswer && outputMode == LlmOutputMode.LongForm ->
                    "你是输入法应答助手。把输入当作问题或请求，输出 1 条自然、完整、可直接发送的中文回答；如果问题需要展开说明，就尽量完整回答，不解释。"

                taskMode == LlmTaskMode.QuestionAnswer ->
                    "你是输入法应答助手。把输入当作问题或请求，输出 1 条自然、完整、可直接发送的中文回答；不要为了简短而省略关键信息，不解释。"

                outputMode == LlmOutputMode.LongForm ->
                    "你是中文输入法润色助手。把输入当作需要润色的原文，在不改变原意、不新增事实的前提下，结合上下文改善措辞、语法和语气，并保留原有段落结构。只输出润色后的完整文本，不解释、不续写，也不要回答其中的问题。"

                else ->
                    "你是中文输入法续写助手。根据前缀补全后续内容。输出最多 $candidateLimit 个候选，每个候选至少要有两个字符, 每行一个，不解释。"
            }

            LlmLanguage.English -> when {
                taskMode == LlmTaskMode.Translate ->
                    "You are an IME translator. First identify the language of the complete input, then translate it. Translate to natural English only when the complete input is Simplified Chinese; translate every other language, including Traditional Chinese, Japanese, Korean, English, and other languages, into natural Simplified Chinese (简体中文). Classification example: Traditional Chinese `臨時切換` must become Simplified Chinese `临时切换`, never be returned unchanged. Do not mistake Traditional Chinese or Japanese for Simplified Chinese just because they contain similar Han characters. If the input is a single English word or a very short phrase, output one plain-text line using the literal separator `[[BR]]` in this fixed order: `释义：...[[BR]]音标：...[[BR]]n. ...[[BR]]v. ...[[BR]]adj. ...[[BR]]adv. ...[[BR]]prep. ...[[BR]]phr. ...`. Skip unavailable items but do not reorder. Do not use real line breaks inside that glossary-style result. For longer text, output only the final translation itself without describing the classification step."

                taskMode == LlmTaskMode.QuestionAnswer && outputMode == LlmOutputMode.BriefAnswer ->
                    "You are an IME reply assistant. Treat the input as a question or request. ${briefAnswerInstruction(language)}"

                taskMode == LlmTaskMode.QuestionAnswer && outputMode == LlmOutputMode.LongForm ->
                    "You are an IME reply assistant. Treat the input as a request and output one natural, fuller reply without cutting important detail short."

                taskMode == LlmTaskMode.QuestionAnswer ->
                    "You are an IME reply assistant. Treat the input as a request and output one natural, complete reply without omitting important detail."

                outputMode == LlmOutputMode.LongForm ->
                    "You are an English IME text polishing assistant. Treat the input as the original text, preserve its meaning and facts, improve the wording, grammar, and tone according to the context, and keep the original paragraph structure. Output only the complete polished text, with no explanation, continuation, or answer to any question inside it."

                else ->
                    "You are an English IME continuation assistant. Output up to $candidateLimit continuations after the prefix, one per line, with no explanation."
            }
        }
        val styleHint = styleHint(language, taskMode, personaPreset, customPersona)
        return if (styleHint.isBlank()) {
            basePrompt
        } else {
            "$basePrompt ${inlineStyleHint(language, styleHint)}"
        }
    }

    private fun localOnDeviceUserPrompt(
        beforeCursor: String,
    ): String = beforeCursor.trim()

    private fun remoteSystemPrompt(
        transport: RemotePromptTransport,
        maxPredictionCandidates: Int,
        beforeCursor: String,
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
        personaPreset: LlmPrefs.PersonaPreset,
        customPersona: String,
    ): String = buildString {
        val language = LlmLanguageDetector.detect(beforeCursor)
        val candidateLimit = if (
            taskMode == LlmTaskMode.QuestionAnswer ||
            taskMode == LlmTaskMode.Translate ||
            outputMode == LlmOutputMode.LongForm
        ) {
            1
        } else {
            normalizedPredictionCandidateLimit(maxPredictionCandidates)
        }
        appendXmlSection("persona", persona(language, outputMode, taskMode))
        styleHint(language, taskMode, personaPreset, customPersona)
            .takeIf { it.isNotBlank() }
            ?.let { appendXmlSection("style_hint", it) }
        appendXmlSection("task", remoteTaskLabel(language, outputMode, taskMode))
        appendXmlSection("input_fields", remoteInputFields(language))
        appendXmlSection(
            "output_contract",
            remoteOutputContract(
                language = language,
                transport = transport,
                candidateLimit = candidateLimit,
                outputMode = outputMode,
                taskMode = taskMode,
            )
        )
        appendXmlSection(
            "rules",
            remoteRules(
                language = language,
                transport = transport,
                candidateLimit = candidateLimit,
                outputMode = outputMode,
                taskMode = taskMode,
            )
        )
    }.trim()

    private fun remoteTaskLabel(
        language: LlmLanguage,
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
    ): String = when (language) {
        LlmLanguage.Chinese -> when {
            outputMode == LlmOutputMode.BriefAnswer && taskMode == LlmTaskMode.QuestionAnswer ->
                "简短问答回复"
            outputMode == LlmOutputMode.LongForm && taskMode == LlmTaskMode.QuestionAnswer ->
                "问答短文回复"
            taskMode == LlmTaskMode.Translate -> "整段文本翻译"
            outputMode == LlmOutputMode.LongForm -> "文本润色"
            taskMode == LlmTaskMode.QuestionAnswer -> "问答回复候选"
            else -> "对话续写/补全"
        }

        LlmLanguage.English -> when {
            outputMode == LlmOutputMode.BriefAnswer && taskMode == LlmTaskMode.QuestionAnswer ->
                "concise direct answer"
            outputMode == LlmOutputMode.LongForm && taskMode == LlmTaskMode.QuestionAnswer ->
                "slightly longer direct answer"
            taskMode == LlmTaskMode.Translate -> "full-text translation"
            outputMode == LlmOutputMode.LongForm -> "text polishing"
            taskMode == LlmTaskMode.QuestionAnswer -> "direct answer generation"
            else -> "dialogue continuation/autocomplete"
        }
    }

    private fun remoteInputFields(language: LlmLanguage): String = when (language) {
        LlmLanguage.Chinese -> """
HISTORY = 更早上下文，没有则为无
LAST_MSG = 最近一条消息，没有则为无
MEMORY = 额外记忆偏置，没有则为无
INSTRUCTION = 当前需要处理的输入文本
""".trim()

        LlmLanguage.English -> """
HISTORY = earlier context, or none
LAST_MSG = latest message, or none
MEMORY = optional memory bias, or none
INSTRUCTION = the current input text to process
""".trim()
    }

    private fun remoteOutputContract(
        language: LlmLanguage,
        transport: RemotePromptTransport,
        candidateLimit: Int,
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
    ): String = when (language) {
        LlmLanguage.Chinese -> when {
            transport == RemotePromptTransport.Chat &&
                outputMode == LlmOutputMode.Suggestions &&
                taskMode == LlmTaskMode.Completion -> """
只能输出 JSON。
顶层格式必须且只能是 {"suggestions":["候选1","候选2"]}。
尽量给满 $candidateLimit 个候选；如果确实无法给满，再输出更少的候选。
如果无法预测，也输出空数组：{"suggestions":[]}
""".trim()

            outputMode == LlmOutputMode.BriefAnswer && taskMode == LlmTaskMode.QuestionAnswer -> """
将 INSTRUCTION 视为完整的问题或请求，不要把它当成待续写前缀。
${briefAnswerInstruction(language)}
""".trim()

            outputMode == LlmOutputMode.LongForm && taskMode == LlmTaskMode.QuestionAnswer -> """
只输出 1 条最终回答文本本身，不要输出 JSON，不要输出标签。
将 INSTRUCTION 视为完整的问题或请求，不要把它当成待续写前缀。
如果问题需要展开说明，就尽量完整回答。
""".trim()

            taskMode == LlmTaskMode.Translate -> """
只输出最终译文文本本身，不要输出 JSON，不要输出标签。
将 INSTRUCTION 视为完整待翻译文本，不要续写，不要总结，不要解释。
先判断 INSTRUCTION 的语言，再执行翻译：只有确认整段文本是简体中文时才翻译成英文；任何非简体中文语言，包括繁体中文、日文、韩文、英文及其他语言，都翻译成简体中文。
判定示例：繁体中文 `臨時切換` 的最终译文是简体中文 `临时切换`，不得原样返回，也不要输出判定过程。
不要因为包含相似汉字就把繁体中文或日文误判为简体中文。英文译文中的单词之间必须保留正常空格，不要把多个英文单词连写在一起。
译文必须使用与上述判断一致的目标语言；翻译成简体中文时不要使用繁体字。
""".trim()

            outputMode == LlmOutputMode.LongForm -> """
只输出 1 条润色后的完整文本本身，不要输出 JSON，不要输出标签。
将 INSTRUCTION 视为需要润色的原文，不要当作待续写前缀，也不要回答其中的问题。
保持原意和原有信息不变，保留原有段落结构。
不要添加解释、前后缀或原文没有的事实。
""".trim()

            taskMode == LlmTaskMode.QuestionAnswer -> """
只输出 1 条最终回答文本本身，不要输出 JSON，不要输出标签。
将 INSTRUCTION 视为完整的问题或请求本身，不要把它当成待续写前缀。
不要为了简短而省略关键信息。
""".trim()

            else -> when (transport) {
                RemotePromptTransport.Chat -> """
只能输出 JSON。
顶层格式必须且只能是 {"suggestions":["候选1","候选2"]}。
尽量给满 $candidateLimit 个候选；如果确实无法给满，再输出更少的候选。
如果无法预测，也输出空数组：{"suggestions":[]}
""".trim()

                RemotePromptTransport.Completion -> """
只输出 1-$candidateLimit 条续写候选，每行 1 条，不要输出 JSON，不要输出标签。
尽量给满 $candidateLimit 条；如果确实无法给满，再输出更少的候选。
如果 assistant 前缀已经包含当前输入前缀，每一行都只继续后半段，不要复述前缀。
""".trim()
            }
        }

        LlmLanguage.English -> when {
            transport == RemotePromptTransport.Chat &&
                outputMode == LlmOutputMode.Suggestions &&
                taskMode == LlmTaskMode.Completion -> """
Output JSON only.
The top-level format must be exactly {"suggestions":["candidate 1","candidate 2"]}.
Try to provide all $candidateLimit candidates; return fewer only when necessary.
If you cannot predict anything, output {"suggestions":[]}.
""".trim()

            outputMode == LlmOutputMode.BriefAnswer && taskMode == LlmTaskMode.QuestionAnswer -> """
Treat INSTRUCTION as a complete question or request, not as a prefix to continue.
${briefAnswerInstruction(language)}
""".trim()

            outputMode == LlmOutputMode.LongForm && taskMode == LlmTaskMode.QuestionAnswer -> """
Output only the final answer text itself, not JSON or labels.
Treat INSTRUCTION as a complete question or request, not as a prefix to continue.
If the question needs more detail, do not cut the answer short.
""".trim()

            taskMode == LlmTaskMode.Translate -> """
Treat INSTRUCTION as the complete source text to translate, not as a prefix to continue.
First identify the language of the complete INSTRUCTION, then translate it. Translate to English only when it is Simplified Chinese; translate every other language, including Traditional Chinese, Japanese, Korean, English, and other languages, into Simplified Chinese (简体中文). Do not mistake Traditional Chinese or Japanese for Simplified Chinese just because they contain similar Han characters.
Classification example: the final translation of Traditional Chinese `臨時切換` is Simplified Chinese `临时切换`; never return it unchanged or output the classification process.
If INSTRUCTION is a single English word or a very short phrase, output one plain-text line using the literal separator [[BR]] in this exact order:
释义：<最常用中文释义>[[BR]]音标：<音标，没有就省略这一项>[[BR]]n. <名词释义，没有就省略>[[BR]]v. <动词释义，没有就省略>[[BR]]adj. <形容词释义，没有就省略>[[BR]]adv. <副词释义，没有就省略>[[BR]]prep. <介词释义，没有就省略>[[BR]]phr. <短语释义，没有就省略>
Use [[BR]] instead of actual line breaks.
Keep the item order fixed as: 释义 -> 音标 -> n. -> v. -> adj. -> adv. -> prep. -> phr.
If some items are unavailable, omit them, but never reorder the remaining items.
Do not output JSON, markdown, or extra labels beyond those lines.
If INSTRUCTION is a longer sentence or paragraph, output only the final translation text itself in the target language determined above.
""".trim()

            outputMode == LlmOutputMode.LongForm -> """
Output only the complete polished text itself, not JSON or labels.
Treat INSTRUCTION as the original text to polish, not as a prefix to continue or a question to answer.
Preserve its meaning, information, language, and paragraph structure.
Do not add explanations, prefixes, suffixes, or facts that are not present in the original.
""".trim()

            taskMode == LlmTaskMode.QuestionAnswer -> """
Output only the final answer text itself, not JSON or labels.
Treat INSTRUCTION as a complete question or request, not as a prefix to continue.
Return one natural, complete answer without dropping important detail.
""".trim()

            else -> when (transport) {
                RemotePromptTransport.Chat -> """
Output JSON only.
The top-level format must be exactly {"suggestions":["candidate 1","candidate 2"]}.
Try to provide all $candidateLimit candidates; return fewer only when necessary.
If you cannot predict anything, output {"suggestions":[]}.
""".trim()

                RemotePromptTransport.Completion -> """
Output 1-$candidateLimit continuation candidates only, one per line, with no JSON or labels.
Try to provide all $candidateLimit candidates; return fewer only when necessary.
If the assistant prefix already includes the typed prefix, each line should continue from it without repeating the prefix.
""".trim()
            }
        }
    }

    private fun remoteRules(
        language: LlmLanguage,
        transport: RemotePromptTransport,
        candidateLimit: Int,
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
    ): String = when (language) {
        LlmLanguage.Chinese -> when {
            taskMode == LlmTaskMode.Translate -> """
保持原意，保留必要的人名、专有名词、数字、URL、邮箱和代码片段；不确定时优先保留原样。
只有确认整段输入是简体中文时才翻译成英文；任何非简体中文语言，包括繁体中文、日文、韩文、英文及其他语言，都翻译成简体中文。
判定示例：繁体中文 `臨時切換` 必须输出简体中文 `临时切换`，不得原样返回。
不要因为包含相似汉字就把繁体中文或日文误判为简体中文。目标语言必须与这个判断一致，翻译成简体中文时不要使用繁体字。
翻译成英文时，英文单词之间必须保留正常空格，不要把多个英文单词连写在一起。
不要输出 markdown 符号、引号、前缀说明或额外注释。
""".trim()

            taskMode == LlmTaskMode.QuestionAnswer -> """
回答要自然、完整、可直接发送，不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
如果当前输入以中文为主，默认输出中文回答；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非问题本身就在明确要求英文、缩写或代码。
""".trim()

            outputMode == LlmOutputMode.LongForm -> """
在原意、信息、人名、专有名词、数字、URL、邮箱和代码片段保持不变的前提下，改善措辞、语法、衔接和语气。
结合上下文选择得体的人设与口吻，但不要改变原文立场或添加原文没有的信息。
保留原有段落结构和换行，不要续写、不要回答问题、不要解释。
不要输出 markdown 符号、引号、前缀说明或额外注释。
如果当前原文以中文为主，默认输出中文润色结果。
""".trim()

            else -> when (transport) {
                RemotePromptTransport.Chat -> """
根据上下文给出 1-$candidateLimit 个自然续写候选，不要重复当前前缀已经输入的文本。
大多数候选尽量控制在 20 个中文字符以内；最多只允许 1 条候选超过这个长度。
根据当前前缀自然判断是否需要以中文全角标点开头，例如 ， 。 ？ ！；不要重复已有标点。
候选必须简短、自然、可直接上屏，像输入法联想，不要写整句说明文。
不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号，也不要输出 suggestions 字段以外的任何额外字段或文字。
如果当前上下文以中文为主，默认输出中文候选；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()

                RemotePromptTransport.Completion -> """
根据上下文给出 1-$candidateLimit 条自然续写候选，每行 1 条，像自然接着上一句往下写。
大多数候选尽量控制在 20 个中文字符以内；最多只允许 1 条候选超过这个长度。
根据前缀自然判断是否需要以中文全角标点开头，例如 ， 。 ？ ！；不要重复已有标点。
不要解释，不要寒暄，不要输出标签，不要输出 JSON。
即使上下文不完整，也优先给出合理、自然、可直接上屏的候选。
如果当前上下文以中文为主，默认续写中文；不要输出英文字母缩写、拼音、ID、URL、代码片段，除非当前前缀本身就在明确输入英文、缩写或代码。
""".trim()
            }
        }

        LlmLanguage.English -> when {
            taskMode == LlmTaskMode.Translate -> """
Preserve the original meaning and keep names, numbers, URLs, emails, and code snippets when needed.
Translate to English only when the complete input is Simplified Chinese; translate every other language, including Traditional Chinese, Japanese, Korean, English, and other languages, into natural, concise Simplified Chinese (简体中文). Do not mistake Traditional Chinese or Japanese for Simplified Chinese just because they contain similar Han characters, and never use Traditional Chinese characters in a Simplified Chinese translation.
Classification example: Traditional Chinese `臨時切換` must be output as Simplified Chinese `临时切换`, never returned unchanged.
For a single English word or a very short phrase, prefer a compact dictionary-style result encoded in plain text with the literal separator `[[BR]]`. Keep the fixed order `释义` -> `音标` -> `n.` -> `v.` -> `adj.` -> `adv.` -> `prep.` -> `phr.`, skip unavailable items, and do not reorder.
Do not explain, annotate, quote, or output markdown symbols.
""".trim()

            taskMode == LlmTaskMode.QuestionAnswer -> """
Make the answer natural, complete, and ready to send directly.
Do not explain, greet, introduce yourself, or output markdown symbols.
""".trim()

            outputMode == LlmOutputMode.LongForm -> """
Preserve the original meaning, information, names, numbers, URLs, emails, and code snippets while improving wording, grammar, flow, and tone.
Use the context and the requested persona to choose an appropriate tone without changing the original stance or adding information.
Keep the original paragraphs and line breaks. Do not continue the text, answer questions inside it, or explain.
Do not output markdown symbols, quotes, prefixes, or extra commentary.
""".trim()

            else -> when (transport) {
                RemotePromptTransport.Chat -> """
Provide 1-$candidateLimit natural continuation candidates from context.
Do not repeat text already present in the typed prefix.
Keep most candidates within 30 characters; allow at most one candidate to exceed that limit.
Add a leading space only when the English continuation naturally needs it; do not duplicate spaces.
Candidates must feel natural, concise, and ready to commit, like IME suggestions.
Do not explain, greet, introduce yourself, output markdown symbols, or add extra fields or text outside the suggestions field.
""".trim()

                RemotePromptTransport.Completion -> """
Provide 1-$candidateLimit natural continuation candidates, one per line, while matching the tone, rhythm, and style of the context.
Keep most candidates within 30 characters; allow at most one candidate to exceed that limit.
Add a leading space only when the English continuation naturally needs it; do not duplicate spaces.
Do not explain, greet, add labels, or output JSON.
Even if the context is incomplete, still provide brief, plausible candidates that can be committed directly.
""".trim()
            }
        }
    }

    private fun persona(
        language: LlmLanguage,
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
    ): String = when {
        taskMode == LlmTaskMode.QuestionAnswer -> when (language) {
            LlmLanguage.Chinese -> STYLE_ANSWERER_ZH
            LlmLanguage.English -> STYLE_ANSWERER_EN
        }

        taskMode == LlmTaskMode.Translate -> when (language) {
            LlmLanguage.Chinese -> STYLE_TRANSLATOR_ZH
            LlmLanguage.English -> STYLE_TRANSLATOR_EN
        }

        outputMode == LlmOutputMode.LongForm -> when (language) {
            LlmLanguage.Chinese -> STYLE_POLISHER_ZH
            LlmLanguage.English -> STYLE_POLISHER_EN
        }

        else -> when (language) {
            LlmLanguage.Chinese -> STYLE_PERSONA_ZH
            LlmLanguage.English -> STYLE_PERSONA_EN
        }
    }

    private fun styleHint(
        language: LlmLanguage,
        taskMode: LlmTaskMode,
        personaPreset: LlmPrefs.PersonaPreset,
        customPersona: String,
    ): String {
        if (taskMode == LlmTaskMode.Translate) return ""
        val presetPrompt = when (personaPreset) {
            LlmPrefs.PersonaPreset.Custom -> customPersona.trim()
            else -> when (language) {
                LlmLanguage.Chinese -> personaPreset.zhPrompt
                LlmLanguage.English -> personaPreset.enPrompt
            }
        }.trim()
        val detailPrompt = customPersona.trim()
        val mergedPrompt = when {
            presetPrompt.isBlank() -> detailPrompt
            detailPrompt.isBlank() -> presetPrompt
            detailPrompt == presetPrompt -> presetPrompt
            else -> "$presetPrompt $detailPrompt"
        }.trim()
        if (mergedPrompt.isBlank()) return ""
        return when (language) {
            LlmLanguage.Chinese ->
                "在保持内容自然、清晰、可直接发送的前提下，额外遵循这条人设与口吻偏好：$mergedPrompt"
            LlmLanguage.English ->
                "While keeping the result natural, clear, and ready to send, also follow this persona and tone preference: $mergedPrompt"
        }
    }

    private fun inlineStyleHint(
        language: LlmLanguage,
        styleHint: String,
    ): String = when (language) {
        LlmLanguage.Chinese -> "额外口吻要求：$styleHint"
        LlmLanguage.English -> "Additional persona and tone guidance: $styleHint"
    }

    private fun StringBuilder.appendXmlSection(
        name: String,
        value: String,
    ) {
        append('<')
        append(name)
        append(">\n")
        append(value)
        append('\n')
        append("</")
        append(name)
        append(">\n")
    }
}
