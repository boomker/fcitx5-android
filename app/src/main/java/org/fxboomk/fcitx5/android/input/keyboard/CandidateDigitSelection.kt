/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.KeyState

/** Soft keys, swipe alternatives and popup keys represent the same digit in different ways. */
internal fun candidateSelectionIndex(action: KeyAction): Int? {
    val digit = when (action) {
        is KeyAction.FcitxKeyAction -> {
            if (action.up || action.states.states and KeyState.SimpleMask.state != 0u) return null
            action.act.removePrefix("KP_").singleOrNull()
        }
        is KeyAction.SymAction -> {
            if (action.states.states and KeyState.SimpleMask.state != 0u) return null
            when (val sym = action.sym.sym) {
                in FcitxKeyMapping.FcitxKey_0..FcitxKeyMapping.FcitxKey_9 -> sym.toChar()
                in FcitxKeyMapping.FcitxKey_KP_0..FcitxKeyMapping.FcitxKey_KP_9 ->
                    '0' + (sym - FcitxKeyMapping.FcitxKey_KP_0)
                else -> null
            }
        }
        is KeyAction.CommitAction -> action.text.removePrefix("\u0000").singleOrNull()
        else -> null
    } ?: return null
    return when (digit) {
        '0' -> 9
        in '1'..'9' -> digit - '1'
        else -> null
    }
}

internal suspend fun FcitxAPI.selectCandidateForDigit(action: KeyAction): Boolean {
    val index = candidateSelectionIndex(action) ?: return false
    // Inspect the engine on the serialized key path, rather than a possibly older UI snapshot.
    if (getCandidates(0, 1).isEmpty()) return false
    // The frontend resolves the displayed candidate order and the current paging mode.
    // Folded symbols are actions, so they never occupy a numbered candidate slot.
    select(index)
    // An out-of-range selection key must not commit the first candidate plus a literal digit.
    return true
}
