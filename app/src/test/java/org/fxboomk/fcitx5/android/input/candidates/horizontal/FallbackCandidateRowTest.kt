/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.input.bar.candidateSurfaceIsEmpty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCandidateRowTest {
    private fun candidates(fallbackIndex: Int = 4) = Array(8) { index ->
        CandidateWord(
            label = "", text = "candidate$index", comment = "",
            isFallback = index == fallbackIndex, nativeIndex = index,
        )
    }

    private fun deferredCandidates(index: Int = 4) = candidates(fallbackIndex = -1).also {
        it[index] = it[index].copy(isDeferredFallback = true)
    }

    private fun correctionGroup() = Array(12) { index ->
        CandidateWord(
            label = "",
            text = when (index) { 4 -> "分"; 10 -> "份"; 11 -> "奋"; else -> "合法$index" },
            comment = "", isFallback = index == 4, isDeferredFallback = index >= 10, nativeIndex = index,
        )
    }

    @Test
    fun `default correction group shows only main on first row and keeps alternatives in expanded tail`() {
        val source = correctionGroup()
        for (slots in listOf(1, 3, 7, 20)) {
            val eligible = nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet())
            val row = placeFallbackCandidateInRow(eligible, slots * 50, 0, slots) { 50 }
            assertEquals(1, row.count { it.nativeIndex in setOf(4, 10, 11) })
            assertEquals(4, row[minOf(1, row.lastIndex)].nativeIndex)
            assertFalse(row.any { it.isDeferredFallback })
            val next = nativeCandidateContinuation(0, row, emptySet())
            val expanded = nativeCandidatesAfterConsumedFallbacks(
                source.drop(next.nextOffset).toTypedArray(), next.nextOffset, next.excludedIndices, false,
            )
            assertEquals(listOf(10, 11), expanded.takeLast(2).map { it.nativeIndex })
            val all = (row.toList() + expanded).map { it.nativeIndex }
            assertEquals((0 until source.size).toList(), all.sorted())
            assertEquals(source.size, all.distinct().size)
        }
    }

    @Test
    fun `multiple correction holes page without totals without loss or duplication`() {
        val deferred = correctionGroup().map {
            it.copy(isFallback = false, isDeferredFallback = it.nativeIndex in setOf(4, 10, 11))
        }
        val head = (listOf(deferred[4]) + deferred.filter { it.nativeIndex != 4 })
            .mapIndexed { index, candidate -> candidate.copy(nativeIndex = index) }
        val afterLegal = (deferred.filterNot { it.isDeferredFallback } + deferred.filter { it.isDeferredFallback })
            .mapIndexed { index, candidate -> candidate.copy(nativeIndex = index) }
        for (source in listOf(deferred, head, afterLegal)) {
            for (batchSize in listOf(5, 12)) {
                for (slots in listOf(1, 3, 7)) {
                    var start = 0
                    var excluded = emptySet<Int>()
                    var initial = true
                    var rows = 0
                    val visited = mutableListOf<Int>()
                    while (true) {
                        // Fetch by native offset and limit only; no total is supplied by the engine.
                        val batch = source.drop(start).take(batchSize).toTypedArray()
                        if (batch.isEmpty()) break
                        assertTrue("Paging must make progress", rows++ < source.size * 2)
                        val row = nativeCandidatesAfterConsumedFallbacks(batch, start, excluded, initial)
                            .take(slots).toTypedArray()
                        if (initial) assertFalse(row.any { it.isDeferredFallback })
                        visited += row.map { it.nativeIndex }
                        val next = nativeCandidateContinuation(start, row, excluded)
                        assertTrue(next.nextOffset > start || next.excludedIndices != excluded)
                        start = next.nextOffset
                        excluded = next.excludedIndices
                        initial = false
                    }
                    assertEquals(source.size, start)
                    assertEquals((source.indices).toList(), visited.sorted())
                    assertEquals(source.size, visited.distinct().size)
                }
            }
        }
    }

    @Test
    fun `whole deferred correction group keeps empty home expansion available and adaptive main remains native`() {
        val group = correctionGroup().filter { it.nativeIndex in setOf(4, 10, 11) }
            .mapIndexed { index, candidate ->
                candidate.copy(isFallback = false, isDeferredFallback = true, nativeIndex = index)
            }.toTypedArray()
        val initial = nativeCandidatesAfterConsumedFallbacks(group, 0, emptySet())
        assertTrue(initial.isEmpty())
        val next = nativeCandidateContinuation(0, initial, emptySet())
        val hasMore = group.size > next.nextOffset + next.excludedIndices.size
        assertFalse(candidateSurfaceIsEmpty(
            candidateEmpty = initial.isEmpty(),
            hasExpandableDeferredCandidates = hasMore && group.any { it.isDeferredFallback },
        ))
        val expanded = nativeCandidatesAfterConsumedFallbacks(group, next.nextOffset, next.excludedIndices, false)
        assertEquals(listOf("分", "份", "奋"), expanded.map { it.text })
        assertEquals(listOf(0, 1, 2), expanded.map { it.nativeIndex })
        val adaptive = group.copyOf().also { it[0] = it[0].copy(isDeferredFallback = false) }
        val adaptiveRow = nativeCandidatesAfterConsumedFallbacks(adaptive, 0, emptySet())
        assertEquals(listOf("分"), adaptiveRow.map { it.text })
        assertEquals(0, adaptiveRow.single().nativeIndex)
        assertFalse(adaptiveRow.single().isFallback)
    }

    @Test
    fun `JNI constructor preserves all legacy overloads and the deferred flag`() {
        val strings = arrayOf(String::class.java, String::class.java, String::class.java)
        CandidateWord::class.java.getConstructor(*strings)
        CandidateWord::class.java.getConstructor(*strings, java.lang.Boolean.TYPE)
        CandidateWord::class.java.getConstructor(*strings, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
        val constructor = CandidateWord::class.java.getConstructor(
            *strings, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE,
        )
        val deferred = constructor.newInstance("", "分", "", true, false, true)
        assertTrue(deferred.isDeferredFallback)
        assertFalse(deferred.isFallback)
        assertEquals(-1, deferred.nativeIndex)
    }

    @Test
    fun `deferred correction is absent from narrow and wide initial rows`() {
        val source = deferredCandidates()
        for (slots in 1..10) {
            val row = nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet())
                .take(slots)
            assertFalse("Slots $slots", row.any { it.isDeferredFallback })
            assertEquals("Slots $slots", listOf(0, 1, 2, 3, 5, 6, 7).take(slots), row.map { it.nativeIndex })
        }
        val narrow = nativeCandidatesAfterConsumedFallbacks(deferredCandidates(1), 0, emptySet()).take(2)
        assertEquals(listOf(0, 2), narrow.map { it.nativeIndex })
    }

    @Test
    fun `wide row keeps deferred gap available without repeating later ordinary items`() {
        val source = deferredCandidates()
        val row = nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet())
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 7), row.map { it.nativeIndex })
        val continuation = nativeCandidateContinuation(0, row, emptySet())
        assertEquals(4, continuation.nextOffset)
        assertEquals(setOf(5, 6, 7), continuation.excludedIndices)
        val later = nativeCandidatesAfterConsumedFallbacks(
            source.drop(continuation.nextOffset).toTypedArray(),
            continuation.nextOffset, continuation.excludedIndices, isInitialRow = false,
        )
        assertEquals(listOf(4), later.map { it.nativeIndex })
        assertTrue(later.single().isDeferredFallback)
        assertEquals((0 until 8).toList(), (row.toList() + later).map { it.nativeIndex }.sorted())
        val end = nativeCandidateContinuation(continuation.nextOffset, later, continuation.excludedIndices)
        assertEquals(8, end.nextOffset)
        assertTrue(end.excludedIndices.isEmpty())
    }

    @Test
    fun `unknown total paging visits every item once including a deferred native head`() {
        val source = deferredCandidates(0)
        val visited = mutableListOf<Int>()
        var start = 0
        var excluded = emptySet<Int>()
        var initial = true
        repeat(10) {
            // The engine supplies batches without a total. Only an empty batch ends iteration.
            val batch = source.drop(start).take(5).toTypedArray()
            if (batch.isEmpty()) return@repeat
            val row = nativeCandidatesAfterConsumedFallbacks(batch, start, excluded, initial)
                .take(3).toTypedArray()
            if (initial) assertFalse(row.any { it.isDeferredFallback })
            visited += row.map { it.nativeIndex }
            val next = nativeCandidateContinuation(start, row, excluded)
            assertTrue(next.nextOffset > start || next.excludedIndices != excluded)
            start = next.nextOffset
            excluded = next.excludedIndices
            initial = false
        }
        assertEquals(8, start)
        assertEquals((0 until 8).toList(), visited.sorted())
        assertEquals(8, visited.distinct().size)
        assertTrue(visited.indexOf(0) >= 3)
    }

    @Test
    fun `swipe back restores the exclusions that preceded that row`() {
        val source = deferredCandidates()
        val first = nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet()).take(5).toTypedArray()
        val firstContinuation = nativeCandidateContinuation(0, first, emptySet())
        val secondBatch = source.drop(firstContinuation.nextOffset).toTypedArray()
        val second = nativeCandidatesAfterConsumedFallbacks(
            secondBatch, firstContinuation.nextOffset, firstContinuation.excludedIndices, false,
        ).take(2).toTypedArray()
        assertEquals(listOf(4, 6), second.map { it.nativeIndex })
        val secondContinuation = nativeCandidateContinuation(
            firstContinuation.nextOffset, second, firstContinuation.excludedIndices,
        )
        assertEquals(7, secondContinuation.nextOffset)
        assertTrue(secondContinuation.excludedIndices.isEmpty())
        val restored = nativeCandidatesAfterConsumedFallbacks(
            secondBatch, firstContinuation.nextOffset, firstContinuation.excludedIndices, false,
        ).take(2)
        assertEquals(second.toList(), restored)
    }

    @Test
    fun `default deferred and adaptive transitions discard initial row exclusion history`() {
        val default = candidates()
        val hinted = placeFallbackCandidateInRow(default, 150, 0, 5) { 50 }
        val hintedContinuation = nativeCandidateContinuation(0, hinted, emptySet())
        assertEquals(setOf(4), hintedContinuation.excludedIndices)
        val deferred = deferredCandidates()
        val hidden = nativeCandidatesAfterConsumedFallbacks(deferred, 0, hintedContinuation.excludedIndices)
            .take(5).toTypedArray()
        assertEquals(listOf(0, 1, 2, 3, 5), hidden.map { it.nativeIndex })
        val hiddenContinuation = nativeCandidateContinuation(0, hidden, emptySet())
        assertEquals(setOf(5), hiddenContinuation.excludedIndices)
        val learned = candidates(fallbackIndex = -1)
        val adaptive = nativeCandidatesAfterConsumedFallbacks(learned, 0, hiddenContinuation.excludedIndices)
            .take(5)
        assertEquals((0 until 5).toList(), adaptive.map { it.nativeIndex })
        assertFalse(adaptive.any { it.isFallback || it.isDeferredFallback })
    }

    @Test
    fun `deferred only candidate remains available after an empty initial row`() {
        val source = arrayOf(CandidateWord("", "分", "", isDeferredFallback = true, nativeIndex = 0))
        val initial = nativeCandidatesAfterConsumedFallbacks(source, 0, emptySet())
        assertTrue(initial.isEmpty())
        val next = nativeCandidateContinuation(0, initial, emptySet())
        assertEquals(0, next.nextOffset)
        val later = nativeCandidatesAfterConsumedFallbacks(source, 0, next.excludedIndices, isInitialRow = false)
        assertEquals(source.toList(), later.toList())
    }

    @Test
    fun `legacy constructors preserve plain candidate defaults`() {
        val candidate = CandidateWord("", "字", "")
        assertFalse(candidate.isFallback)
        assertFalse(candidate.isDeferredFallback)
        assertEquals(-1, candidate.nativeIndex)
        assertTrue(candidate.spaceBetweenComment)
    }

    @Test
    fun `learned correction at native head keeps the head position`() {
        val learned = candidates(fallbackIndex = -1).also {
            it[0] = it[0].copy(text = "分")
        }
        val row = placeFallbackCandidateInRow(learned, 250, 0, 5) { 50 }.take(5)
        assertEquals("分", row.first().text)
        assertEquals((0 until 5).toList(), row.map { it.nativeIndex })
        assertFalse(row.any { it.isFallback })
    }

    @Test
    fun `learned correction below first page is not pulled back onto it`() {
        val learned = candidates(fallbackIndex = -1).also {
            it[6] = it[6].copy(text = "分")
        }
        val row = placeFallbackCandidateInRow(learned, 250, 0, 5) { 50 }.take(5)
        assertEquals((0 until 5).toList(), row.map { it.nativeIndex })
        assertFalse(row.any { it.text == "分" })
        assertEquals("分", learned[6].text)
    }

    @Test
    fun `revoking hint on same code cannot hide a candidate using previous exclusions`() {
        val initial = candidates()
        val initialRow = placeFallbackCandidateInRow(initial, 150, 0, 5) { 50 }
        val previousExclusions = initialRow.filter { it.isFallback }.map { it.nativeIndex }.toSet()
        assertEquals(setOf(4), previousExclusions)
        val learned = initial.map { it.copy(isFallback = false) }.toTypedArray()
        val currentPage = nativeCandidatesAfterConsumedFallbacks(learned, 0, previousExclusions)
        val row = placeFallbackCandidateInRow(currentPage, 250, 0, 5) { 50 }.take(5)
        assertEquals((0 until 5).toList(), row.map { it.nativeIndex })
        assertFalse(row.any { it.isFallback })
        assertEquals(5, nextNativeCandidateOffset(0, row.toTypedArray(), emptySet()))
    }

    @Test
    fun `later pages still exclude a recovery hint already shown on first row`() {
        val page = candidates().drop(2).toTypedArray()
        val remaining = nativeCandidatesAfterConsumedFallbacks(page, 2, setOf(4))
        assertEquals(listOf(2, 3, 5, 6, 7), remaining.map { it.nativeIndex })
    }

    @Test
    fun `reserves the second visible slot with original selection index`() {
        val row = placeFallbackCandidateInRow(candidates(), 150, 0, 5) { 50 }
        assertEquals(listOf(0, 4, 1), row.map { it.nativeIndex })
        assertTrue(row[1].isFallback)
    }

    @Test
    fun `counts dividers before placing fallback`() {
        val row = placeFallbackCandidateInRow(candidates(), 153, 1, 5) { 50 }
        assertEquals(listOf(0, 4, 1), row.map { it.nativeIndex })
        val narrower = placeFallbackCandidateInRow(candidates(), 152, 1, 5) { 50 }
        assertEquals(listOf(0, 4), narrower.map { it.nativeIndex })
    }

    @Test
    fun `measures the correction comment as part of the reserved width`() {
        val candidates = candidates().also { it[4] = it[4].copy(comment = "(fen)") }
        val row = placeFallbackCandidateInRow(candidates, 150, 0, 5) {
            if (it.comment.isNotEmpty()) 70 else 50
        }
        assertEquals(listOf(0, 4), row.map { it.nativeIndex })
    }

    @Test
    fun `respects candidate count limit even when more items fit`() {
        val row = placeFallbackCandidateInRow(candidates(), 1000, 0, 3) { 50 }
        assertEquals(listOf(0, 4, 1), row.map { it.nativeIndex })
    }

    @Test
    fun `keeps all normal prefix items when fallback starts before them`() {
        val row = placeFallbackCandidateInRow(candidates(1), 150, 0, 5) { 50 }
        assertEquals(listOf(0, 1, 2), row.map { it.nativeIndex })
        assertEquals(3, nextNativeCandidateOffset(0, row, setOf(1)))
    }

    @Test
    fun `expanded and next row start at first unseen ordinary candidate`() {
        val row = placeFallbackCandidateInRow(candidates(), 150, 0, 5) { 50 }
        val next = nextNativeCandidateOffset(0, row, setOf(4))
        assertEquals(2, next)
        val remaining = candidates().drop(next).filter { it.nativeIndex != 4 }
        assertEquals((0 until 8).toList(), (row.toList() + remaining).map { it.nativeIndex }.sorted())
        assertEquals(8, (row.toList() + remaining).map { it.nativeIndex }.distinct().size)
    }

    @Test
    fun `next row skips fallback already consumed on first row`() {
        val secondRow = candidates().filter { it.nativeIndex in setOf(2, 3, 5) }.toTypedArray()
        assertEquals(6, nextNativeCandidateOffset(2, secondRow, setOf(4)))
    }

    @Test
    fun `oversized recovery content keeps one bounded slot`() {
        assertTrue(placeFallbackCandidateInRow(candidates(), 0, 0, 5) { 50 }.isEmpty())
        assertEquals(listOf(4), placeFallbackCandidateInRow(candidates(), 1, 0, 5) { 50 }.map { it.nativeIndex })
        assertEquals(listOf(4), placeFallbackCandidateInRow(candidates(), 100, 0, 1) { 50 }.map { it.nativeIndex })
        assertEquals(listOf(0, 4), placeFallbackCandidateInRow(candidates(), 49, 0, 5) { 50 }.map { it.nativeIndex })
        assertEquals(listOf(0, 4), placeFallbackCandidateInRow(candidates(), 50, 0, 5) { 50 }.map { it.nativeIndex })
    }

    @Test
    fun `narrow and large font rows keep both native head and recovery in bounded slots`() {
        val source = candidates().also { it[4] = it[4].copy(text = "非遗", comment = "(fei yi)") }
        for (availableWidth in listOf(52, 100, 201)) {
            for (divider in listOf(0, 1, 3)) {
                val limit = fallbackCandidateRowWidthLimit(source, availableWidth, divider) {
                    if (it.isFallback) 320 else 240
                }
                val row = placeFallbackCandidateInRow(source, availableWidth, divider, 5) {
                    if (it.isFallback) 320 else 240
                }
                assertEquals(listOf(0, 4), row.map { it.nativeIndex })
                assertTrue(limit * 2 + divider * 2 <= availableWidth)
                assertTrue(row[1].isFallback)
                assertEquals("(fei yi)", row[1].comment)
            }
        }
    }

    @Test
    fun `ordinary and adaptive rows have no recovery width cap`() {
        assertEquals(Int.MAX_VALUE, fallbackCandidateRowWidthLimit(candidates(-1), 50, 1) { 200 })
        assertEquals(Int.MAX_VALUE, fallbackCandidateRowWidthLimit(candidates(), 200, 1) { 50 })
    }

    @Test
    fun `engine cursor maps to recovery position without changing navigation origin`() {
        val row = placeFallbackCandidateInRow(candidates(), 150, 0, 5) { 50 }
        val nativeCursor = 4
        val displayedCursor = displayedNativeCandidateIndex(nativeCursor, row, 0)
        assertEquals(1, displayedCursor)
        assertEquals(-4, row[displayedCursor - 1].nativeIndex - nativeCursor)
        assertEquals(4, nativeCursor)
    }

    @Test
    fun `ordinary engine cursor is clamped only for visible rendering`() {
        val row = Array(3) { CandidateWord("", "字", "") }
        assertEquals(2, displayedNativeCandidateIndex(7, row, 0))
        assertEquals(0, displayedNativeCandidateIndex(-1, row, 0))
        assertEquals(1, displayedNativeCandidateIndex(6, row, 5))
    }

    @Test
    fun `ordinary row continuation remains contiguous`() {
        val row = Array(3) { CandidateWord("", "字", "") }
        assertEquals(8, nextNativeCandidateOffset(5, row, emptySet()))
    }
}
