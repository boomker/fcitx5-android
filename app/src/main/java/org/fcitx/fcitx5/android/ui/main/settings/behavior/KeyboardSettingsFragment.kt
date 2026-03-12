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

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    companion object {
        private const val CALIBRATION_PREF_KEY = "split_keyboard_calibration"
        private const val SPLIT_GAP_KEY = "split_keyboard_gap_percent"
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

        // Split keyboard calibration entry - placed after "Split keyboard center gap"
        val calibrationPreference = Preference(requireContext()).apply {
            key = CALIBRATION_PREF_KEY
            setTitle(R.string.split_keyboard_calibration_title)
            setSummary(R.string.split_keyboard_calibration_summary)
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SplitKeyboardCalibrationActivity::class.java))
                true
            }
        }

        // Assign stable order to existing items, then insert calibration entry after "Split keyboard center gap"
        val gapIndex = (0 until screen.preferenceCount)
            .firstOrNull { index -> screen.getPreference(index).key == SPLIT_GAP_KEY }
        for (index in 0 until screen.preferenceCount) {
            screen.getPreference(index).order = index * 2
        }
        calibrationPreference.order = gapIndex?.let { it * 2 + 1 } ?: Int.MAX_VALUE

        screen.addPreference(calibrationPreference)
    }
}
