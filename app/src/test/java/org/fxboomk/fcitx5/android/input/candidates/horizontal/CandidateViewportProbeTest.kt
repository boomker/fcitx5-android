/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.fxboomk.fcitx5.android.core.CandidateViewportSnapshot
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateViewportProbeTest {
    private fun word(index: Int, text: String, source: Int = -1, count: Int = 0) =
        CandidateWord("", text, "", false, nativeIndex = index, symbolSourceId = source, symbolCount = count)

    private fun snapshot(vararg words: CandidateWord, total: Int = words.size, enabled: Boolean = true) =
        CandidateViewportSnapshot(7, enabled, total, arrayOf(*words))

    private fun probe(
        snapshot: CandidateViewportSnapshot,
        width: Int,
        maxCount: Int = Int.MAX_VALUE,
        measure: (CandidateWord) -> Int = { 20 },
    ): CandidateViewportProbe = probeCandidateViewport(snapshot, width, normalizeRow = { candidates ->
        var used = 0
        candidates.takeWhileIndexed { index, candidate ->
            if (index >= maxCount || index > 0 && used + measure(candidate) > width) false
            else { used += measure(candidate); true }
        }.toTypedArray()
    }, measureCandidateWidth = measure)

    @Test
    fun keepsOneFoldedHeadAndFillsWithOrdinaryCandidates() {
        val result = probe(snapshot(
            word(0, "叉"), word(1, "❌", 0, 2), word(2, "茶"),
            word(3, "🍵", 2, 1), word(4, "❎", 0, 2), word(5, "查"),
        ), width = 60)

        assertArrayEquals(intArrayOf(0, 1, 2), result.firstPageIndices)
        assertEquals(1, result.firstSymbolIndex)
        assertEquals("❌…", result.candidates[1].textWithComment())
        assertEquals(1, result.candidates.count { it.symbolSourceId >= 0 })
    }

    @Test
    fun aTooWideFirstGroupDoesNotBlockAFittingLaterHead() {
        val result = probe(snapshot(
            word(0, "叉"), word(1, "❌", 0, 2), word(2, "茶"),
            word(3, "❎", 0, 2), word(4, "🍵", 2, 1),
        ), width = 70, measure = { if (it.text == "❌") 100 else 20 })

        assertArrayEquals(intArrayOf(0, 2, 4), result.firstPageIndices)
        assertEquals(4, result.firstSymbolIndex)
        assertEquals("🍵", result.candidates.last().textWithComment())
    }

    @Test
    fun noFittingHeadLeavesOnlyTheMeasuredOrdinaryPrefix() {
        val head = word(1, "❌", 0, 2).copy(comment = "(叉)")
        val result = probe(snapshot(word(0, "叉"), head, word(2, "❎", 0, 2)),
            width = 40, measure = { if (it.symbolSourceId >= 0) 80 else 30 })

        assertArrayEquals(intArrayOf(0), result.firstPageIndices)
        assertEquals(-1, result.firstSymbolIndex)
        assertEquals("(叉)", head.comment)
    }

    @Test
    fun preservesOrderedNativeGapsFromRecoveryPlacement() {
        val input = snapshot(*(0..5).map { word(it, "字$it") }.toTypedArray())
        val result = probeCandidateViewport(input, 120,
            normalizeRow = { candidates -> intArrayOf(0, 4, 2).map { id -> candidates.first { it.nativeIndex == id } }.toTypedArray() },
            measureCandidateWidth = { 20 },
        )

        assertArrayEquals(intArrayOf(0, 4, 2), result.firstPageIndices)
        assertEquals(-1, result.firstSymbolIndex)
    }

    @Test
    fun requestsMoreWhileTheProbeCutsThroughDeferredSymbols() {
        val result = probe(snapshot(word(0, "叉"), word(1, "茶"), word(2, "❌", 0, 2), total = 7),
            width = 40)

        assertTrue(result.needsMoreCandidates)
    }

    @Test
    fun requestsMoreWhenTheBoundarySourceCanHaveAnUnseenHead() {
        val result = probe(snapshot(word(0, "叉"), word(1, "❌", 0, 2), word(2, "茶"), total = 4),
            width = 70, measure = { if (it.symbolSourceId >= 0) 100 else 20 })

        assertTrue(result.needsMoreCandidates)
    }

    @Test
    fun doesNotGrowAWidthAndCountLimitedCompletePrefix() {
        val result = probe(snapshot(word(0, "一"), word(1, "二"), word(2, "三"), total = 9),
            width = 100, maxCount = 2)

        assertArrayEquals(intArrayOf(0, 1), result.firstPageIndices)
        assertFalse(result.needsMoreCandidates)
    }

    @Test
    fun disabledFoldingKeepsLegacySymbolsInTheMeasuredPrefix() {
        val result = probe(snapshot(word(0, "叉"), word(1, "❌", 0, 2), word(2, "❎", 0, 2), enabled = false),
            width = 60)

        assertArrayEquals(intArrayOf(0, 1, 2), result.firstPageIndices)
        assertEquals(-1, result.firstSymbolIndex)
        assertEquals("❌", result.candidates[1].textWithComment())
    }

    @Test
    fun anOversizedOrphanHeadKeepsTheExistingBoundedFirstSlot() {
        val result = probe(snapshot(word(0, "❌", 3, 2), word(1, "❎", 3, 2)),
            width = 40, measure = { 80 })

        assertArrayEquals(intArrayOf(0), result.firstPageIndices)
        assertEquals(0, result.firstSymbolIndex)
    }

    private fun <T> Array<T>.takeWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
        val result = ArrayList<T>()
        for ((index, item) in withIndex()) {
            if (!predicate(index, item)) break
            result.add(item)
        }
        return result
    }
}
