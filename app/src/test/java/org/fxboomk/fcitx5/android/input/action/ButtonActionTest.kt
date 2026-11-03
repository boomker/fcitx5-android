/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.action

import org.fxboomk.fcitx5.android.R
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
        assertEquals(R.string.number_keyboard, NumberKeyboardAction.defaultLabelRes)
        assertTrue(ButtonAction.kawaiiBarActions.contains(NumberKeyboardAction))
        assertFalse(
            ButtonsLayoutConfig.default().kawaiiBarButtons.any { it.id == NumberKeyboardAction.id }
        )
    }

    @Test
    fun symbolAndVoiceButtonsAreAvailableAsOptionalActions() {
        assertSame(SymbolKeyboardAction, ButtonAction.fromId("symbol_keyboard"))
        assertSame(VoiceInputAction, ButtonAction.fromId("voice_input"))
        assertEquals(R.string.symbol_keyboard, SymbolKeyboardAction.defaultLabelRes)
        assertEquals(R.string.voice_input, VoiceInputAction.defaultLabelRes)
        assertTrue(ButtonAction.kawaiiBarActions.contains(SymbolKeyboardAction))
        assertTrue(ButtonAction.kawaiiBarActions.contains(VoiceInputAction))
        assertTrue(ButtonAction.allConfigurableActions.contains(SymbolKeyboardAction))
        assertTrue(ButtonAction.allConfigurableActions.contains(VoiceInputAction))
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
    fun keyboardLayoutListAndManagementAreSeparateMacroActions() {
        assertSame(TextKeyboardLayoutFileSelectAction, ButtonAction.fromId("text_keyboard_layout_file_select"))
        assertEquals(
            R.string.text_keyboard_layout_file_select_title,
            TextKeyboardLayoutFileSelectAction.defaultLabelRes
        )

        assertSame(TextKeyboardLayoutManageAction, ButtonAction.fromId("text_keyboard_layout_manage"))
        assertEquals(
            R.string.text_keyboard_layout_manage_title,
            TextKeyboardLayoutManageAction.defaultLabelRes
        )
        assertTrue(ButtonAction.macroEditorActions.contains(TextKeyboardLayoutFileSelectAction))
        assertTrue(ButtonAction.macroEditorActions.contains(TextKeyboardLayoutManageAction))
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
