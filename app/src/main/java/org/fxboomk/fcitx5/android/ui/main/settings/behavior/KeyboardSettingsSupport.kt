/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim

internal object KeyboardSettingsSupport {

    const val SPLIT_ENABLED_KEY = "split_keyboard_enabled"
    private const val CALIBRATION_PREF_KEY = "split_keyboard_calibration"

    val touchAndSoundKeys = listOf(
        "haptic_on_keypress",
        "haptic_on_keyup",
        "haptic_on_repeat",
        "button_vibration_press_milliseconds",
        "button_vibration_press_amplitude",
        "sound_on_keypress",
        "button_sound_volume"
    )

    val basicBehaviorKeys = listOf(
        "reset_keyboard_on_focus_change",
        "inline_suggestions",
        "keep_keyboard_letters_uppercase"
    )

    val toolbarAndInputKeys = listOf(
        "expand_toolbar_by_default",
        "toolbar_manually_toggled",
        "toolbar_num_row_on_password",
        "show_voice_input_button",
        "preferred_voice_input"
    )

    val keyAndGestureKeys = listOf(
        "popup_on_key_press",
        "expand_keypress_area",
        "show_lang_switch_key",
        "swipe_symbol_behavior",
        "keyboard_long_press_delay",
        "lang_switch_key_behavior",
        "space_long_press_behavior",
        "space_key_label_mode",
        "prediction_space_behavior",
        "space_swipe_vertical_behavior"
    )

    val layoutKeys = listOf(
        "keyboard_height_percent",
        "keyboard_side_padding",
        "keyboard_bottom_padding",
        SPLIT_ENABLED_KEY,
        "split_keyboard_use_landscape_layout"
    )

    val candidatesKeys = listOf(
        "horizontal_candidate_style",
        "expanded_candidate_style",
        "expanded_candidate_grid_span_count_portrait"
    )

    fun ManagedPreferenceFragment.addKeyboardPreference(
        parent: PreferenceGroup,
        key: String
    ): Preference? {
        val ui = AppPrefs.getInstance().keyboard.managedPreferencesUi.firstOrNull { it.key == key } ?: return null
        val preference = ui.createUi(parent.context).apply {
            isEnabled = ui.isEnabled()
        }
        parent.addPreference(preference)
        return preference
    }

    fun ManagedPreferenceFragment.addKeyboardSection(
        screen: PreferenceScreen,
        @StringRes title: Int,
        keys: List<String>
    ) {
        screen.addCategory(title) {
            keys.forEach { key ->
                addKeyboardPreference(this, key)
            }
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

    fun showSelectTextLayoutFileDialog(fragment: ManagedPreferenceFragment, onChanged: () -> Unit) {
        val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableList()
        val current = currentTextLayoutProfile()
        if (current !in profiles) profiles += current
        val sortedProfiles = profiles.distinct().sortedWith(
            compareBy({ it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it })
        )
        val labels = sortedProfiles.map { displayProfile(fragment, it) }.toTypedArray()
        val initialSelection = sortedProfiles.indexOf(current).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.text_keyboard_layout_file_select_title)
            .setSingleChoiceItems(labels, initialSelection) { dialog, which ->
                val selectedProfile = sortedProfiles.getOrNull(which) ?: return@setSingleChoiceItems
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(selectedProfile)
                ConfigProviders.provider = ConfigProviders.provider
                onChanged()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun PreferenceCategory.addDestinationPreference(
        fragment: ManagedPreferenceFragment,
        @StringRes title: Int,
        summary: String,
        route: SettingsRoute
    ) {
        addPreference(title, summary) {
            fragment.navigateWithAnim(route)
        }
    }
}
