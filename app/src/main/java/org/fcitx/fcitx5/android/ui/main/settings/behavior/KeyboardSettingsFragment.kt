/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    companion object {
        private const val CALIBRATION_PREF_KEY = "split_keyboard_calibration"
        private const val SPLIT_ENABLED_KEY = "split_keyboard_enabled"
    }

    private var calibrationPreference: Preference? = null
    
    private val onSplitEnabledChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == SPLIT_ENABLED_KEY) {
            val enabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            calibrationPreference?.isEnabled = enabled
            // Also enable/disable "Use landscape layout when split" preference when auto-split is toggled
            val useLandscapePref = preferenceScreen.findPreference<Preference>("split_keyboard_use_landscape_layout")
            useLandscapePref?.isEnabled = enabled
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.edit_fontset)
            setSummary(R.string.edit_fontset_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), FontsetEditorActivity::class.java))
                true
            }
        })
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.edit_popup_preset)
            setSummary(R.string.edit_popup_preset_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), PopupEditorActivity::class.java))
                true
            }
        })
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.edit_text_keyboard_layout)
            setSummary(R.string.edit_text_keyboard_layout_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), TextKeyboardLayoutEditorActivity::class.java))
                true
            }
        })

        // Split keyboard calibration entry - placed after "Enable auto split keyboard"
        calibrationPreference = Preference(requireContext()).apply {
            key = CALIBRATION_PREF_KEY
            setTitle(R.string.split_keyboard_calibration_title)
            setSummary(R.string.split_keyboard_calibration_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            isEnabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SplitKeyboardCalibrationActivity::class.java))
                true
            }
        }

        // Assign stable order to existing items, then insert calibration entry after "Enable auto split keyboard"
        val enabledIndex = (0 until screen.preferenceCount)
            .firstOrNull { index -> screen.getPreference(index).key == SPLIT_ENABLED_KEY }
        for (index in 0 until screen.preferenceCount) {
            screen.getPreference(index).order = index * 2
        }
        calibrationPreference?.order = enabledIndex?.let { it * 2 + 1 } ?: Int.MAX_VALUE

        calibrationPreference?.let { screen.addPreference(it) }
        
        // Ensure "Use landscape layout when split" preference is enabled only when auto-split is enabled
        val useLandscapePref = screen.findPreference<Preference>("split_keyboard_use_landscape_layout")
        useLandscapePref?.isEnabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()

        // Register listener to update calibration preference enabled state
        AppPrefs.getInstance().keyboard.registerOnChangeListener(onSplitEnabledChangeListener)
    }
    
    override fun onDestroy() {
        AppPrefs.getInstance().keyboard.unregisterOnChangeListener(onSplitEnabledChangeListener)
        super.onDestroy()
    }
}
