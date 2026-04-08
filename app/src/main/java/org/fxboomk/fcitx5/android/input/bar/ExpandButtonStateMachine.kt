/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fxboomk.fcitx5.android.utils.BuildTransitionEvent
import org.fxboomk.fcitx5.android.utils.EventStateMachine
import org.fxboomk.fcitx5.android.utils.TransitionBuildBlock


object ExpandButtonStateMachine {
    enum class State {
        ClickToAttachWindow,
        ClickToDetachWindow,
        Hidden
    }

    enum class BooleanKey : EventStateMachine.BooleanStateKey {
        ExpandedCandidatesEmpty
    }

    enum class TransitionEvent(val builder: TransitionBuildBlock<State, BooleanKey>) :
        EventStateMachine.TransitionEvent<State, BooleanKey> by BuildTransitionEvent(builder) {
        ExpandedCandidatesUpdated({
            from(Hidden) transitTo ClickToAttachWindow on (ExpandedCandidatesEmpty to false)
            from(ClickToAttachWindow) transitTo Hidden on (ExpandedCandidatesEmpty to true)
        }),
        ExpandedCandidatesAttached({
            from(ClickToAttachWindow) transitTo ClickToDetachWindow
        }),
        ExpandedCandidatesDetached({
            from(ClickToDetachWindow) transitTo Hidden on (ExpandedCandidatesEmpty to true)
            from(ClickToDetachWindow) transitTo ClickToAttachWindow on (ExpandedCandidatesEmpty to false)
        });
    }

    fun new(block: (State) -> Unit) =
        EventStateMachine<State, TransitionEvent, BooleanKey>(
            initialState =  Hidden,
            externalBooleanStates = mutableMapOf(
                ExpandedCandidatesEmpty to true
            )
        ).apply {
            onNewStateListener = block
        }
}

