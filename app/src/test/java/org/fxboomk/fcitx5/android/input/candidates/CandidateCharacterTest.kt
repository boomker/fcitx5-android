/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateCharacterTest {

    @Test
    fun `splits complete candidate into grapheme characters`() {
        assertEquals(
            listOf("\u8F93", "\u5165", "e\u0301", "\uD840\uDC00"),
            "\u8F93\u5165e\u0301\uD840\uDC00".candidateCharacters()
        )
        assertEquals(emptyList<String>(), "".candidateCharacters())
    }

    @Test
    fun `chooses popup grid dimensions from character count`() {
        assertEquals(CandidateCharacterGrid(1, 1), candidateCharacterGrid(1))
        assertEquals(CandidateCharacterGrid(1, 2), candidateCharacterGrid(2))
        assertEquals(CandidateCharacterGrid(1, 3), candidateCharacterGrid(3))
        assertEquals(CandidateCharacterGrid(2, 2), candidateCharacterGrid(4))
        assertEquals(CandidateCharacterGrid(3, 3), candidateCharacterGrid(5))
        assertEquals(CandidateCharacterGrid(2, 3), candidateCharacterGrid(6))
        assertEquals(CandidateCharacterGrid(3, 3), candidateCharacterGrid(9))
        assertEquals(CandidateCharacterGrid(4, 4), candidateCharacterGrid(10))
        assertEquals(CandidateCharacterGrid(4, 4), candidateCharacterGrid(16))
        assertEquals(CandidateCharacterGrid(5, 5), candidateCharacterGrid(17))
    }

    @Test
    fun `cancels character selection outside popup bounds`() {
        assertTrue(isInsideCandidateCharacterPopup(0f, 0f, width = 100, height = 50))
        assertTrue(isInsideCandidateCharacterPopup(99.9f, 49.9f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(-0.1f, 25f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(50f, -0.1f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(100f, 25f, width = 100, height = 50))
        assertFalse(isInsideCandidateCharacterPopup(50f, 50f, width = 100, height = 50))
    }

    @Test
    fun `recognizes candidate frequency reset actions`() {
        assertTrue("Forget candidate".isCandidateFrequencyResetActionText())
        assertTrue("Forget word".isCandidateFrequencyResetActionText())
        assertTrue("Reset candidate frequency".isCandidateFrequencyResetActionText())
        assertTrue("\u5FD8\u8BB0\u5019\u9009\u8BCD".isCandidateFrequencyResetActionText())
        assertTrue("\u5FD8\u8BB0\u8BCD\u6C47".isCandidateFrequencyResetActionText())
        assertTrue("\u91CD\u7F6E\u8BCD\u9891".isCandidateFrequencyResetActionText())
        assertFalse("Forget custom phrase".isCandidateFrequencyResetActionText())
        assertFalse("Pin to top as custom phrase".isCandidateFrequencyResetActionText())
        assertFalse("Delete from custom phrase".isCandidateFrequencyResetActionText())
    }

    @Test
    fun `selects first and last character`() {
        val candidate = "\u8F93\u5165\u6CD5"

        assertEquals(
            CandidateEdgeCharacters("\u8F93", "\u6CD5"),
            candidate.candidateEdgeCharacters()
        )
    }

    @Test
    fun `keeps supplementary code points intact`() {
        val candidate = "\uD840\uDC00\u8F93\uD840\uDC01"

        assertEquals(
            CandidateEdgeCharacters("\uD840\uDC00", "\uD840\uDC01"),
            candidate.candidateEdgeCharacters()
        )
    }

    @Test
    fun `keeps combining marks with their character`() {
        val candidate = "e\u0301lan"

        assertEquals(
            CandidateEdgeCharacters("e\u0301", "n"),
            candidate.candidateEdgeCharacters()
        )
    }

    @Test
    fun `rejects empty and single-character candidates`() {
        assertNull("".candidateEdgeCharacters())
        assertNull("\u5B57".candidateEdgeCharacters())
        assertNull("\uD840\uDC00".candidateEdgeCharacters())
        assertNull("e\u0301".candidateEdgeCharacters())
    }

    @Test
    fun `rejects punctuation and symbol candidates`() {
        assertNull("?!".candidateEdgeCharacters())
        assertNull("\u2026\u2026".candidateEdgeCharacters())
        assertNull("+-".candidateEdgeCharacters())
    }

    @Test
    fun `rejects emoji candidates`() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"

        assertNull(family.candidateEdgeCharacters())
        assertNull("\uD83C\uDDE8\uD83C\uDDF3".candidateEdgeCharacters())
        assertNull("1\uFE0F\u20E3".candidateEdgeCharacters())
        assertNull("\uD83C\uDFF7\uFE0F".candidateEdgeCharacters())
    }

    @Test
    fun `accepts ordinary words named after excluded categories`() {
        assertEquals(
            CandidateEdgeCharacters("\u6807", "\u7B7E"),
            "\u6807\u7B7E".candidateEdgeCharacters()
        )
        assertEquals(
            CandidateEdgeCharacters("A", "\u00A9"),
            "A\u00A9".candidateEdgeCharacters()
        )
    }
}
