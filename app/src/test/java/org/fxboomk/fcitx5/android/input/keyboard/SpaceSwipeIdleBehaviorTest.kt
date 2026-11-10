/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceSwipeIdleBehaviorTest {
    @Test
    fun legacyCheckboxSelectionsMapToTheCorrespondingSingleOption() {
        val expected = mapOf(
            emptySet<String>() to SpaceSwipeIdleBehavior.Default,
            setOf("UpSelect", "DownCursor") to SpaceSwipeIdleBehavior.SelectionThenCursor,
            setOf("UpSelect") to SpaceSwipeIdleBehavior.SelectionOnly,
            setOf("DownCursor") to SpaceSwipeIdleBehavior.CursorOnly,
            setOf("Default", "unknown") to SpaceSwipeIdleBehavior.Default,
        )
        expected.forEach { (stored, behavior) ->
            assertEquals(behavior, SpaceSwipeIdleBehavior.fromStoredValue(stored))
        }
    }

    @Test
    fun everyNewOptionRoundTripsAsOneEnumName() {
        assertEquals(5, SpaceSwipeIdleBehavior.entries.size)
        SpaceSwipeIdleBehavior.entries.forEach { behavior ->
            assertEquals(behavior, SpaceSwipeIdleBehavior.fromStoredValue(behavior.name))
        }
    }

    @Test
    fun missingAndInvalidStorageUseDefaultAndLegacyNamesRemainReadable() {
        listOf(null, "unknown", 1, true, setOf(42)).forEach { value ->
            assertEquals(SpaceSwipeIdleBehavior.Default, SpaceSwipeIdleBehavior.fromStoredValue(value))
        }
        assertEquals(SpaceSwipeIdleBehavior.SelectionOnly, SpaceSwipeIdleBehavior.fromStoredValue("UpSelect"))
        assertEquals(SpaceSwipeIdleBehavior.CursorOnly, SpaceSwipeIdleBehavior.fromStoredValue("DownCursor"))
    }
}
