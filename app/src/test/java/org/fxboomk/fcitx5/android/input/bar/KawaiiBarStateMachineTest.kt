/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.bar

import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Candidate
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Idle
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.State.Title
import org.junit.Assert.assertEquals
import org.junit.Test

class KawaiiBarStateMachineTest {

    @Test
    fun `deferred only empty first row keeps its expansion parent visible`() {
        val empty = candidateSurfaceIsEmpty(candidateEmpty = true, hasExpandableDeferredCandidates = true)
        assertEquals(Candidate, nextKawaiiBarStateOnCandidatesUpdated(Idle, empty))
        assertEquals(Candidate, nextKawaiiBarStateOnCandidatesUpdated(Candidate, empty))
        assertEquals(Title, nextKawaiiBarStateOnCandidatesUpdated(Title, empty))
    }

    @Test
    fun `AI availability refresh preserves deferred surface until native clear`() {
        var state = nextKawaiiBarStateOnCandidatesUpdated(
            Idle, candidateSurfaceIsEmpty(candidateEmpty = true, hasExpandableDeferredCandidates = true),
        )
        state = nextKawaiiBarStateOnCandidatesUpdated(
            state, candidateSurfaceIsEmpty(candidateEmpty = true, hasExpandableDeferredCandidates = true),
        )
        assertEquals(Candidate, state)
        state = nextKawaiiBarStateOnCandidatesUpdated(
            state, candidateSurfaceIsEmpty(candidateEmpty = true, hasExpandableDeferredCandidates = false),
        )
        assertEquals(Idle, state)
    }

    @Test
    fun `ordinary empty and visible candidate rows keep their existing states`() {
        assertEquals(Idle, nextKawaiiBarStateOnCandidatesUpdated(
            Candidate, candidateSurfaceIsEmpty(candidateEmpty = true, hasExpandableDeferredCandidates = false),
        ))
        assertEquals(Candidate, nextKawaiiBarStateOnCandidatesUpdated(
            Idle, candidateSurfaceIsEmpty(candidateEmpty = false, hasExpandableDeferredCandidates = false),
        ))
    }

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
