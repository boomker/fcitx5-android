/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionBackspaceBehaviorDeciderTest {

    @Test
    fun preeditAlwaysGoesToFcitx() {
        assertEquals(
            PredictionBackspaceAction.SendToFcitx,
            predictionBackspaceAction(
                hasPreedit = true,
                hasNativePredictionCandidatesVisible = true,
                hasAiPredictionCandidatesVisible = true,
                isRimeInputMethod = true,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DismissCandidates,
            )
        )
    }

    @Test
    fun rimePredictionCandidatesCanDeleteText() {
        assertEquals(
            PredictionBackspaceAction.DeleteText,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = true,
                hasAiPredictionCandidatesVisible = false,
                isRimeInputMethod = true,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DeleteText,
            )
        )
    }

    @Test
    fun rimePredictionCandidatesCanDismissCandidates() {
        assertEquals(
            PredictionBackspaceAction.DismissCandidates,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = true,
                hasAiPredictionCandidatesVisible = false,
                isRimeInputMethod = true,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DismissCandidates,
            )
        )
    }

    @Test
    fun builtInPredictionCandidatesCanDeleteText() {
        assertEquals(
            PredictionBackspaceAction.DeleteText,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = true,
                hasAiPredictionCandidatesVisible = false,
                isRimeInputMethod = false,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DeleteText,
            )
        )
    }

    @Test
    fun builtInPredictionCandidatesCanDismissCandidates() {
        assertEquals(
            PredictionBackspaceAction.DismissCandidates,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = true,
                hasAiPredictionCandidatesVisible = false,
                isRimeInputMethod = false,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DismissCandidates,
            )
        )
    }

    @Test
    fun aiPredictionCandidatesCanDismissCandidates() {
        assertEquals(
            PredictionBackspaceAction.DismissCandidates,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = false,
                hasAiPredictionCandidatesVisible = true,
                isRimeInputMethod = false,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DismissCandidates,
            )
        )
    }

    @Test
    fun aiPredictionCandidatesCanDeleteText() {
        assertEquals(
            PredictionBackspaceAction.DeleteText,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = false,
                hasAiPredictionCandidatesVisible = true,
                isRimeInputMethod = false,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DeleteText,
            )
        )
    }

    @Test
    fun noPredictionCandidatesGoesToFcitx() {
        assertEquals(
            PredictionBackspaceAction.SendToFcitx,
            predictionBackspaceAction(
                hasPreedit = false,
                hasNativePredictionCandidatesVisible = false,
                hasAiPredictionCandidatesVisible = false,
                isRimeInputMethod = false,
                predictionBackspaceBehavior = PredictionBackspaceBehavior.DismissCandidates,
            )
        )
    }
}
