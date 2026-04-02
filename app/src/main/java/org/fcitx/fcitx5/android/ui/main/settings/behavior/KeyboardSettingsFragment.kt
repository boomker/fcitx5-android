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
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {

    private var calibrationPreference: Preference? = null
    private var textLayoutFilePreference: Preference? = null
    private var textLayoutFileSelectPreference: Preference? = null
    
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
            setSummary(buildTextLayoutSummary())
            isSingleLineTitle = false
            isIconSpaceReserved = false
            textLayoutFilePreference = this
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), TextKeyboardLayoutEditorActivity::class.java))
                true
            }
        })
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.text_keyboard_layout_file_select_title)
            key = TEXT_LAYOUT_FILE_SELECT_PREF_KEY
            setSummary(buildCurrentTextLayoutFileSummary())
            isSingleLineTitle = false
            isIconSpaceReserved = false
            textLayoutFileSelectPreference = this
            setOnPreferenceClickListener {
                showSelectTextLayoutFileDialog()
                true
            }
        })
        screen.addPreference(Preference(requireContext()).apply {
            setTitle(R.string.edit_buttons)
            setSummary(R.string.edit_buttons_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ButtonsCustomizerActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        textLayoutFilePreference?.summary = buildTextLayoutSummary()
        textLayoutFileSelectPreference?.summary = buildCurrentTextLayoutFileSummary()
    }

    private fun buildTextLayoutSummary(): String {
        return getString(
            R.string.edit_text_keyboard_layout_summary_with_file,
            displayProfile(currentTextLayoutProfile())
        )
    }

    private fun buildCurrentTextLayoutFileSummary(): String {
        val profile = currentTextLayoutProfile()
        return getString(
            R.string.text_keyboard_layout_file_select_summary,
            displayProfile(profile)
        )
    }

    private fun currentTextLayoutProfile(): String {
        return UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
    }

    private fun showSelectTextLayoutFileDialog() {
        val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableList()
        val current = currentTextLayoutProfile()
        if (current !in profiles) profiles += current
        val sortedProfiles = profiles.distinct().sortedWith(compareBy({ it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
        val labels = sortedProfiles.map { displayProfile(it) }.toTypedArray()
        val initialSelection = sortedProfiles.indexOf(current).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_keyboard_layout_file_select_title)
            .setSingleChoiceItems(labels, initialSelection) { dialog, which ->
                val selectedProfile = sortedProfiles.getOrNull(which) ?: return@setSingleChoiceItems
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(selectedProfile)
                ConfigProviders.provider = ConfigProviders.provider
                textLayoutFilePreference?.summary = buildTextLayoutSummary()
                textLayoutFileSelectPreference?.summary = buildCurrentTextLayoutFileSummary()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displayProfile(profile: String): String {
        return if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            getString(R.string.default_)
        } else {
            profile
        }
    }

    companion object {
        private const val CALIBRATION_PREF_KEY = "split_keyboard_calibration"
        private const val SPLIT_ENABLED_KEY = "split_keyboard_enabled"
        private const val TEXT_LAYOUT_FILE_SELECT_PREF_KEY = "text_keyboard_layout_file_select"
    }
}
