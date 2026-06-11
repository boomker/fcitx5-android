/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.bar

import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Candidate
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Idle
import org.junit.Assert.assertEquals
import org.junit.Test

class KawaiiBarStateMachineTest {

    @Test
    fun `candidate bar returns idle when candidates become empty with preedit still marked non-empty`() {
        var state = nextKawaiiBarStateOnCandidatesUpdated(
            currentState = Idle,
            candidateEmpty = false,
        )
        assertEquals(Candidate, state)

        state = nextKawaiiBarStateOnCandidatesUpdated(
            currentState = state,
            candidateEmpty = true,
        )

        assertEquals(Idle, state)
    }
}
