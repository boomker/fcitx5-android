/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTogglePolicyTest {

    @Test
    fun secondTapOnActiveSessionStopsInsteadOfRestarting() {
        assertTrue(VoiceInputProviderManager.shouldStopOnTap(isActive = true))
    }

    @Test
    fun firstTapStartsVoiceInput() {
        assertFalse(VoiceInputProviderManager.shouldStopOnTap(isActive = false))
    }
}
