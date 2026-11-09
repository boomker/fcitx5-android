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
    idleBehaviors: Set<String>,
    isMainKeyboard: Boolean,
    hasVisibleAiSuggestions: Boolean = false,
): SpaceSwipeVerticalActionType {
    if (hasPreedit || hasCandidates) {
        return when (composingBehavior) {
            SpaceSwipeVerticalBehavior.ArrowKeys -> SpaceSwipeVerticalActionType.CandidateHighlight
            SpaceSwipeVerticalBehavior.CandidateRows -> SpaceSwipeVerticalActionType.CandidateRows
        }
    }
    return when {
        isMainKeyboard && !hasVisibleAiSuggestions && delta < 0 && SpaceSwipeIdleBehavior.UpSelect.name in idleBehaviors ->
            SpaceSwipeVerticalActionType.TextSelection
        isMainKeyboard && !hasVisibleAiSuggestions && delta > 0 && SpaceSwipeIdleBehavior.DownCursor.name in idleBehaviors ->
            SpaceSwipeVerticalActionType.CursorMovement
        else -> SpaceSwipeVerticalActionType.ArrowKey
    }
}
