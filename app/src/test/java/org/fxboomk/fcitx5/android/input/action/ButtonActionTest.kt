/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.action

import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonActionTest {

    @Test
    fun numberKeyboardIsAvailableAsAnOptionalKawaiiBarAction() {
        assertSame(NumberKeyboardAction, ButtonAction.fromId("number_keyboard"))
        assertTrue(ButtonAction.kawaiiBarActions.contains(NumberKeyboardAction))
        assertFalse(
            ButtonsLayoutConfig.default().kawaiiBarButtons.any { it.id == NumberKeyboardAction.id }
        )
    }

    @Test
    fun moreButtonIsTheFixedFirstKawaiiBarButton() {
        assertEquals("more", ButtonsLayoutConfig.default().kawaiiBarButtons.first().id)
        assertEquals("font:E141", ButtonsLayoutConfig.default().kawaiiBarButtons.first().icon)
    }

    @Test
    fun keyboardHeightAdjustmentIsAvailableInStatusArea() {
        val config = ButtonsLayoutConfig.default()

        assertSame(AdjustKeyboardHeightAction, ButtonAction.fromId("adjust_keyboard_height"))
        assertTrue(ButtonAction.statusAreaActions.contains(AdjustKeyboardHeightAction))
        assertEquals("adjust_keyboard_height", config.statusAreaButtons.last().id)
    }

    @Test
    fun legacyConfigGainsKeyboardHeightAdjustmentBeforeInputMethodOptions() {
        val legacy = ButtonsLayoutConfig(
            kawaiiBarButtons = ButtonsLayoutConfig.default().kawaiiBarButtons,
            statusAreaButtons = listOf(
                ConfigurableButton("theme"),
                ConfigurableButton("input_method_options")
            ),
            optionalButtons = emptyList()
        )

        val normalized = legacy.withKeyboardHeightAdjustButton()

        assertEquals(
            listOf("theme", "adjust_keyboard_height", "input_method_options"),
            normalized.statusAreaButtons.map { it.id }
        )
        assertSame(normalized, normalized.withKeyboardHeightAdjustButton())
    }
}
