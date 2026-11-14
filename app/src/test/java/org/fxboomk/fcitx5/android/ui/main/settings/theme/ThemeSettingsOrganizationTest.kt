/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings.theme

import org.fxboomk.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsOrganizationTest {

    @Test
    fun appearanceSettingsAreGroupedByConfiguredObject() {
        assertEquals(
            listOf(
                "key_border",
                "key_border_stroke",
                "gboard_style_side_keys",
                "key_opacity",
                "key_radius",
                "key_horizontal_margin",
                "key_vertical_margin",
                "punctuation_position",
                "uppercase_position"
            ),
            ThemeSettingsSupport.keyStyleThemeKeys
        )
        assertEquals(
            listOf(
                "show_lang_switch_key",
                "keep_keyboard_letters_uppercase",
                "popup_on_key_press",
                "space_key_label_mode"
            ),
            ThemeSettingsSupport.keyStyleKeyboardKeys
        )
        assertEquals(
            listOf(
                "key_border",
                "key_border_stroke",
                "gboard_style_side_keys",
                "show_lang_switch_key",
                "keep_keyboard_letters_uppercase",
                "popup_on_key_press",
                "key_opacity",
                "key_radius",
                "key_horizontal_margin",
                "key_vertical_margin",
                "punctuation_position",
                "uppercase_position",
                "space_key_label_mode"
            ),
            ThemeSettingsSupport.keyStyleKeys
        )
        assertEquals(
            listOf(
                "key_border",
                "key_border_stroke",
                "show_lang_switch_key",
                "keep_keyboard_letters_uppercase",
                "popup_on_key_press",
                "gboard_style_side_keys",
                "key_opacity",
                "key_radius",
                "key_horizontal_margin",
                "key_vertical_margin",
                "punctuation_position",
                "uppercase_position",
                "space_key_label_mode"
            ),
            ThemeSettingsSupport.keyStyleDisplayKeys
        )
        assertEquals(
            listOf(
                "navbar_border",
                "toolbar_height",
                "navbar_radius"
            ),
            ThemeSettingsSupport.toolbarStyleThemeKeys
        )
        assertEquals(
            listOf(
                "toolbar_dynamic_height",
                "toolbar_num_row_on_password",
                "physical_keyboard_horizontal_candidate_bar"
            ),
            ThemeSettingsSupport.toolbarStyleKeyboardKeys
        )
        assertEquals(
            listOf(
                "navbar_border",
                "toolbar_dynamic_height",
                "toolbar_num_row_on_password",
                "physical_keyboard_horizontal_candidate_bar",
                "toolbar_height",
                "navbar_radius"
            ),
            ThemeSettingsSupport.toolbarStyleKeys
        )
        assertEquals(
            listOf(
                "clipboard_entry_radius",
                "text_editing_button_radius",
                "cursor_movement_button_radius",
                "text_selection_button_radius"
            ),
            ThemeSettingsSupport.otherStyleThemeKeys
        )
        assertEquals(
            listOf(
                "clipboard_entry_radius",
                "text_editing_button_radius",
                "cursor_movement_button_radius",
                "text_selection_button_radius"
            ),
            ThemeSettingsSupport.otherStyleKeys
        )
        assertEquals(
            listOf(
                "keyboard_height_percent",
                "keyboard_side_padding",
                "keyboard_bottom_padding",
                "keyboard_top_corner_radius",
                "keyboard_glass_enabled",
                "keyboard_glass_blur_radius",
                "keyboard_glass_background_opacity",
                "follow_system_dark_mode",
                "light_mode_themes",
                "dark_mode_themes"
            ),
            ThemeSettingsSupport.themeModeKeys
        )
        assertEquals(
            listOf("key_ripple_effect", "disable_animation"),
            ThemeSettingsSupport.animationKeys
        )
    }

    @Test
    fun candidateAppearanceKeepsItsDisplayModeOrder() {
        assertEquals(
            listOf(
                "horizontal_candidate_style",
                "expanded_candidate_style",
                "candidate_highlight_radius",
                "expanded_candidate_grid_span_count_portrait"
            ),
            ThemeSettingsSupport.candidateHorizontalKeys
        )
        assertEquals(
            listOf(
                "virtual_keyboard_candidates_position",
                "candidates_window_padding",
                "candidates_window_radius",
                "candidates_window_min_width"
            ),
            ThemeSettingsSupport.candidateWindowKeys
        )
        assertEquals(
            listOf(
                "candidates_window_orientation",
                "candidates_item_padding_vertical"
            ),
            ThemeSettingsSupport.candidateItemKeys
        )
        assertEquals(
            ThemeSettingsSupport.candidateHorizontalKeys +
                ThemeSettingsSupport.candidateWindowKeys +
                ThemeSettingsSupport.candidateItemKeys,
            ThemeSettingsSupport.candidateStyleKeys
        )
    }

    @Test
    fun candidateAndAnimationSettingsMoveOutOfAdvanced() {
        assertEquals(
            listOf("disable_animation"),
            ThemeSettingsSupport.advancedMovedKeys
        )
    }

    @Test
    fun everyThemePreferenceAppearsInExactlyOneGroup() {
        val themeKeys = ThemeSettingsSupport.keyStyleThemeKeys +
            ThemeSettingsSupport.toolbarStyleThemeKeys +
            ThemeSettingsSupport.themeModeThemeKeys +
            ThemeSettingsSupport.animationThemeKeys +
            ThemeSettingsSupport.otherStyleThemeKeys
        assertEquals(
            setOf(
                "key_border",
                "key_border_stroke",
                "key_ripple_effect",
                "gboard_style_side_keys",
                "key_radius",
                "keyboard_top_corner_radius",
                "keyboard_glass_enabled",
                "keyboard_glass_blur_radius",
                "keyboard_glass_background_opacity",
                "key_opacity",
                "key_horizontal_margin",
                "key_vertical_margin",
                "punctuation_position",
                "uppercase_position",
                "navbar_border",
                "toolbar_height",
                "navbar_radius",
                "text_editing_button_radius",
                "clipboard_entry_radius",
                "cursor_movement_button_radius",
                "text_selection_button_radius",
                "follow_system_dark_mode",
                "light_mode_themes",
                "dark_mode_themes"
            ),
            themeKeys.toSet()
        )
        assertEquals(themeKeys.size, themeKeys.toSet().size)
    }

    @Test
    fun appearanceGroupsDoNotDuplicatePreferences() {
        val groups = listOf(
            ThemeSettingsSupport.keyStyleKeys,
            ThemeSettingsSupport.toolbarStyleKeys,
            ThemeSettingsSupport.candidateStyleKeys,
            ThemeSettingsSupport.themeModeKeys,
            ThemeSettingsSupport.otherStyleKeys
        )
        val appearanceKeys = groups.flatten()
        assertEquals(appearanceKeys.size, appearanceKeys.toSet().size)
        assertTrue(ThemeSettingsSupport.animationKeys.none { it in appearanceKeys })
        // Emoji 相关偏好已迁到「便捷功能 → 表情和符号」，不再出现在外观分组里
        assertTrue("hide_unsupported_emojis" !in appearanceKeys)
        assertTrue("default_emoji_skin_tone" !in appearanceKeys)
        assertFalse("show_lang_switch_key" in KeyboardSettingsSupport.basicBehaviorKeys)
    }
}
