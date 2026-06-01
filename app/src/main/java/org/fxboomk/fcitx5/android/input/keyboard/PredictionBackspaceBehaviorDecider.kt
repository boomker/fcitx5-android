/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

internal enum class PredictionBackspaceAction {
    SendToFcitx,
    DeleteText,
    DismissCandidates
}

internal fun predictionBackspaceAction(
    hasPreedit: Boolean,
    hasNativePredictionCandidatesVisible: Boolean,
    hasAiPredictionCandidatesVisible: Boolean,
    isRimeInputMethod: Boolean,
    predictionBackspaceBehavior: PredictionBackspaceBehavior,
): PredictionBackspaceAction = when {
    hasPreedit -> PredictionBackspaceAction.SendToFcitx
    !hasNativePredictionCandidatesVisible && !hasAiPredictionCandidatesVisible ->
        PredictionBackspaceAction.SendToFcitx
    predictionBackspaceBehavior == PredictionBackspaceBehavior.DeleteText ->
        PredictionBackspaceAction.DeleteText
    isRimeInputMethod -> PredictionBackspaceAction.DismissCandidates
    predictionBackspaceBehavior == PredictionBackspaceBehavior.DismissCandidates ->
        PredictionBackspaceAction.DismissCandidates
    else -> PredictionBackspaceAction.SendToFcitx
}
