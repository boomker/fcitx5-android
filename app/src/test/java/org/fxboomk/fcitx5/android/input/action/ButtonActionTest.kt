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
    fun aiAssistantIsRegisteredAsAConfigurableAction() {
        assertSame(AiAssistantAction, ButtonAction.fromId("ai_assistant"))
        assertEquals(R.string.ai_assistant, AiAssistantAction.defaultLabelRes)
        assertTrue(ButtonAction.kawaiiBarActions.contains(AiAssistantAction))
        assertTrue(ButtonAction.allConfigurableActions.contains(AiAssistantAction))
        assertFalse(AiAssistantAction.defaultIcon == AiCandidatesAction.defaultIcon)
    }

    @Test
    fun aiAssistantIsHiddenByDefault() {
        val config = ButtonsLayoutConfig.default()

        assertEquals(1, config.optionalButtons.count { it.id == AiAssistantAction.id })
        assertFalse(config.kawaiiBarButtons.any { it.id == AiAssistantAction.id })
        assertFalse(config.statusAreaButtons.any { it.id == AiAssistantAction.id })
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
        assertEquals(R.drawable.ic_keyboard_height_adjust_24, AdjustKeyboardHeightAction.defaultIcon)
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

    @Test
    fun toolbarCustomizationIsRegisteredAsAConfigurableStatusAreaAction() {
        assertSame(ToolbarCustomizeAction, ButtonAction.fromId("toolbar_customize"))
        assertEquals("toolbar_customize", ToolbarCustomizeAction.id)
        assertEquals(R.string.toolbar_customize, ToolbarCustomizeAction.defaultLabelRes)
        assertEquals(R.drawable.ic_baseline_tune_24, ToolbarCustomizeAction.defaultIcon)
        assertTrue(ButtonAction.allActions.contains(ToolbarCustomizeAction))
        assertTrue(ButtonAction.statusAreaActions.contains(ToolbarCustomizeAction))
        assertTrue(ButtonAction.allConfigurableActions.contains(ToolbarCustomizeAction))
    }

    @Test
    fun toolbarCustomizationDefaultsToTheStatusAreaAfterTheme() {
        val config = ButtonsLayoutConfig.default()
        val themePosition = config.statusAreaButtons.indexOfFirst { it.id == "theme" }

        assertTrue(themePosition >= 0)
        assertEquals("toolbar_customize", config.statusAreaButtons[themePosition + 1].id)
        assertEquals(1, config.statusAreaButtons.count { it.id == "toolbar_customize" })
        assertFalse(config.kawaiiBarButtons.any { it.id == "toolbar_customize" })
        assertFalse(config.optionalButtons.any { it.id == "toolbar_customize" })
        assertSame(config, config.withToolbarCustomizeButton())
    }

    @Test
    fun legacyConfigGainsToolbarCustomizationAfterThemeAndPreservesOtherButtons() {
        val top = listOf(ConfigurableButton("undo", icon = "font:E141", label = "Undo custom"))
        val optional = listOf(ConfigurableButton("search", label = "Search custom"))
        val theme = ConfigurableButton("theme", icon = "font:E142", label = "Theme custom")
        val hide = ConfigurableButton("hide_keyboard", icon = "font:E143", label = "Hide custom")
        val legacy = ButtonsLayoutConfig(
            kawaiiBarButtons = top,
            statusAreaButtons = listOf(ConfigurableButton("clipboard"), theme, ConfigurableButton("reload_config")),
            optionalButtons = optional,
            hideKeyboardButton = hide
        )

        val normalized = legacy.withToolbarCustomizeButton()

        assertEquals(
            listOf("clipboard", "theme", "toolbar_customize", "reload_config"),
            normalized.statusAreaButtons.map { it.id }
        )
        assertSame(top, normalized.kawaiiBarButtons)
        assertSame(optional, normalized.optionalButtons)
        assertSame(hide, normalized.hideKeyboardButton)
        assertSame(theme, normalized.statusAreaButtons[1])
        assertEquals(legacy.statusAreaButtons, normalized.statusAreaButtons.filter { it.id != "toolbar_customize" })
        assertSame(normalized, normalized.withToolbarCustomizeButton())
    }

    @Test
    fun legacyConfigWithoutThemeGainsToolbarCustomizationBeforeOptionsOrAtTheEnd() {
        val cases = listOf(
            listOf("reload_config", "input_method_options", "virtual_keyboard") to
                listOf("reload_config", "toolbar_customize", "input_method_options", "virtual_keyboard"),
            listOf("reload_config") to listOf("reload_config", "toolbar_customize"),
            emptyList<String>() to listOf("toolbar_customize")
        )
        cases.forEach { (originalIds, expectedIds) ->
            val legacy = ButtonsLayoutConfig(
                kawaiiBarButtons = emptyList(),
                statusAreaButtons = originalIds.map { ConfigurableButton(it) }
            )

            val normalized = legacy.withToolbarCustomizeButton()

            assertEquals(expectedIds, normalized.statusAreaButtons.map { it.id })
            assertSame(normalized, normalized.withToolbarCustomizeButton())
        }
    }

    @Test
    fun toolbarCustomizationAlreadyInAnySectionKeepsItsPositionAndCustomization() {
        val customized = ConfigurableButton(
            "toolbar_customize",
            icon = "font:E141",
            activeIcon = "font:E142",
            label = "My toolbar",
            longPressAction = "custom_long_press"
        )
        val configured = listOf(ConfigurableButton("undo"), customized, ConfigurableButton("search"))
        val sections = listOf(
            ButtonsLayoutConfig(configured, listOf(ConfigurableButton("theme"))),
            ButtonsLayoutConfig(emptyList(), listOf(ConfigurableButton("theme")) + configured),
            ButtonsLayoutConfig(emptyList(), listOf(ConfigurableButton("theme")), configured)
        )

        sections.forEach { config ->
            val normalized = config.withToolbarCustomizeButton()

            assertSame(config, normalized)
            assertEquals(config.kawaiiBarButtons, normalized.kawaiiBarButtons)
            assertEquals(config.statusAreaButtons, normalized.statusAreaButtons)
            assertEquals(config.optionalButtons, normalized.optionalButtons)
            assertSame(
                customized,
                (normalized.kawaiiBarButtons + normalized.statusAreaButtons + normalized.optionalButtons)
                    .single { it.id == "toolbar_customize" }
            )
        }
    }
}
