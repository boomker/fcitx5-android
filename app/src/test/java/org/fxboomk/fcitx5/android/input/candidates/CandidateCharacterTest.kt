/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateCharacterTest {

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
