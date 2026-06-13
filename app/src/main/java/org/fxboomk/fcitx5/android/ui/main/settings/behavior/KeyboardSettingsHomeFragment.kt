/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim

class KeyboardSettingsHomeFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.removeAll()
        buildScreen(screen)
    }

    private fun buildScreen(screen: PreferenceScreen) {
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        screen.addCategory(R.string.keyboard_settings_basic_section) {
            addPreference(
                R.string.keyboard_settings_basic_behavior,
                buildBasicBehaviorSummary(keyboardPrefs)
            ) {
                navigateWithAnim(SettingsRoute.VirtualKeyboardBasic)
            }
        }
        screen.addCategory(R.string.keyboard_settings_sections) {
            KeyboardSettingsSupport.run {
                addDestinationPreference(
                this@KeyboardSettingsHomeFragment,
                R.string.keyboard_settings_touch_and_sound,
                buildTouchAndSoundSummary(keyboardPrefs),
                SettingsRoute.VirtualKeyboardTouchAndSound
            )
                addDestinationPreference(
                this@KeyboardSettingsHomeFragment,
                R.string.keyboard_settings_toolbar_and_voice,
                buildToolbarSummary(keyboardPrefs),
                SettingsRoute.VirtualKeyboardToolbarAndInput
            )
                addDestinationPreference(
                this@KeyboardSettingsHomeFragment,
                R.string.keyboard_settings_key_and_gesture,
                buildKeyAndGestureSummary(keyboardPrefs),
                SettingsRoute.VirtualKeyboardKeyAndGesture
            )
                addDestinationPreference(
                this@KeyboardSettingsHomeFragment,
                R.string.keyboard_settings_layout_and_split,
                buildLayoutSummary(keyboardPrefs),
                SettingsRoute.VirtualKeyboardLayoutAndSplit
            )
                addDestinationPreference(
                this@KeyboardSettingsHomeFragment,
                R.string.keyboard_settings_candidates,
                buildCandidatesSummary(keyboardPrefs),
                SettingsRoute.VirtualKeyboardCandidates
            )
                addDestinationPreference(
                this@KeyboardSettingsHomeFragment,
                R.string.keyboard_settings_advanced_customization,
                getString(R.string.keyboard_advanced_customization_summary),
                SettingsRoute.VirtualKeyboardAdvancedCustomization
            )
            }
        }
    }

    private fun buildBasicBehaviorSummary(keyboardPrefs: AppPrefs.Keyboard): String {
        val focusSummary = if (keyboardPrefs.focusChangeResetKeyboard.getValue()) {
            getString(R.string.focus_change_reset_enabled_summary)
        } else {
            getString(R.string.focus_change_reset_disabled_summary)
        }
        val inlineSummary = if (keyboardPrefs.inlineSuggestions.getValue()) {
            getString(R.string.inline_suggestions_enabled_summary)
        } else {
            getString(R.string.inline_suggestions_disabled_summary)
        }
        return "$focusSummary，$inlineSummary"
    }

    private fun buildTouchAndSoundSummary(keyboardPrefs: AppPrefs.Keyboard): String {
        val haptic = getString(keyboardPrefs.hapticOnKeyPress.getValue().stringRes)
        val sound = getString(keyboardPrefs.soundOnKeyPress.getValue().stringRes)
        return "$haptic，$sound"
    }

    private fun buildToolbarSummary(keyboardPrefs: AppPrefs.Keyboard): String {
        val toolbarSummary = if (keyboardPrefs.expandToolbarByDefault.getValue()) {
            getString(R.string.expand_toolbar_by_default)
        } else {
            getString(R.string.toolbar_collapsed_by_default)
        }
        val voiceSummary = if (keyboardPrefs.showVoiceInputButton.getValue()) {
            getString(R.string.voice_input_button_visible_summary)
        } else {
            getString(R.string.voice_input_hidden_summary)
        }
        return "$toolbarSummary，$voiceSummary"
    }

    private fun buildKeyAndGestureSummary(keyboardPrefs: AppPrefs.Keyboard): String {
        val popupSummary = if (keyboardPrefs.popupOnKeyPress.getValue()) {
            getString(R.string.popup_preview_visible_summary)
        } else {
            getString(R.string.popup_preview_hidden_summary)
        }
        val langSwitchSummary = getString(keyboardPrefs.langSwitchKeyBehavior.getValue().stringRes)
        return "$popupSummary，$langSwitchSummary"
    }

    private fun buildLayoutSummary(keyboardPrefs: AppPrefs.Keyboard): String {
        val height = getString(
            R.string.keyboard_height_summary_compact,
            keyboardPrefs.keyboardHeightPercent.getValue(),
            keyboardPrefs.keyboardHeightPercentLandscape.getValue()
        )
        val split = if (keyboardPrefs.splitKeyboardEnabled.getValue()) {
            getString(R.string.split_keyboard_enabled_summary_short)
        } else {
            getString(R.string.split_keyboard_disabled_summary)
        }
        return "$height，$split"
    }

    private fun buildCandidatesSummary(keyboardPrefs: AppPrefs.Keyboard): String {
        val horizontal = getString(keyboardPrefs.horizontalCandidateStyle.getValue().stringRes)
        val expanded = getString(keyboardPrefs.expandedCandidateStyle.getValue().stringRes)
        return "$horizontal，$expanded"
    }
}
