package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fxboomk.fcitx5.android.input.applyAiInsertionUndo
import org.fxboomk.fcitx5.android.input.canApplyAiInsertionUndo

class AiSuggestionStripComponentTest {

    @Test
    fun mergeSingleTextPanelResultPrefersLongerFinalTextWhenItExtendsStream() {
        assertEquals(
            "这是已经流式出来的完整回答",
            mergeSingleTextPanelResult(
                streamedText = "这是已经流式出来的",
                finalText = "这是已经流式出来的完整回答",
            ),
        )
    }

    @Test
    fun mergeSingleTextPanelResultKeepsStreamWhenFinalTextShrinks() {
        assertEquals(
            "这是已经流式出来的完整回答",
            mergeSingleTextPanelResult(
                streamedText = "这是已经流式出来的完整回答",
                finalText = "这是已经流式出来的",
            ),
        )
    }

    @Test
    fun mergeSingleTextPanelResultFallsBackToStreamWhenFinalTextIsBlank() {
        assertEquals(
            "streamed answer",
            mergeSingleTextPanelResult(
                streamedText = "streamed answer",
                finalText = "",
            ),
        )
    }

    @Test
    fun aiUndoInsertionShouldOnlyReplaceInsertedSegment() {
        val result = applyAiInsertionUndo(
            currentText = "hello world",
            insertedText = " world",
            replacedText = "",
            selectionStart = 5,
        )
        assertEquals("hello", result)
    }

    @Test
    fun aiUndoInsertionShouldRestoreSelectedText() {
        val result = applyAiInsertionUndo(
            currentText = "hello earth",
            insertedText = "earth",
            replacedText = "world",
            selectionStart = 6,
        )
        assertEquals("hello world", result)
    }

    @Test
    fun aiUndoInsertionShouldRejectMismatchedCurrentText() {
        assertFalse(
            canApplyAiInsertionUndo(
                currentText = "hello planet",
                insertedText = " world",
                selectionStart = 5,
            ),
        )
    }

    @Test
    fun aiUndoInsertionShouldAcceptMatchingCurrentText() {
        assertTrue(
            canApplyAiInsertionUndo(
                currentText = "hello world",
                insertedText = " world",
                selectionStart = 5,
            ),
        )
    }

    @Test
    fun rememberedTranslateModeDoesNotExposeEmptyAiContentWhenPanelIsClosed() {
        val state = AiSuggestionStripComponent.PresentationState(
            mode = AiSuggestionStripComponent.PresentationMode.Hidden,
            suggestions = emptyList(),
            anchor = null,
            panelSuggestions = emptyList(),
            isPanelOpen = false,
            isLongFormEnabled = false,
            isSingleTextMode = true,
            isLoading = false,
            loadingLabel = null,
            isQuestionAnswerEnabled = false,
            isThinkingEnabled = false,
            isTranslateEnabled = true,
        )

        assertFalse(hasInteractiveAiContent(state))
    }

    @Test
    fun openTranslatePanelKeepsAiContentVisibleBeforeResultArrives() {
        val state = AiSuggestionStripComponent.PresentationState(
            mode = AiSuggestionStripComponent.PresentationMode.PanelVisible,
            suggestions = emptyList(),
            anchor = null,
            panelSuggestions = emptyList(),
            isPanelOpen = true,
            isLongFormEnabled = false,
            isSingleTextMode = true,
            isLoading = false,
            loadingLabel = null,
            isQuestionAnswerEnabled = false,
            isThinkingEnabled = false,
            isTranslateEnabled = true,
        )

        assertTrue(hasInteractiveAiContent(state))
    }

    @Test
    fun doNotAutoRequestRememberedTranslateForNonFloatingDisplayWithoutOpenPanel() {
        assertFalse(
            shouldAutoRequestRememberedTranslate(
                displayMode = LlmPrefs.PredictionDisplayMode.CandidateExpanded,
                taskMode = LlmTaskMode.Translate,
                panelVisible = false,
                isAutomatic = true,
            )
        )
    }

    @Test
    fun explicitRememberedTranslateCanRequestForNonFloatingDisplayWithoutOpenPanel() {
        assertTrue(
            shouldAutoRequestRememberedTranslate(
                displayMode = LlmPrefs.PredictionDisplayMode.CandidateExpanded,
                taskMode = LlmTaskMode.Translate,
                panelVisible = false,
                isAutomatic = false,
            )
        )
    }

    @Test
    fun doNotAutoRequestRememberedTranslateForFloatingDisplayWithoutOpenPanel() {
        assertFalse(
            shouldAutoRequestRememberedTranslate(
                displayMode = LlmPrefs.PredictionDisplayMode.FloatingWindow,
                taskMode = LlmTaskMode.Translate,
                panelVisible = false,
                isAutomatic = true,
            )
        )
    }

    @Test
    fun completedAiResultRequiresNonLoadingCandidateContent() {
        val state = AiSuggestionStripComponent.PresentationState(
            mode = AiSuggestionStripComponent.PresentationMode.PanelVisible,
            suggestions = emptyList(),
            anchor = null,
            panelSuggestions = listOf("translated result"),
            isPanelOpen = true,
            isLongFormEnabled = false,
            isSingleTextMode = true,
            isLoading = false,
            loadingLabel = null,
            isQuestionAnswerEnabled = false,
            isThinkingEnabled = false,
            isTranslateEnabled = true,
        )

        assertTrue(hasCompletedAiResult(state))
    }

    @Test
    fun loadingAiResultDoesNotCountAsCompleted() {
        val state = AiSuggestionStripComponent.PresentationState(
            mode = AiSuggestionStripComponent.PresentationMode.PanelVisible,
            suggestions = emptyList(),
            anchor = null,
            panelSuggestions = listOf("partial translated result"),
            isPanelOpen = true,
            isLongFormEnabled = false,
            isSingleTextMode = true,
            isLoading = true,
            loadingLabel = null,
            isQuestionAnswerEnabled = false,
            isThinkingEnabled = false,
            isTranslateEnabled = true,
        )

        assertFalse(hasCompletedAiResult(state))
    }
}
