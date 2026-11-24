/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalCandidateComponentTest {

    @Test
    fun `highlight spacing preserves the existing candidate item width`() {
        assertEquals(4, HORIZONTAL_CANDIDATE_OUTER_PADDING_DP)
        assertEquals(8, HORIZONTAL_CANDIDATE_HIGHLIGHT_PADDING_DP)
        assertEquals(4, HORIZONTAL_CANDIDATE_VERTICAL_PADDING_DP)
    }

    @Test
    fun `always fill keeps a single candidate at its natural width`() {
        val sizing = resolveHorizontalCandidateLayoutSizing(
            fillStyle = HorizontalCandidateMode.AlwaysFillWidth,
            candidateCount = 1,
            availableWidth = 500,
            maxSpanCount = 5,
            dividerWidth = 1,
        )

        assertEquals(0, sizing.minWidth)
        assertEquals(0f, sizing.flexGrow)
        assertEquals(false, sizing.secondLayoutPassNeeded)
    }

    @Test
    fun `always fill still distributes multiple candidates`() {
        val sizing = resolveHorizontalCandidateLayoutSizing(
            fillStyle = HorizontalCandidateMode.AlwaysFillWidth,
            candidateCount = 2,
            availableWidth = 500,
            maxSpanCount = 5,
            dividerWidth = 1,
        )

        assertEquals(0, sizing.minWidth)
        assertEquals(1f, sizing.flexGrow)
        assertEquals(false, sizing.secondLayoutPassNeeded)
    }

    @Test
    fun `auto fill preserves one candidate slot width without stretching`() {
        val sizing = resolveHorizontalCandidateLayoutSizing(
            fillStyle = HorizontalCandidateMode.AutoFillWidth,
            candidateCount = 1,
            availableWidth = 500,
            maxSpanCount = 5,
            dividerWidth = 1,
        )

        assertEquals(99, sizing.minWidth)
        assertEquals(0f, sizing.flexGrow)
        assertEquals(true, sizing.secondLayoutPassNeeded)
    }

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
    fun `advances candidate window by one visible row at a time`() {
        val secondRowStart = nextCandidateRowStart(
            currentStart = 0,
            prefetchedCandidateCount = 24,
            visibleRowCount = 4,
        )
        val thirdRowStart = nextCandidateRowStart(
            currentStart = secondRowStart,
            prefetchedCandidateCount = 20,
            visibleRowCount = 3,
        )

        assertEquals(4, secondRowStart)
        assertEquals(7, thirdRowStart)
    }

    @Test
    fun `advances at least one candidate for a measured empty row`() {
        assertEquals(
            6,
            nextCandidateRowStart(
                currentStart = 5,
                prefetchedCandidateCount = 8,
                visibleRowCount = 0,
            )
        )
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
    fun `same text ai suggestion retains its single line width beside top pinyin`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = arrayOf("你 (nǐ)"),
            aiSuggestions = listOf("你 (nǐ)"),
            availableWidth = 100,
            dividerWidth = 0,
            maxCandidateCount = Int.MAX_VALUE,
            measureAiCandidateWidth = { 80 },
        ) { 40 }

        assertEquals(listOf("你 (nǐ)"), placement.candidates.toList())
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
    fun `keeps calculator result when the candidate row has no measured width`() {
        val placement = placeAiCandidatesInRow(
            nativeCandidates = arrayOf("51.8"),
            aiSuggestions = listOf("ai"),
            availableWidth = 1,
            dividerWidth = 1,
            maxCandidateCount = Int.MAX_VALUE,
        ) { candidate ->
            if (candidate == "51.8") 80 else 40
        }

        assertEquals(listOf("51.8"), placement.candidates.toList())
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

    @Test
    fun `numbers candidates by displayed position for digit keys up to nine`() {
        assertEquals(1, indexLabelForPosition(position = 0, enabled = true))
        assertEquals(9, indexLabelForPosition(position = 8, enabled = true))
    }

    @Test
    fun `hides index number beyond selectable digit keys`() {
        assertEquals(null, indexLabelForPosition(position = 9, enabled = true))
    }

    @Test
    fun `hides index numbers on the virtual keyboard`() {
        assertEquals(null, indexLabelForPosition(position = 0, enabled = false))
        assertEquals(null, indexLabelForPosition(position = 8, enabled = false))
    }
}
