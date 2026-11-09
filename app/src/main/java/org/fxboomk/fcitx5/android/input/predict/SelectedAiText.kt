/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.predict

/** Local editor snapshot; surrounding text never enters the selected-text request. */
internal data class SelectedAiText(
    val text: String,
    val start: Int,
    val end: Int,
    val before: String,
    val after: String,
) {
    fun matches(start: Int, end: Int, text: String, before: String, after: String): Boolean =
        this.start == start && this.end == end && this.text == text &&
            this.before == before && this.after == after
}

internal fun selectedAiTextRequest(
    action: AiCandidateAction,
    text: String,
    thinkingEffort: LlmPrefs.ThinkingEffort,
): LlmPredictor.Request? {
    if (text.isBlank() || action == AiCandidateAction.Settings) return null
    return LlmPredictor.Request(
        beforeCursor = text,
        outputMode = when (action) {
            AiCandidateAction.Polish -> LlmOutputMode.LongForm
            AiCandidateAction.Reply -> LlmOutputMode.BriefAnswer
            else -> LlmOutputMode.Suggestions
        },
        taskMode = when (action) {
            AiCandidateAction.Translate -> LlmTaskMode.Translate
            AiCandidateAction.Reply -> LlmTaskMode.QuestionAnswer
            else -> LlmTaskMode.Completion
        },
        thinkingEffort = thinkingEffort,
    )
}
