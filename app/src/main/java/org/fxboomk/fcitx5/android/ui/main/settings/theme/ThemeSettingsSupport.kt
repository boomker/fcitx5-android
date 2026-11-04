/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider

internal object ThemeSettingsSupport {

    val keyStyleThemeSwitchKeys = listOf(
        "key_border",
        "key_border_stroke",
        "gboard_style_side_keys"
    )

    val keyStyleThemeNonSwitchKeys = listOf(
        "key_radius",
        "key_horizontal_margin",
        "key_vertical_margin",
        "punctuation_position",
        "uppercase_position"
    )

    val keyStyleKeyboardSwitchKeys = listOf(
        "show_lang_switch_key",
        "keep_keyboard_letters_uppercase",
        "popup_on_key_press"
    )

    val keyStyleKeyboardNonSwitchKeys = listOf(
        "space_key_label_mode",
        "keyboard_height_percent",
        "keyboard_side_padding",
        "keyboard_bottom_padding"
    )

    val keyStyleThemeKeys = keyStyleThemeSwitchKeys + keyStyleThemeNonSwitchKeys

    val keyStyleKeyboardKeys = keyStyleKeyboardSwitchKeys + keyStyleKeyboardNonSwitchKeys

    val keyStyleSwitchKeys = keyStyleThemeSwitchKeys + keyStyleKeyboardSwitchKeys

    val keyStyleNonSwitchKeys = keyStyleThemeNonSwitchKeys + keyStyleKeyboardNonSwitchKeys

    val keyStyleKeys = keyStyleSwitchKeys + keyStyleNonSwitchKeys

    val keyStyleDisplayKeys = listOf(
        "key_border",
        "key_border_stroke",
        "show_lang_switch_key",
        "keep_keyboard_letters_uppercase",
        "popup_on_key_press",
        "gboard_style_side_keys",
        "key_radius",
        "key_horizontal_margin",
        "key_vertical_margin",
        "punctuation_position",
        "uppercase_position",
        "keyboard_height_percent",
        "keyboard_side_padding",
        "keyboard_bottom_padding",
        "space_key_label_mode"
    )

    val toolbarStyleThemeSwitchKeys = listOf(
        "navbar_border"
    )

    val toolbarStyleThemeNonSwitchKeys = listOf(
        "toolbar_height",
        "navbar_radius"
    )

    val toolbarStyleKeyboardSwitchKeys = listOf(
        "toolbar_dynamic_height",
        "toolbar_num_row_on_password",
        "physical_keyboard_horizontal_candidate_bar"
    )

    val toolbarStyleThemeKeys = toolbarStyleThemeSwitchKeys + toolbarStyleThemeNonSwitchKeys

    val toolbarStyleKeyboardKeys = toolbarStyleKeyboardSwitchKeys

    val toolbarStyleSwitchKeys = toolbarStyleThemeSwitchKeys + toolbarStyleKeyboardSwitchKeys

    val toolbarStyleNonSwitchKeys = toolbarStyleThemeNonSwitchKeys

    val toolbarStyleKeys = toolbarStyleSwitchKeys + toolbarStyleNonSwitchKeys

    val otherStyleThemeKeys = listOf(
        "text_editing_button_radius",
        "clipboard_entry_radius"
    )

    val otherStyleKeys = otherStyleThemeKeys

    val candidateHorizontalKeysBeforeAppearance = listOf(
        "horizontal_candidate_style",
        "expanded_candidate_style"
    )

    val candidateHorizontalAppearanceKeys = listOf(
        "candidate_highlight_radius"
    )

    val candidateHorizontalKeysAfterAppearance = listOf(
        "expanded_candidate_grid_span_count_portrait"
    )

    val candidateHorizontalKeys = candidateHorizontalKeysBeforeAppearance +
        candidateHorizontalAppearanceKeys +
        candidateHorizontalKeysAfterAppearance

    val candidateWindowKeys = listOf(
        "virtual_keyboard_candidates_position",
        "candidates_window_padding",
        "candidates_window_radius",
        "candidates_window_min_width"
    )

    val candidateItemKeys = listOf(
        "candidates_window_orientation",
        "candidates_item_padding_vertical"
    )

    val candidateStyleKeys = candidateHorizontalKeys +
        candidateWindowKeys +
        candidateItemKeys

    val animationThemeKeys = listOf(
        "key_ripple_effect"
    )

    val animationAdvancedKeys = listOf(
        "disable_animation"
    )

    val animationKeys = animationThemeKeys + animationAdvancedKeys

    val themeModeKeys = listOf(
        "follow_system_dark_mode",
        "light_mode_themes",
        "dark_mode_themes",
        "gboard_light_main_key_tone",
        "gboard_light_other_key_tone",
        "wallpaper_blend_percent"
    )

    /**
     * Preferences that keep living in [org.fxboomk.fcitx5.android.data.prefs.AppPrefs.Advanced]
     * but are presented by the appearance pages.
     */
    val advancedMovedKeys = animationAdvancedKeys

    fun ManagedPreferenceFragment.addThemePreferences(
        parent: PreferenceGroup,
        provider: ManagedPreferenceProvider,
        keys: List<String>
    ) {
        keys.forEach { key ->
            addManagedPreference(parent, provider, key)
        }
    }

    fun ManagedPreferenceFragment.addManagedPreference(
        parent: PreferenceGroup,
        provider: ManagedPreferenceProvider,
        key: String
    ): Preference? {
        val ui = provider.managedPreferencesUi.firstOrNull { it.key == key } ?: return null
        val preference = ui.createUi(parent.context).apply {
            isEnabled = ui.isEnabled()
        }
        parent.addPreference(preference)
        return preference
    }
}
