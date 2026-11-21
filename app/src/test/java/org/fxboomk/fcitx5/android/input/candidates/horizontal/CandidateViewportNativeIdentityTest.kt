/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.fxboomk.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateViewportNativeIdentityTest {
    @Test
    fun deferredHintFilteringPreservesSparseFirstPageIdentities() {
        val page = arrayOf(
            CandidateWord("", "字", "", nativeIndex = 0),
            CandidateWord("", "延后", "", isDeferredFallback = true, nativeIndex = 3),
            CandidateWord("", "叉", "", nativeIndex = 8),
            CandidateWord("", "❌", "…", nativeIndex = 9, symbolSourceId = 8, symbolCount = 2),
        )
        val row = nativeCandidatesAfterConsumedFallbacks(page, 0, emptySet(), true)
        val continuation = nativeCandidateContinuation(0, row, emptySet())

        assertEquals(listOf(0, 8, 9), row.map { it.nativeIndex })
        assertEquals(1, continuation.nextOffset)
        assertEquals(setOf(8, 9), continuation.excludedIndices)
    }

    @Test
    fun laterMappedRowExcludesAnAlreadyShownHighFoldedHead() {
        val later = arrayOf(
            CandidateWord("", "字", "", nativeIndex = 2),
            CandidateWord("", "❌", "…", nativeIndex = 9, symbolSourceId = 8, symbolCount = 2),
            CandidateWord("", "茶", "", nativeIndex = 10),
        )
        val row = nativeCandidatesAfterConsumedFallbacks(later, 2, setOf(9), false)

        assertEquals(listOf(2, 10), row.map { it.nativeIndex })
        assertTrue(row.none { it.comment == "…" })
    }
}
