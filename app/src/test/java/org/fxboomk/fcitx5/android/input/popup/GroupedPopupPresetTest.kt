/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedPopupPresetTest {

    @Test
    fun legacyLetterChoicesStayAheadOfPresetSymbols() {
        val candidates = resolveGroupedPopupPresetCandidates("we", "2")

        assertEquals(listOf("W", "E", "2", "w", "e"), candidates.take(5))
        assertTrue(candidates.contains("ê"))
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun regroupedLettersWithoutPunctuationDoNotOfferAnEmptyKey() {
        val candidates = resolveGroupedPopupPresetCandidates("wq", "")

        assertEquals(listOf("W", "Q", "w", "q"), candidates.take(4))
        assertFalse(candidates.contains(""))
        assertTrue(candidates.all { it.all(Char::isLetter) })
    }

    @Test
    fun emptyCustomPresetEntriesAreIgnoredWithoutACorner() {
        val candidates = resolveGroupedPopupPresetCandidates(
            "wq", "", mapOf("w" to arrayOf("", "ẃ", "2", "!")), emptyMap(),
        )

        assertEquals(listOf("W", "Q", "w", "q", "ẃ"), candidates.toList())
    }

    @Test
    fun mergedAlphabetKeepsEveryLetterAndFitsItsPopupGrid() {
        val group = "abcdefghijklmnopqrstuvwxyz;"
        val candidates = resolveGroupedPopupPresetCandidates(group, "", builtInPresets = emptyMap())

        val uppercase = ('A'..'Z').map(Char::toString) + ":"
        assertEquals(uppercase + group.map(Char::toString), candidates.toList())
        assertFalse(candidates.contains(""))
        val rows = popupRowCount(candidates.size)
        val columns = popupColumnCount(candidates.size, rows)
        assertTrue(rows * columns >= candidates.size)
    }

    @Test
    fun numericCandidatesFollowTheCornerDigit() {
        val candidates = resolveGroupedPopupPresetCandidates("we", "2")

        assertFalse(candidates.contains("3"))
        assertEquals(listOf("2"), candidates.filter { candidate -> candidate.all(Char::isDigit) })
    }

    @Test
    fun customPresetWinsOverTheBuiltInPresetForItsLetter() {
        val candidates = resolveGroupedPopupPresetCandidates(
            "q", "1", mapOf("q" to arrayOf("θ"))
        )

        assertEquals(listOf("Q", "1", "q", "θ"), candidates.toList())
    }

    @Test
    fun openingBracketsOnlyStayOnTheOpeningCorner() {
        val candidates = resolveGroupedPopupPresetCandidates(
            "h", "(", mapOf("k" to arrayOf("（", "【", "K"))
        )

        assertTrue(candidates.contains("（"))
        assertTrue(candidates.contains("【"))
        assertFalse(candidates.contains("）"))
        assertFalse(candidates.contains("】"))
    }

    @Test
    fun closingBracketsOnlyStayOnTheClosingCorner() {
        val candidates = resolveGroupedPopupPresetCandidates("jk", ")")

        assertTrue(candidates.contains("]"))
        assertTrue(candidates.contains("}"))
        assertFalse(candidates.contains("["))
        assertFalse(candidates.contains("{"))
    }

    @Test
    fun customClosingBracketsLandOnTheClosingCorner() {
        val candidates = resolveGroupedPopupPresetCandidates(
            "jk", ")", mapOf("l" to arrayOf("）", "】", "L"))
        )

        assertTrue(candidates.contains("）"))
        assertTrue(candidates.contains("】"))
        assertFalse(candidates.contains("（"))
        assertFalse(candidates.contains("【"))
    }

    @Test
    fun semicolonGroupDoesNotInheritBracketsFromTheLetter() {
        val candidates = resolveGroupedPopupPresetCandidates("l;", "-")

        assertFalse(candidates.contains(")"))
        assertFalse(candidates.contains("]"))
        assertFalse(candidates.contains("}"))
        assertEquals(listOf("L", ":", "-", "l", ";", "ł"), candidates.toList())
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
    fun columnCountRoundsUp() {
        assertEquals(5, popupColumnCount(17, 4))
        assertEquals(5, popupColumnCount(21, 5))
    }
}
