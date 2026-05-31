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

    @Test
    fun `keeps whole ai suggestion in expanded area when it does not fit remaining row`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = arrayOf("native"),
            aiSuggestions = listOf("long ai"),
            availableWidth = 100,
            dividerWidth = 0,
            maxCandidateCount = Int.MAX_VALUE,
        ) { candidate ->
            when (candidate) {
                "native" -> 70
                "long ai" -> 40
                else -> 0
            }
        }

        assertEquals(listOf("native"), placement.candidates.toList())
        assertEquals(0, placement.visibleAiCount)
    }

    @Test
    fun `appends ai suggestions until the next whole suggestion would overflow`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = arrayOf("native"),
            aiSuggestions = listOf("ai1", "ai2"),
            availableWidth = 100,
            dividerWidth = 0,
            maxCandidateCount = Int.MAX_VALUE,
        ) { candidate ->
            when (candidate) {
                "native" -> 40
                "ai1" -> 30
                "ai2" -> 40
                else -> 0
            }
        }

        assertEquals(listOf("native", "ai1"), placement.candidates.toList())
        assertEquals(1, placement.visibleAiCount)
    }

    @Test
    fun `does not force an oversized ai suggestion into an empty candidate row`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = emptyArray(),
            aiSuggestions = listOf("oversized ai"),
            availableWidth = 100,
            dividerWidth = 0,
            maxCandidateCount = Int.MAX_VALUE,
        ) { 140 }

        assertEquals(emptyList<String>(), placement.candidates.toList())
        assertEquals(0, placement.visibleAiCount)
    }

    @Test
    fun `places ai suggestion in empty row when it fits completely`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = emptyArray(),
            aiSuggestions = listOf("ai"),
            availableWidth = 100,
            dividerWidth = 0,
            maxCandidateCount = Int.MAX_VALUE,
        ) { 80 }

        assertEquals(listOf("ai"), placement.candidates.toList())
        assertEquals(1, placement.visibleAiCount)
    }

    @Test
    fun `candidate expanded placement keeps native candidates before ai suggestions`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = arrayOf("native1", "native2"),
            aiSuggestions = listOf("ai1"),
            availableWidth = 120,
            dividerWidth = 0,
            maxCandidateCount = Int.MAX_VALUE,
        ) { 30 }

        assertEquals(listOf("native1", "native2", "ai1"), placement.candidates.toList())
        assertEquals(1, placement.visibleAiCount)
    }
}
