/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceSwipeVerticalActionDeciderTest {
    private val both = setOf(SpaceSwipeIdleBehavior.UpSelect.name, SpaceSwipeIdleBehavior.DownCursor.name)

    private fun action(
        delta: Int,
        enabled: Set<String> = both,
        preedit: Boolean = false,
        candidates: Boolean = false,
        behavior: SpaceSwipeVerticalBehavior = SpaceSwipeVerticalBehavior.ArrowKeys,
        mainKeyboard: Boolean = true,
    ) = spaceSwipeVerticalAction(delta, preedit, candidates, behavior, enabled, mainKeyboard)

    @Test
    fun bothIdleGesturesCanBeEnabledAndRouteIndependently() {
        assertEquals(SpaceSwipeVerticalActionType.TextSelection, action(-1))
        assertEquals(SpaceSwipeVerticalActionType.CursorMovement, action(1))
    }

    @Test
    fun defaultAndUnconfiguredDirectionsKeepExistingArrowBehavior() {
        SpaceSwipeVerticalBehavior.entries.forEach { behavior ->
            listOf(-1, 1).forEach { delta ->
                assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(delta, emptySet(), behavior = behavior))
            }
        }
        assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(1, setOf(SpaceSwipeIdleBehavior.UpSelect.name)))
        assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(-1, setOf(SpaceSwipeIdleBehavior.DownCursor.name)))
    }

    @Test
    fun compositionAndPredictionCandidatesPreventEditingModeEntry() {
        listOf(true to false, false to true, true to true).forEach { (preedit, candidates) ->
            listOf(-1, 1).forEach { delta ->
                assertEquals(SpaceSwipeVerticalActionType.CandidateHighlight, action(delta, preedit = preedit, candidates = candidates))
                assertEquals(SpaceSwipeVerticalActionType.CandidateRows, action(delta, preedit = preedit, candidates = candidates,
                    behavior = SpaceSwipeVerticalBehavior.CandidateRows))
            }
        }
    }

    @Test
    fun otherInputWindowsCannotEnterEditingMode() {
        listOf(-1, 1).forEach { delta ->
            assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(delta, mainKeyboard = false))
        }
    }

    @Test
    fun visibleAiSuggestionsPreventEntryAndKeepDefaultArrowBehavior() {
        listOf(-1, 1).forEach { delta ->
            assertEquals(SpaceSwipeVerticalActionType.ArrowKey, spaceSwipeVerticalAction(
                delta, false, false, SpaceSwipeVerticalBehavior.ArrowKeys, both, true,
                hasVisibleAiSuggestions = true,
            ))
        }
    }
}
