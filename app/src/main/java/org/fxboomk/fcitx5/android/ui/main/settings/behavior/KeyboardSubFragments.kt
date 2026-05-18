/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference

abstract class KeyboardSectionFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).also(::onBuildPreferenceScreen)
    }

    protected abstract fun onBuildPreferenceScreen(screen: PreferenceScreen)
}

class KeyboardBasicSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_basic_behavior, basicBehaviorKeys)
        }
    }
}

class KeyboardTouchAndSoundSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_touch_and_sound, touchAndSoundKeys)
        }
    }
}

class KeyboardToolbarAndInputSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_toolbar_and_voice, toolbarAndInputKeys)
        }
    }
}

class KeyboardKeyAndGestureSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_key_and_gesture, keyAndGestureKeys)
        }
    }
}

class KeyboardLayoutAndSplitSettingsFragment : KeyboardSectionFragment() {

    private var calibrationPreference: Preference? = null
    private var useLandscapePreference: Preference? = null

    private val onSplitEnabledChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == KeyboardSettingsSupport.SPLIT_ENABLED_KEY) {
            val enabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            calibrationPreference?.isEnabled = enabled
            useLandscapePreference?.isEnabled = enabled
        }
    }

    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_layout_and_split, layoutKeys)
        }
        calibrationPreference = KeyboardSettingsSupport.createSplitKeyboardCalibrationPreference(this)
        screen.addCategory(R.string.keyboard_settings_split_tools_section) {
            calibrationPreference?.let(::addPreference)
        }
        useLandscapePreference = screen.findPreference("split_keyboard_use_landscape_layout")
        useLandscapePreference?.isEnabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
        AppPrefs.getInstance().keyboard.registerOnChangeListener(onSplitEnabledChangeListener)
    }

    override fun onDestroy() {
        AppPrefs.getInstance().keyboard.unregisterOnChangeListener(onSplitEnabledChangeListener)
        super.onDestroy()
    }
}

class KeyboardCandidatesSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_candidates, candidatesKeys)
        }
    }
}

class KeyboardAdvancedCustomizationFragment : KeyboardSectionFragment() {

    private var textLayoutFilePreference: Preference? = null
    private var textLayoutFileSelectPreference: Preference? = null

    override fun onResume() {
        super.onResume()
        textLayoutFilePreference?.summary = KeyboardSettingsSupport.buildTextLayoutSummary(this)
        textLayoutFileSelectPreference?.summary = KeyboardSettingsSupport.buildCurrentTextLayoutFileSummary(this)
    }

    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        screen.addCategory(R.string.keyboard_settings_advanced_customization) {
            addPreference(R.string.edit_fontset, R.string.edit_fontset_summary) {
                startActivity(Intent(requireContext(), FontsetEditorActivity::class.java))
            }
            addPreference(R.string.edit_popup_preset, R.string.edit_popup_preset_summary) {
                startActivity(Intent(requireContext(), PopupEditorActivity::class.java))
            }
            addPreference(Preference(requireContext()).apply {
                setTitle(R.string.edit_text_keyboard_layout)
                summary = KeyboardSettingsSupport.buildTextLayoutSummary(this@KeyboardAdvancedCustomizationFragment)
                isSingleLineTitle = false
                isIconSpaceReserved = false
                textLayoutFilePreference = this
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), TextKeyboardLayoutEditorActivity::class.java))
                    true
                }
            })
            addPreference(Preference(requireContext()).apply {
                setTitle(R.string.text_keyboard_layout_file_select_title)
                summary = KeyboardSettingsSupport.buildCurrentTextLayoutFileSummary(this@KeyboardAdvancedCustomizationFragment)
                isSingleLineTitle = false
                isIconSpaceReserved = false
                textLayoutFileSelectPreference = this
                setOnPreferenceClickListener {
                    KeyboardSettingsSupport.showSelectTextLayoutFileDialog(this@KeyboardAdvancedCustomizationFragment) {
                        textLayoutFilePreference?.summary = KeyboardSettingsSupport.buildTextLayoutSummary(this@KeyboardAdvancedCustomizationFragment)
                        textLayoutFileSelectPreference?.summary = KeyboardSettingsSupport.buildCurrentTextLayoutFileSummary(this@KeyboardAdvancedCustomizationFragment)
                    }
                    true
                }
            })
            addPreference(R.string.edit_buttons, R.string.edit_buttons_summary) {
                startActivity(Intent(requireContext(), ButtonsCustomizerActivity::class.java))
            }
        }
    }
}
