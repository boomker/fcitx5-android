/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.KeyState
import org.junit.Assert.assertEquals
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

    @Test
    fun defaultCapsKeyBehaviorKeepsSingleTapAsOneShot() {
        assertFalse(shouldLockCapsAction(false, CapsKeyBehavior.Default))
        assertTrue(shouldLockCapsAction(true, CapsKeyBehavior.Default))
    }

    @Test
    fun singleTapLockCapsKeyBehaviorLocksOnSingleTap() {
        assertTrue(shouldLockCapsAction(false, CapsKeyBehavior.SingleTapLock))
        assertTrue(shouldLockCapsAction(true, CapsKeyBehavior.SingleTapLock))
    }

    @Test
    fun macroCapsLockSurvivesHardwareTypingStateRefresh() {
        assertFalse(shouldResetCapsStateOnHardwareTyping(true))
        assertTrue(shouldResetCapsStateOnHardwareTyping(false))
    }

    @Test
    fun uppercaseMacroDisplayTextStaysUppercaseAcrossCapsAndAsciiStates() {
        val macro = MacroKey(
            label = "Q",
            tap = MacroAction(listOf(MacroStep.Text("q")))
        )
        val appearance = macro.appearance as KeyDef.Appearance.AltText

        assertFalse(appearance.followsCapsState)
        assertTrue(appearance.caseAwareCapsState)
        for (capsOn in listOf(false, true)) {
            for (asciiMode in listOf(false, true)) {
                assertEquals(
                    "Q",
                    resolveCapsAwareAltText(
                        appearance = appearance,
                        renderedText = "q",
                        displayUppercase = capsOn,
                        keepLettersUppercase = asciiMode,
                    )
                )
            }
        }
    }

    @Test
    fun lowercaseMacroDisplayTextFollowsCapsButIgnoresAsciiUppercasePreference() {
        val appearance = (MacroKey(
            label = "q",
            character = "Q",
            tap = MacroAction(listOf(MacroStep.Text("q")))
        ).appearance as KeyDef.Appearance.AltText)

        for (asciiMode in listOf(false, true)) {
            assertEquals("q", resolveCapsAwareAltText(appearance, "q", false, asciiMode))
            assertEquals("Q", resolveCapsAwareAltText(appearance, "q", true, asciiMode))
            assertEquals("q", resolveCapsAwareAltText(appearance, "Q", false, asciiMode))
        }
    }

    @Test
    fun previewCanKeepMacroDisplayTextInConfiguredCase() {
        val appearance = (MacroKey(
            label = "q",
            tap = MacroAction(listOf(MacroStep.Text("q")))
        ).appearance as KeyDef.Appearance.AltText)

        assertEquals(
            "q",
            resolveCapsAwareAltText(
                appearance = appearance,
                renderedText = "q",
                displayUppercase = true,
                keepLettersUppercase = true,
                preserveConfiguredDisplayCase = true,
            )
        )
    }

    @Test
    fun alphabetLabelsStillFollowCapsState() {
        val appearance = KeyDef.Appearance.AltText(
            displayText = "q",
            altText = "",
            character = "q",
            textSize = 23f,
        )

        assertTrue(appearance.followsCapsState)
        assertEquals("q", resolveCapsAwareAltText(appearance, "q", false, false))
        assertEquals("Q", resolveCapsAwareAltText(appearance, "q", true, false))
    }

}
