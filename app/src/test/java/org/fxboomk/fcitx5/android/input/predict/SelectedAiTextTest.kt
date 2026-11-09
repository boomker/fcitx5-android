/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedAiTextTest {
    @Test
    fun selectedActionsChooseTranslationPolishAndBriefReplyTasks() {
        val text = " Selected text\n原文 "
        val expected = mapOf(
            AiCandidateAction.Translate to (LlmOutputMode.Suggestions to LlmTaskMode.Translate),
            AiCandidateAction.Polish to (LlmOutputMode.LongForm to LlmTaskMode.Completion),
            AiCandidateAction.Reply to (LlmOutputMode.BriefAnswer to LlmTaskMode.QuestionAnswer),
        )
        expected.forEach { (action, modes) ->
            val request = requireNotNull(selectedAiTextRequest(action, text, LlmPrefs.ThinkingEffort.Low))
            assertEquals(text, request.beforeCursor)
            assertEquals("", request.recentCommittedText)
            assertEquals("", request.historyText)
            assertEquals(modes.first, request.outputMode)
            assertEquals(modes.second, request.taskMode)
            assertEquals(LlmPrefs.ThinkingEffort.Low, request.thinkingEffort)
        }
    }

    @Test
    fun emptySelectionsAndSettingsAreNeverSentAsEditingRequests() {
        listOf("", " ", "\n\t").forEach { text ->
            assertNull(selectedAiTextRequest(AiCandidateAction.Translate, text, LlmPrefs.ThinkingEffort.Off))
        }
        assertNull(selectedAiTextRequest(AiCandidateAction.Settings, "text", LlmPrefs.ThinkingEffort.Off))
    }

    @Test
    fun selectionSnapshotRejectsCursorRangeAndSelectedTextChanges() {
        val selected = SelectedAiText("original", 6, 14, "before", "after")
        assertTrue(selected.matches(6, 14, "original", "before", "after"))
        assertFalse(selected.matches(6, 6, "", "before", "originalafter"))
        assertFalse(selected.matches(7, 15, "original", "before", "after"))
        assertFalse(selected.matches(6, 14, "modified", "before", "after"))
    }

    @Test
    fun selectionSnapshotRejectsEditsOutsideTheSelectedText() {
        val selected = SelectedAiText("original", 6, 14, "before", "after")
        assertFalse(selected.matches(6, 14, "original", "BEFORE", "after"))
        assertFalse(selected.matches(6, 14, "original", "before", "AFTER"))
    }

    @Test
    fun staleUiTextCannotCommitAfterCurrentPresentationWasClearedOrReplaced() {
        val current = AiSuggestionStripComponent.PresentationState(
            mode = AiSuggestionStripComponent.PresentationMode.PanelVisible,
            suggestions = listOf("current result"),
            anchor = null,
            panelSuggestions = listOf("current panel"),
            singleTextCommitText = "current translation",
            isPanelOpen = true,
            isLongFormEnabled = false,
            isSingleTextMode = true,
            isLoading = false,
            loadingLabel = null,
            isQuestionAnswerEnabled = false,
            isTranslateEnabled = false,
        )
        listOf("current result", "current panel", "current translation").forEach {
            assertTrue(canCommitAiSuggestionFromUi(it, current))
        }
        assertFalse(canCommitAiSuggestionFromUi("old result", current))
        assertFalse(canCommitAiSuggestionFromUi("", current))
        val cleared = current.copy(suggestions = emptyList(), panelSuggestions = emptyList(), singleTextCommitText = null)
        assertFalse(canCommitAiSuggestionFromUi("current result", cleared))
    }
}
