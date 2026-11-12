/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedPopupPresetTest {

    @Test
    fun legacyLetterChoicesStayAheadOfPresetSymbols() {
        val candidates = resolveGroupedPopupPresetCandidates("we", "2")

        assertEquals(listOf("W", "E", "2", "w", "e"), candidates.take(5))
        assertTrue(candidates.contains("3"))
        assertTrue(candidates.contains("ê"))
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun customPresetWinsOverTheBuiltInPresetForItsLetter() {
        val candidates = resolveGroupedPopupPresetCandidates(
            "q", "1", mapOf("q" to arrayOf("θ"))
        )

        assertEquals(listOf("Q", "1", "q", "θ"), candidates.toList())
    }

    @Test
    fun gridNeverDropsTrailingKeys() {
        for (keyCount in 1..30) {
            val rows = popupRowCount(keyCount)
            val columns = popupColumnCount(keyCount, rows)
            assertTrue(
                "grid for $keyCount keys is ${rows}x$columns",
                rows * columns >= keyCount
            )
        }
    }

    @Test
    fun seventeenCandidatesNeedFiveColumns() {
        val candidates = resolveGroupedPopupPresetCandidates("we", "2")
        val rows = popupRowCount(candidates.size)

        assertEquals(17, candidates.size)
        assertEquals(4, rows)
        assertEquals(5, popupColumnCount(candidates.size, rows))
    }
}
