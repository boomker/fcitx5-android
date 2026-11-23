/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.fxboomk.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbbreviationCandidateRowTest {
    private fun words(best: Int = 6, fallback: Int = -1) = Array(10) { index ->
        CandidateWord("", "词$index", "", nativeIndex = index,
            isAbbreviation = index == best || index == 2 || index == 8,
            isBestAbbreviation = index == best, isFallback = index == fallback)
    }

    private fun row(
        source: Array<CandidateWord>,
        width: Int = 150,
        slots: Int = 5,
        divider: Int = 0,
        measure: (CandidateWord) -> Int = { 50 },
    ): Array<CandidateWord> = placeAbbreviationCandidateInRow(
        nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet()),
        width, divider, slots, measure,
    )

    @Test
    fun `natural best abbreviation keeps its score order`() {
        val source = words(best = 1)
        assertEquals(listOf(0, 1, 2), row(source).map { it.nativeIndex })
        assertEquals(1, row(source).count { it.isBestAbbreviation })
    }

    @Test
    fun `naturally leading best abbreviation is not moved to the last slot`() {
        assertEquals(listOf(0, 1, 2), row(words(best = 0)).map { it.nativeIndex })
    }

    @Test
    fun `best abbreviation outside the natural row takes the last visible slot`() {
        val source = words()
        assertEquals(listOf(0, 1, 6), row(source).map { it.nativeIndex })
        assertTrue(row(source).last().isBestAbbreviation)
    }

    @Test
    fun `wide homepage fills available slots with ranked abbreviation alternatives`() {
        val source = words()
        val actual = row(source, width = 1000, slots = 20)
        assertEquals(source.toList(), actual.toList())
        assertEquals(1, actual.count { it.isBestAbbreviation })
        assertEquals(3, actual.count { it.isAbbreviation })
    }

    @Test
    fun `two key abbreviations fill the remaining width after the best and correction`() {
        val source = arrayOf("我们", "前", "雾霾", "完美", "外面", "外贸", "闻名", "网民")
            .mapIndexed { index, text ->
                CandidateWord("", text, "", nativeIndex = index,
                    isAbbreviation = index != 1, isBestAbbreviation = index == 0,
                    isFallback = index == 1)
            }.toTypedArray()

        val actual = row(source, width = 300, slots = 6)

        assertEquals(source.take(6), actual.toList())
        assertTrue(actual.first().isBestAbbreviation)
        assertTrue(actual[1].isFallback)
        assertEquals(5, actual.count { it.isAbbreviation })
        val continuation = nativeCandidateContinuation(0, actual, emptySet())
        val later = nativeCandidatesAfterConsumedFallbacks(
            source.drop(continuation.nextOffset).toTypedArray(), continuation.nextOffset,
            continuation.excludedIndices, isInitialRow = false,
        )
        assertEquals(source.toList(), actual.toList() + later)
    }

    @Test
    fun `a narrow two key row keeps the best and correction before alternatives`() {
        val source = arrayOf("我们", "前", "雾霾", "完美").mapIndexed { index, text ->
            CandidateWord("", text, "", nativeIndex = index,
                isAbbreviation = index != 1, isBestAbbreviation = index == 0,
                isFallback = index == 1)
        }.toTypedArray()

        val actual = row(source, width = 100, slots = 6)

        assertEquals(source.take(2), actual.toList())
        assertEquals(1, actual.count { it.isAbbreviation })
    }

    @Test
    fun `homepage filtering and early best placement preserve every later native index once`() {
        val source = words()
        for (width in listOf(50, 100, 150, 250, 1000)) {
            val first = row(source, width, 20)
            val continuation = nativeCandidateContinuation(0, first, emptySet())
            val later = nativeCandidatesAfterConsumedFallbacks(
                source.drop(continuation.nextOffset).toTypedArray(), continuation.nextOffset,
                continuation.excludedIndices, isInitialRow = false,
            )
            val visited = (first.toList() + later).map { it.nativeIndex }
            assertEquals(source.indices.toList(), visited.sorted())
            assertEquals(source.size, visited.distinct().size)
            assertEquals(1, first.count { it.isBestAbbreviation })
        }
    }

    @Test
    fun `nonbest abbreviations remain on later pages and expanded lists`() {
        val source = words()
        val later = nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet(), isInitialRow = false)
        assertEquals(source.toList(), later.toList())
        assertEquals(3, later.count { it.isAbbreviation })
    }

    @Test
    fun `one available slot shows the best abbreviation without a correction`() {
        assertEquals(listOf(6), row(words(), width = 50, slots = 1).map { it.nativeIndex })
    }

    @Test
    fun `correction keeps second slot and best abbreviation occupies final slot`() {
        val actual = row(words(fallback = 4))
        assertEquals(listOf(0, 4, 6), actual.map { it.nativeIndex })
        assertTrue(actual[1].isFallback)
        assertTrue(actual.last().isBestAbbreviation)
    }

    @Test
    fun `two slots preserve abbreviation and correction even if head must wait`() {
        val source = words(fallback = 4)
        for (width in listOf(50, 100, 200)) {
            assertEquals(listOf(6, 4), row(source, width, slots = 2).map { it.nativeIndex })
        }
    }

    @Test
    fun `one slot gives correction priority and leaves abbreviation for expansion`() {
        val source = words(fallback = 4)
        val actual = row(source, width = 100, slots = 1)
        assertEquals(listOf(4), actual.map { it.nativeIndex })
        val next = nativeCandidateContinuation(0, actual, emptySet())
        val later = nativeCandidatesAfterConsumedFallbacks(source, next.nextOffset, next.excludedIndices, false)
        assertTrue(later.any { it.isBestAbbreviation })
    }

    @Test
    fun `a naturally visible best keeps its position alongside second slot correction`() {
        assertEquals(listOf(0, 4, 1), row(words(best = 0, fallback = 4)).map { it.nativeIndex })
        assertEquals(listOf(0, 4, 1), row(words(best = 1, fallback = 4)).map { it.nativeIndex })
    }

    @Test
    fun `large text narrow rows cap every protected candidate without losing identities`() {
        val source = words(fallback = 4)
        for (width in listOf(20, 52, 100, 201)) {
            for (divider in listOf(0, 1, 3)) {
                val actual = row(source, width, divider = divider) { 320 }
                assertEquals(listOf(0, 4, 6), actual.map { it.nativeIndex })
                val limits = protectedCandidateRowWidthLimits(actual, width, divider) { 320 }
                assertTrue(limits.sumOf { minOf(it, 320).toLong() + divider } <= width)
            }
        }
    }

    @Test
    fun `width caps preserve short text while bounding longer protected choices`() {
        val source = words(fallback = 4)
        val actual = row(source, 100) { if (it.nativeIndex == 0) 20 else 320 }
        val limits = protectedCandidateRowWidthLimits(actual, 100, 0) {
            if (it.nativeIndex == 0) 20 else 320
        }
        assertEquals(listOf(0, 4, 6), actual.map { it.nativeIndex })
        assertEquals(listOf(20, 40, 40), actual.mapIndexed { index, candidate ->
            minOf(if (candidate.nativeIndex == 0) 20 else 320, limits[index])
        })
    }

    @Test
    fun `ordinary rows and learned corrections keep existing native rank without abbreviation flags`() {
        val ordinary = words().map { it.copy(isAbbreviation = false, isBestAbbreviation = false) }.toTypedArray()
        assertFalse(row(ordinary).any { it.isAbbreviation })
        assertEquals(listOf(0, 1, 2), row(ordinary).take(3).map { it.nativeIndex })
        assertTrue(protectedCandidateRowWidthLimits(ordinary.take(2).toTypedArray(), 100, 0) { 40 }
            .all { it == Int.MAX_VALUE })
    }

    @Test
    fun `empty space does not publish a clickable protected candidate`() {
        assertTrue(row(words(), width = 0).isEmpty())
        assertTrue(row(words(), slots = 0).isEmpty())
    }
}
