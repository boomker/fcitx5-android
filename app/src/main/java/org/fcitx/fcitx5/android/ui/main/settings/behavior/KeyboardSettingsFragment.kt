/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
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
	}
}
