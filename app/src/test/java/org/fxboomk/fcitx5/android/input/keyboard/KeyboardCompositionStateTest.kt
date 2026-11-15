/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardCompositionStateTest {

    @Test
    fun rimePredictionCandidatesDoNotActivateComposeOverride() {
        assertFalse(
            shouldComposeForKeyboardOverride(
                preeditEmpty = true,
                hasVisibleCandidates = true,
                inputMethod = rimeInputMethod(),
            )
        )
    }

    @Test
    fun rimePreeditActivatesComposeOverride() {
        assertTrue(
            shouldComposeForKeyboardOverride(
                preeditEmpty = false,
                hasVisibleCandidates = true,
                inputMethod = rimeInputMethod(),
            )
        )
    }

    @Test
    fun nonRimeVisibleCandidatesActivateComposeOverride() {
        assertTrue(
            shouldComposeForKeyboardOverride(
                preeditEmpty = true,
                hasVisibleCandidates = true,
                inputMethod = inputMethod(addon = "keyboard", icon = "keyboard"),
            )
        )
    }

    @Test
    fun emptyPreeditAndNoCandidatesDoNotActivateComposeOverride() {
        assertFalse(
            shouldComposeForKeyboardOverride(
                preeditEmpty = true,
                hasVisibleCandidates = false,
                inputMethod = rimeInputMethod(),
            )
        )
    }

    @Test
    fun compactPredictionCandidatesDoNotActivateComposeOverride() {
        val composing = shouldComposeForKeyboardOverride(
            preeditEmpty = true,
            hasVisibleCandidates = true,
            inputMethod = inputMethod(addon = "pinyin", icon = "pinyin"),
        )

        assertTrue(composing)
        assertFalse(shouldComposeForTextKeyboardOverride(composing, false, ShuangpinKeyboardLayout.Compact18))
        assertTrue(shouldComposeForTextKeyboardOverride(composing, false, ShuangpinKeyboardLayout.Full))
    }

    @Test
    fun compactComposeOverrideFollowsPreeditWhileCandidatesRemainVisible() {
        for (hasPreedit in listOf(false, true, false)) {
            val active = shouldComposeForTextKeyboardOverride(true, hasPreedit, ShuangpinKeyboardLayout.Compact18)
            if (hasPreedit) assertTrue(active) else assertFalse(active)
        }
    }

    @Test
    fun compactPreeditActivatesComposeOverrideEvenWithoutCandidates() {
        val composing = shouldComposeForKeyboardOverride(
            preeditEmpty = false,
            hasVisibleCandidates = false,
            inputMethod = inputMethod(addon = "pinyin", icon = "pinyin"),
        )

        assertTrue(shouldComposeForTextKeyboardOverride(composing, true, ShuangpinKeyboardLayout.Compact18))
    }

    private fun rimeInputMethod() = inputMethod(addon = "rime", icon = "fcitx-rime")

    private fun inputMethod(addon: String, icon: String) = InputMethodEntry(
        uniqueName = addon,
        name = addon,
        icon = icon,
        nativeName = addon,
        label = addon,
        languageCode = "zh",
        addon = addon,
        isConfigurable = false,
    )
}
