/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPageCandidateTrackerTest {
    @Test
    fun `no actual first layout leaves visibility unknown`() {
        assertNull(FirstPageCandidateTracker().contains(4))
    }

    @Test
    fun `actual first row records native indices rather than displayed ordinals`() {
        val tracker = FirstPageCandidateTracker()
        tracker.record(setOf(0, 1, 4), isFirstPage = true)
        assertEquals(true, tracker.contains(4))
        assertEquals(false, tracker.contains(2))
    }

    @Test
    fun `swiped or expanded rows cannot overwrite saved first page`() {
        val tracker = FirstPageCandidateTracker()
        tracker.record(setOf(0, 1, 4), true)
        tracker.record(setOf(2, 3, 5), false)
        assertEquals(true, tracker.contains(4))
        assertEquals(false, tracker.contains(2))
    }

    @Test
    fun `width layout replaces first row with actual fully visible indices`() {
        val tracker = FirstPageCandidateTracker()
        tracker.record(setOf(0, 1, 4), true)
        tracker.clear()
        tracker.record(setOf(0, 4), true)
        assertEquals(false, tracker.contains(1))
        assertEquals(true, tracker.contains(4))
    }

    @Test
    fun `engine later pages never reuse first page relative ordinals`() {
        val tracker = FirstPageCandidateTracker()
        tracker.record(setOf(0, 1, 4), true)
        assertEquals(false, tracker.contains(0, nativePageHasPrevious = true))
        assertEquals(true, tracker.contains(0, nativePageHasPrevious = false))
    }

    @Test
    fun `composition clear cannot leak first row membership into next composition`() {
        val tracker = FirstPageCandidateTracker()
        tracker.record(setOf(0, 1, 4), true)
        tracker.clear()
        assertNull(tracker.contains(4))
        tracker.record(setOf(0, 1), true)
        assertEquals(false, tracker.contains(4))
    }

    @Test
    fun `known empty deferred first row records off page rather than unknown`() {
        val tracker = FirstPageCandidateTracker()
        tracker.record(emptySet(), true)
        assertFalse(tracker.contains(0)!!)
        tracker.record(setOf(0), false)
        assertFalse(tracker.contains(0)!!)
        tracker.record(setOf(0), true)
        assertTrue(tracker.contains(0)!!)
    }
}
