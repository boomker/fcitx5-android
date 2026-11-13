/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.SingleLineSummaryPreference
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import org.fxboomk.fcitx5.android.utils.setup

internal object KeyboardSettingsSupport {

    const val SPLIT_ENABLED_KEY = "split_keyboard_enabled"
    private const val CALIBRATION_PREF_KEY = "split_keyboard_calibration"

    val touchAndSoundKeys = listOf(
        "haptic_on_keypress",
        "haptic_on_keyup",
        "haptic_on_repeat",
        "button_vibration_press_amplitude",
        "button_vibration_press_milliseconds",
        "keyboard_long_press_delay",
        "sound_on_keypress",
        "button_sound_volume"
    )

    val basicBehaviorKeys = listOf(
        "reset_keyboard_on_focus_change",
        "inline_suggestions",
        "auto_close_brackets",
        "insert_space_between_chinese_and_english",
        "expand_keypress_area"
    )

    val keyAndGestureKeys = listOf(
        "punctuation_swipe_strategy",
        "swipe_symbol_behavior",
        "caps_key_behavior",
        "lang_switch_key_behavior",
        "lang_switch_key_long_press_behavior",
        "space_long_press_behavior",
        "space_swipe_vertical_behavior",
        "prediction_space_behavior",
        "prediction_backspace_behavior"
    )

    val layoutKeys = listOf(
        SPLIT_ENABLED_KEY,
        "split_keyboard_use_landscape_layout"
    )

    val voiceKeys = listOf(
        "show_voice_input_button",
        "preferred_voice_input"
    )

    fun ManagedPreferenceFragment.addManagedPreference(
        parent: PreferenceGroup,
        provider: ManagedPreferenceProvider,
        key: String
    ): Preference? {
        if (key == "space_swipe_vertical_behavior") {
            return SpaceSwipeVerticalPreference(parent.context).also { parent.addPreference(it) }
        }
        val ui = provider.managedPreferencesUi.firstOrNull { it.key == key } ?: return null
        val preference = ui.createUi(parent.context).apply {
            isEnabled = ui.isEnabled()
        }
        parent.addPreference(preference)
        return preference
    }

    fun ManagedPreferenceFragment.addKeyboardSection(
        screen: PreferenceScreen,
        @StringRes title: Int,
        keys: List<String>,
        extraPreference: Preference? = null
    ) {
        screen.addCategory(title) {
            keys.forEach { key ->
                addManagedPreference(this, AppPrefs.getInstance().keyboard, key)
            }
            extraPreference?.let(::addPreference)
        }
    }

    fun createSplitKeyboardCalibrationPreference(fragment: ManagedPreferenceFragment): Preference {
        return Preference(fragment.requireContext()).apply {
            key = CALIBRATION_PREF_KEY
            setTitle(R.string.split_keyboard_calibration_title)
            setSummary(R.string.split_keyboard_calibration_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            isEnabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            setOnPreferenceClickListener {
                fragment.startActivity(Intent(fragment.requireContext(), SplitKeyboardCalibrationActivity::class.java))
                true
            }
        }
    }

    fun currentTextLayoutProfile(): String {
        return UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    }

    fun displayProfile(fragment: ManagedPreferenceFragment, profile: String): String {
        return if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            fragment.getString(R.string.default_)
        } else {
            profile
        }
    }

    fun buildTextLayoutSummary(fragment: ManagedPreferenceFragment): String {
        return fragment.getString(
            R.string.edit_text_keyboard_layout_summary_with_file,
            displayProfile(fragment, currentTextLayoutProfile())
        )
    }

    fun buildCurrentTextLayoutFileSummary(fragment: ManagedPreferenceFragment): String {
        return fragment.getString(
            R.string.text_keyboard_layout_file_select_summary,
            displayProfile(fragment, currentTextLayoutProfile())
        )
    }

    fun PreferenceGroup.addDestinationPreference(
        fragment: ManagedPreferenceFragment,
        @StringRes title: Int,
        summary: String,
        route: SettingsRoute,
        key: String? = null
    ) {
        addPreference(
            SingleLineSummaryPreference(context).apply {
                this.key = key
                setup(context.getString(title), summary) {
                    fragment.navigateWithAnim(route)
                }
            }
        )
    }
}
