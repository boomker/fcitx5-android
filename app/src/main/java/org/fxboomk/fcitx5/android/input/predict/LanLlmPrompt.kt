package org.fxboomk.fcitx5.android.input.predict

object LanLlmPrompt {
    private const val SYSTEM_PROMPT = """
1. 只能输出 JSON。
2. 格式必须是 {"suggestions":["候选1","候选2"]}
3. 不要解释，不要寒暄，不要自我介绍，不要输出 markdown 符号。
4. 不要重复光标前已经输入的文本。
5. 每个候选尽量不超过 12 个汉字或 20 个字符。
6. 候选必须简短、自然、可直接上屏，像输入法联想，不要写整句说明文。
7. 不要输出字段名以外的任何额外文字。
8. 如果无法预测，也输出空数组：{"suggestions":[]}
"""

    fun systemPrompt(): String = SYSTEM_PROMPT.trim()

    fun userPrompt(beforeCursor: String): String = buildString {
        append("光标前文本：")
        append(beforeCursor)
    }.trim()

    fun completionPrompt(
        beforeCursor: String,
        recentCommittedText: String,
        historyText: String,
        useRecentCommitBias: Boolean,
    ): String {
        if (!useRecentCommitBias || recentCommittedText.isBlank()) {
            return userPrompt(beforeCursor)
        }
        return buildString {
            append("最近一次上屏：")
            append(recentCommittedText)
            if (historyText.isNotBlank()) {
                append('\n')
                append("近期上屏：")
                append(historyText)
            }
            append('\n')
            append(userPrompt(beforeCursor))
        }.trim()
    }
}
