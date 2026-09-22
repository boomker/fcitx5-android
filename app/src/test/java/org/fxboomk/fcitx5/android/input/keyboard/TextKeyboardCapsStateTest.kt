/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.KeyState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardCapsStateTest {

    @Test
    fun virtualUppercaseStateUsesShiftInsteadOfCapsLock() {
        val states = virtualUppercaseKeyStates()

        assertTrue(states.has(KeyState.Virtual))
        assertTrue(states.has(KeyState.Shift))
        assertFalse(states.has(KeyState.CapsLock))
    }
}
