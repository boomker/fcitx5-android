/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceSwipeIdleBehaviorTest {
    @Test
    fun bothIdleGesturesCanBeSelectedAndRemovedIndependently() {
        val up = SpaceSwipeIdleBehavior.updateSelection(emptySet(), SpaceSwipeIdleBehavior.UpSelect, true)
        val both = SpaceSwipeIdleBehavior.updateSelection(up, SpaceSwipeIdleBehavior.DownCursor, true)
        assertEquals(setOf("UpSelect", "DownCursor"), both)
        assertEquals(
            setOf("DownCursor"),
            SpaceSwipeIdleBehavior.updateSelection(both, SpaceSwipeIdleBehavior.UpSelect, false)
        )
        assertEquals(
            emptySet<String>(),
            SpaceSwipeIdleBehavior.updateSelection(setOf("DownCursor"), SpaceSwipeIdleBehavior.DownCursor, false)
        )
    }

    @Test
    fun defaultClearsBothGesturesAndCannotBePersistedAlongsideThem() {
        assertEquals(
            emptySet<String>(),
            SpaceSwipeIdleBehavior.updateSelection(setOf("UpSelect", "DownCursor"), SpaceSwipeIdleBehavior.Default, true)
        )
        assertEquals(
            setOf("UpSelect"),
            SpaceSwipeIdleBehavior.updateSelection(setOf("Default", "unknown"), SpaceSwipeIdleBehavior.UpSelect, true)
        )
    }
}
