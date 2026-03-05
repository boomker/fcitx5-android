/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.utils.addPreference

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
	override fun onPreferenceUiCreated(screen: PreferenceScreen) {
		screen.addPreference(
			R.string.edit_fontset,
			R.string.edit_fontset_summary
		) {
			startActivity(Intent(requireContext(), FontsetEditorActivity::class.java))
		}
	}
}
