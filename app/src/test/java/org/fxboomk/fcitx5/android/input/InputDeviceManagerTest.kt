/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDeviceManagerTest {

    @Test
    fun physicalKeyboardHorizontalCandidateBarFollowsItsOwnSwitch() {
        assertTrue(
            shouldUsePhysicalKeyboardHorizontalCandidateBar(
                isVirtualKeyboard = false,
                enabled = true,
            )
        )
    }

    @Test
    fun virtualKeyboardDoesNotUsePhysicalKeyboardHorizontalCandidateBar() {
        assertFalse(
            shouldUsePhysicalKeyboardHorizontalCandidateBar(
                isVirtualKeyboard = true,
                enabled = true,
            )
        )
    }

    @Test
    fun disabledPreferenceDoesNotUsePhysicalKeyboardHorizontalCandidateBar() {
        assertFalse(
            shouldUsePhysicalKeyboardHorizontalCandidateBar(
                isVirtualKeyboard = false,
                enabled = false,
            )
        )
    }
}
