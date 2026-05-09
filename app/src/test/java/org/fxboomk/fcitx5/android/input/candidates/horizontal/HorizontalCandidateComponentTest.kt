/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalCandidateComponentTest {

    @Test
    fun `uses paged cursor index when it is within range`() {
        assertEquals(2, activeCandidateIndex(cursorIndex = 2, candidateCount = 5))
    }

    @Test
    fun `clamps negative cursor index to first candidate`() {
        assertEquals(0, activeCandidateIndex(cursorIndex = -1, candidateCount = 3))
    }

    @Test
    fun `clamps cursor index beyond visible row to last visible candidate`() {
        assertEquals(1, activeCandidateIndex(cursorIndex = 7, candidateCount = 2))
    }

    @Test
    fun `returns no active candidate for empty list`() {
        assertEquals(-1, activeCandidateIndex(cursorIndex = 0, candidateCount = 0))
    }

    @Test
    fun `moves active candidate to next item`() {
        assertEquals(2, moveActiveCandidateIndex(currentIndex = 1, delta = 1, candidateCount = 4))
    }

    @Test
    fun `moves active candidate to previous item`() {
        assertEquals(0, moveActiveCandidateIndex(currentIndex = 1, delta = -1, candidateCount = 4))
    }

    @Test
    fun `keeps active candidate within visible bounds`() {
        assertEquals(0, moveActiveCandidateIndex(currentIndex = 0, delta = -1, candidateCount = 4))
        assertEquals(3, moveActiveCandidateIndex(currentIndex = 3, delta = 1, candidateCount = 4))
    }
}
