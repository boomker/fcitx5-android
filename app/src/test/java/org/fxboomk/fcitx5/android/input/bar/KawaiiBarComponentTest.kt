/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.bar

import org.fxboomk.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KawaiiBarComponentTest {

    @Test
    fun `blank candidates are not visible candidate content`() {
        assertFalse(
            hasVisibleCandidateContent(
                arrayOf(
                    CandidateWord(label = "1 ", text = "", comment = "", spaceBetweenComment = false),
                    CandidateWord.Empty,
                )
            )
        )
    }

    @Test
    fun `candidate text or comment is visible candidate content`() {
        assertTrue(
            hasVisibleCandidateContent(
                arrayOf(CandidateWord(label = "1 ", text = "候选", comment = "", spaceBetweenComment = false))
            )
        )
        assertTrue(
            hasVisibleCandidateContent(
                arrayOf(CandidateWord(label = "1 ", text = "", comment = "comment", spaceBetweenComment = false))
            )
        )
    }
}
