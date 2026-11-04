/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarVoiceInputPolicyTest {

    @Test
    fun enablingVoiceButtonAppendsExactlyOneEntry() {
        val buttons = listOf(ConfigurableButton("undo"), ConfigurableButton("redo"))

        val enabled = ToolbarVoiceInputPolicy.enableVoiceButton(buttons)

        assertEquals(
            listOf("undo", "redo", "voice_input"),
            enabled.map { it.id }
        )
        assertEquals(enabled, ToolbarVoiceInputPolicy.enableVoiceButton(enabled))
    }

    @Test
    fun disablingVoiceButtonRemovesAllVoiceEntries() {
        val buttons = listOf(
            ConfigurableButton("undo"),
            ConfigurableButton("voice_input"),
            ConfigurableButton("redo"),
            ConfigurableButton("voice_input")
        )

        assertEquals(
            listOf("undo", "redo"),
            ToolbarVoiceInputPolicy.disableVoiceButton(buttons).map { it.id }
        )
    }

    @Test
    fun switchStateIsDerivedFromToolbarButtons() {
        assertTrue(ToolbarVoiceInputPolicy.syncedSwitchState(listOf(ConfigurableButton("voice_input"))))
        assertFalse(ToolbarVoiceInputPolicy.syncedSwitchState(listOf(ConfigurableButton("undo"))))
    }

    @Test
    fun svgThemeIconStillUsesThemeWhenToolbarButtonHasNoIcon() {
        val themed = ConfigurableButton("voice_input", icon = null)

        assertTrue(ToolbarVoiceInputPolicy.usesKeyIconTheme(themed))
    }

    @Test
    fun manualVoiceButtonUsesIconThemeOnlyWithoutExplicitToolbarIcon() {
        val themed = ConfigurableButton("voice_input")
        val custom = ConfigurableButton("voice_input", icon = "font:E141")

        assertTrue(ToolbarVoiceInputPolicy.usesKeyIconTheme(themed))
        assertFalse(ToolbarVoiceInputPolicy.usesKeyIconTheme(custom))
        assertFalse(ToolbarVoiceInputPolicy.usesKeyIconTheme(ConfigurableButton("undo")))
    }
}

class ToolbarVoiceInputFinishPolicyTest {

    @Test
    fun finishButtonUsesActiveVoiceIconAndFinishesInsteadOfCancelling() {
        assertEquals(ToolbarVoiceInputPolicy.LongPressAction.OpenPicker, ToolbarVoiceInputPolicy.longPressAction())
        assertEquals(R.drawable.ic_voice_input_active_24, ToolbarVoiceInputPolicy.finishButtonIcon())
        assertEquals(
            ToolbarVoiceInputPolicy.FinishAction.FinishRecording,
            ToolbarVoiceInputPolicy.finishButtonAction(),
        )
    }
}
