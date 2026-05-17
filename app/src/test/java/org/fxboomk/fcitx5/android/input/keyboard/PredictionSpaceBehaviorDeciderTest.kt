/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionSpaceBehaviorDeciderTest {

    @Test
    fun commitSpaceDisablesPredictionCommitForNativePredictionCandidates() {
        assertFalse(
            shouldCommitPredictionOnSpace(
                hasVisibleCandidates = true,
                hasNativePredictionCandidatesVisible = true,
                predictionSpaceBehavior = PredictionSpaceBehavior.CommitSpace,
            )
        )
    }

    @Test
    fun commitPredictionKeepsExistingPredictionCommitBehavior() {
        assertTrue(
            shouldCommitPredictionOnSpace(
                hasVisibleCandidates = true,
                hasNativePredictionCandidatesVisible = true,
                predictionSpaceBehavior = PredictionSpaceBehavior.CommitPrediction,
            )
        )
    }

    @Test
    fun nonPredictionCandidatesStillCommitOnSpace() {
        assertTrue(
            shouldCommitPredictionOnSpace(
                hasVisibleCandidates = true,
                hasNativePredictionCandidatesVisible = false,
                predictionSpaceBehavior = PredictionSpaceBehavior.CommitSpace,
            )
        )
    }

    @Test
    fun noCandidatesMeansNoCommit() {
        assertFalse(
            shouldCommitPredictionOnSpace(
                hasVisibleCandidates = false,
                hasNativePredictionCandidatesVisible = true,
                predictionSpaceBehavior = PredictionSpaceBehavior.CommitPrediction,
            )
        )
    }
}
