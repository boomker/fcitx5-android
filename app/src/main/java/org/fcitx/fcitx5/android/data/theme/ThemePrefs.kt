/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceCategory
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

class ThemePrefs(sharedPreferences: SharedPreferences) :
    ManagedPreferenceCategory(R.string.theme, sharedPreferences) {

    private fun themeMultiSelectPreference(
        @StringRes
        title: Int,
        key: String,
        defaultSelected: Set<String>,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedThemeSetPreference {
        val pref = ManagedThemeSetPreference(sharedPreferences, key, defaultSelected)
        val ui = ManagedThemeMultiSelectPreferenceUi(title, key, defaultSelected, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    val keyBorder = switch(R.string.key_border, "key_border", false)

    val keyBorderStroke = switch(
        R.string.key_border_stroke, "key_border_stroke", false,
        enableUiOn = { keyBorder.getValue() }
    )

    val keyRippleEffect = switch(R.string.key_ripple_effect, "key_ripple_effect", false)

    val keyHorizontalMargin: ManagedPreference.PInt
    val keyHorizontalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_horizontal_margin,
            R.string.portrait,
            "key_horizontal_margin",
            3,
            R.string.landscape,
            "key_horizontal_margin_landscape",
            3,
            0,
            24,
            "dp"
        )
        keyHorizontalMargin = primary
        keyHorizontalMarginLandscape = secondary
    }

    val keyVerticalMargin: ManagedPreference.PInt
    val keyVerticalMarginLandscape: ManagedPreference.PInt

    init {
        val (primary, secondary) = twinInt(
            R.string.key_vertical_margin,
            R.string.portrait,
            "key_vertical_margin",
            7,
            R.string.landscape,
            "key_vertical_margin_landscape",
            4,
            0,
            24,
            "dp"
        )
        keyVerticalMargin = primary
        keyVerticalMarginLandscape = secondary
    }

    val keyRadius = int(R.string.key_radius, "key_radius", 4, 0, 48, "dp")

    val textEditingButtonRadius =
        int(R.string.text_editing_button_radius, "text_editing_button_radius", 8, 0, 48, "dp")

    val clipboardEntryRadius =
        int(R.string.clipboard_entry_radius, "clipboard_entry_radius", 2, 0, 48, "dp")

    enum class PunctuationPosition(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.punctuation_pos_none),
        Bottom(R.string.punctuation_pos_bottom),
        TopRight(R.string.punctuation_pos_top_right);
    }

    val punctuationPosition = enumList(
        R.string.punctuation_position,
        "punctuation_position",
        PunctuationPosition.Bottom
    )

    enum class NavbarBackground(override val stringRes: Int) : ManagedPreferenceEnum {
        None(R.string.navbar_bkg_none),
        ColorOnly(R.string.navbar_bkg_color_only),
        Full(R.string.navbar_bkg_full);
    }

    val navbarBackground = enumList(
        R.string.navbar_background,
        "navbar_background",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) NavbarBackground.Full else NavbarBackground.ColorOnly,
        // 35+ forces edge to edge
        enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM }
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            sharedPreferences.edit {
                remove(this@apply.key)
            }
        }
    }

    /**
     * When [followSystemDayNightTheme] is disabled, this theme is used.
     * This is effectively an internal preference which does not need UI.
     */
    val normalModeTheme = ManagedThemePreference(
        sharedPreferences, "normal_mode_theme", ThemeManager.DefaultTheme
    ).also {
        it.register()
    }

    val followSystemDayNightTheme = switch(
        R.string.follow_system_day_night_theme,
        "follow_system_dark_mode",
        true,
        summary = R.string.follow_system_day_night_theme_summary
    )

    /**
     * Selected themes for light mode. Multiple themes can be selected and cycled through.
     */
    val lightModeThemes = themeMultiSelectPreference(
        R.string.light_mode_theme,
        "light_mode_themes",
        setOf(if (BuildConfig.DEBUG) ThemePreset.MaterialLight.name else ThemePreset.PixelLight.name),
        summary = R.string.light_mode_theme_summary,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        }
    )

    /**
     * Selected themes for dark mode. Multiple themes can be selected and cycled through.
     */
    val darkModeThemes = themeMultiSelectPreference(
        R.string.dark_mode_theme,
        "dark_mode_themes",
        setOf(if (BuildConfig.DEBUG) ThemePreset.MaterialDark.name else ThemePreset.PixelDark.name),
        summary = R.string.dark_mode_theme_summary,
        enableUiOn = {
            followSystemDayNightTheme.getValue()
        }
    )

    /**
     * Index of the currently active light theme in the light mode themes list.
     * Used for cycling through multiple light themes.
     */
    val currentLightThemeIndex = ManagedPreference.PInt(
        sharedPreferences,
        "current_light_theme_index",
        0
    ).also {
        it.register()
    }

    /**
     * Index of the currently active dark theme in the dark mode themes list.
     * Used for cycling through multiple dark themes.
     */
    val currentDarkThemeIndex = ManagedPreference.PInt(
        sharedPreferences,
        "current_dark_theme_index",
        0
    ).also {
        it.register()
    }

    val dayNightModePrefNames = setOf(
        followSystemDayNightTheme.key,
        lightModeThemes.key,
        darkModeThemes.key,
        currentLightThemeIndex.key,
        currentDarkThemeIndex.key
    )
}
