/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

internal enum class SpaceSwipeVerticalActionType {
    CandidateHighlight, CandidateRows, ArrowKey, TextSelection, CursorMovement
}

internal fun spaceSwipeVerticalAction(
    delta: Int,
    hasPreedit: Boolean,
    hasCandidates: Boolean,
    composingBehavior: SpaceSwipeVerticalBehavior,
    idleBehavior: SpaceSwipeIdleBehavior,
    isMainKeyboard: Boolean,
    hasVisibleAiSuggestions: Boolean = false,
): SpaceSwipeVerticalActionType {
    if (hasPreedit || hasCandidates) {
        return when (composingBehavior) {
            SpaceSwipeVerticalBehavior.ArrowKeys -> SpaceSwipeVerticalActionType.CandidateHighlight
            SpaceSwipeVerticalBehavior.CandidateRows -> SpaceSwipeVerticalActionType.CandidateRows
        }
    }
    if (!isMainKeyboard || hasVisibleAiSuggestions || delta == 0) return SpaceSwipeVerticalActionType.ArrowKey
    return when (idleBehavior) {
        SpaceSwipeIdleBehavior.SelectionThenCursor ->
            if (delta < 0) SpaceSwipeVerticalActionType.TextSelection else SpaceSwipeVerticalActionType.CursorMovement
        SpaceSwipeIdleBehavior.CursorThenSelection ->
            if (delta < 0) SpaceSwipeVerticalActionType.CursorMovement else SpaceSwipeVerticalActionType.TextSelection
        SpaceSwipeIdleBehavior.SelectionOnly -> SpaceSwipeVerticalActionType.TextSelection
        SpaceSwipeIdleBehavior.CursorOnly -> SpaceSwipeVerticalActionType.CursorMovement
        SpaceSwipeIdleBehavior.Default -> SpaceSwipeVerticalActionType.ArrowKey
    }
}
