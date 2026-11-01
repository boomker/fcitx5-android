/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.fxboomk.fcitx5.android.input.popup.PopupPreset
import org.fxboomk.fcitx5.android.input.popup.resolvePopupPresetCandidates

class MacroKeyPopupTest {

    @Test
    fun longPressPopupUsesCharacterInsteadOfSubmodeDisplayLabel() {
        val key = MacroKey(
            label = "\u200bL",
            character = "L",
            tap = MacroAction(emptyList()),
            longPress = MacroAction(listOf(MacroStep.AppAction("text_keyboard_layout_file_select"))),
            longPressLabel = "布",
        )

        val popup = key.popup
            ?.filterIsInstance<KeyDef.Popup.LongPressKeyboard>()
            ?.single()

        requireNotNull(popup)
        assertEquals("布", popup.displayLabel)
        assertEquals("L", popup.baseLabel)

        val preset = key.popup
            .filterIsInstance<KeyDef.Popup.Keyboard.Preset>()
            .single()
        requireNotNull(preset)
        assertEquals("L", preset.label)
        assertEquals(null, preset.fallbackLabel)
    }

    @Test
    fun longPressPopupUsesCleanDisplayLabelAndLogicalFallback() {
        val key = MacroKey(
            label = "\u200BL",
            character = "L",
            tap = MacroAction(listOf(MacroStep.Text("L"))),
            longPress = MacroAction(emptyList()),
            longPressLabel = "布",
        )

        val popup = key.popup
            ?.filterIsInstance<KeyDef.Popup.LongPressKeyboard>()
            ?.single()
        requireNotNull(popup)

        assertEquals("L", popup.baseLabel)
        assertEquals(null, popup.fallbackBaseLabel)
        assertArrayEquals(
            arrayOf("布", *PopupPreset.getValue("L")),
            arrayOf("布", *resolvePopupPresetCandidates(
                popup.baseLabel,
                popup.fallbackBaseLabel
            )!!)
        )
    }

    @Test
    fun lowerDisplayLabelUsesLowercasePresetAndFallsBackToCharacter() {
        val key = MacroKey(
            label = "l",
            character = "L",
            tap = MacroAction(listOf(MacroStep.Text("L"))),
            longPress = MacroAction(emptyList()),
            longPressLabel = "布",
        )

        val popup = key.popup
            ?.filterIsInstance<KeyDef.Popup.LongPressKeyboard>()
            ?.single()
        requireNotNull(popup)
        assertEquals("l", popup.baseLabel)
        assertEquals("L", popup.fallbackBaseLabel)
        assertArrayEquals(
            PopupPreset.getValue("l"),
            resolvePopupPresetCandidates(popup.baseLabel, popup.fallbackBaseLabel)
        )
    }

    @Test
    fun longPressPopupKeepsAllVisiblePresetCandidatesBeforeLongPressLabel() {
        val key = MacroKey(
            label = "l",
            character = "L",
            tap = MacroAction(listOf(MacroStep.Text("L"))),
            longPress = MacroAction(emptyList()),
            longPressLabel = "布",
        )

        val popup = key.popup
            ?.filterIsInstance<KeyDef.Popup.LongPressKeyboard>()
            ?.single()
        requireNotNull(popup)

        assertArrayEquals(
            arrayOf("布", *PopupPreset.getValue("l")),
            arrayOf("布", *resolvePopupPresetCandidates(
                popup.baseLabel,
                popup.fallbackBaseLabel
            )!!)
        )
    }

    @Test
    fun popupLookupUsesOppositeCaseWhenCustomPresetOnlyDefinesLogicalCase() {
        val candidates = arrayOf(")", "l", "]", "}", "Ł")

        assertArrayEquals(
            candidates,
            resolvePopupPresetCandidates(
                label = "l",
                fallbackLabel = null,
                customPresets = mapOf("L" to candidates),
                builtInPresets = emptyMap(),
            )
        )
    }
}
