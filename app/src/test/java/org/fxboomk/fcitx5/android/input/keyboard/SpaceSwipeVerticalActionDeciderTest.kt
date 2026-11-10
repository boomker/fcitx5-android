/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceSwipeVerticalActionDeciderTest {
    private fun action(
        delta: Int,
        idle: SpaceSwipeIdleBehavior = SpaceSwipeIdleBehavior.SelectionThenCursor,
        preedit: Boolean = false,
        candidates: Boolean = false,
        composing: SpaceSwipeVerticalBehavior = SpaceSwipeVerticalBehavior.ArrowKeys,
        mainKeyboard: Boolean = true,
        visibleAi: Boolean = false,
    ) = spaceSwipeVerticalAction(delta, preedit, candidates, composing, idle, mainKeyboard, visibleAi)

    @Test
    fun allFiveIdleOptionsRouteBothPhysicalDirections() {
        val selection = SpaceSwipeVerticalActionType.TextSelection
        val cursor = SpaceSwipeVerticalActionType.CursorMovement
        val arrow = SpaceSwipeVerticalActionType.ArrowKey
        val expected = mapOf(
            SpaceSwipeIdleBehavior.SelectionThenCursor to (selection to cursor),
            SpaceSwipeIdleBehavior.CursorThenSelection to (cursor to selection),
            SpaceSwipeIdleBehavior.SelectionOnly to (selection to selection),
            SpaceSwipeIdleBehavior.CursorOnly to (cursor to cursor),
            SpaceSwipeIdleBehavior.Default to (arrow to arrow),
        )
        expected.forEach { (behavior, directions) ->
            assertEquals("$behavior swipe up", directions.first, action(-1, behavior))
            assertEquals("$behavior swipe down", directions.second, action(1, behavior))
        }
    }

    @Test
    fun defaultKeepsArrowMovementForBothExistingCandidateSettings() {
        SpaceSwipeVerticalBehavior.entries.forEach { composing ->
            listOf(-1, 1).forEach { delta ->
                assertEquals(SpaceSwipeVerticalActionType.ArrowKey,
                    action(delta, SpaceSwipeIdleBehavior.Default, composing = composing))
            }
        }
    }

    @Test
    fun compositionOrCandidatesAlwaysKeepTheirOwnBehavior() {
        SpaceSwipeIdleBehavior.entries.forEach { idle ->
            listOf(true to false, false to true, true to true).forEach { (preedit, candidates) ->
                listOf(-1, 1).forEach { delta ->
                    assertEquals(SpaceSwipeVerticalActionType.CandidateHighlight,
                        action(delta, idle, preedit, candidates))
                    assertEquals(SpaceSwipeVerticalActionType.CandidateRows,
                        action(delta, idle, preedit, candidates, SpaceSwipeVerticalBehavior.CandidateRows))
                }
            }
        }
    }

    @Test
    fun otherWindowsAndVisibleAiCandidatesPreventEntryForEveryOption() {
        SpaceSwipeIdleBehavior.entries.forEach { idle ->
            listOf(-1, 1).forEach { delta ->
                assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(delta, idle, mainKeyboard = false))
                assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(delta, idle, visibleAi = true))
            }
        }
    }

    @Test
    fun missingDirectionDoesNotEnterAnEditingMode() {
        SpaceSwipeIdleBehavior.entries.forEach { idle ->
            assertEquals(SpaceSwipeVerticalActionType.ArrowKey, action(0, idle))
        }
    }
}
