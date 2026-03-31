/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet

class ThemeSettingsFragment : ManagedPreferenceFragment(ThemeManager.prefs) {

    private val followSystemDayNightTheme = ThemeManager.prefs.followSystemDayNightTheme

    private lateinit var switchPreference: SwitchPreference
    private lateinit var monetEditorLauncher: ActivityResultLauncher<Theme.Monet>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        monetEditorLauncher =
            registerForActivityResult(MonetThemeEditorActivity.Contract()) { result ->
                if (result == null) return@registerForActivityResult
                ThemeManager.refreshThemes()
            }
        super.onCreatePreferences(savedInstanceState, rootKey)
        switchPreference = findPreference(followSystemDayNightTheme.key)!!
        if (ThemeMonet.supportsCustomMappingEditor(requireContext())) {
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.edit_monet_light_theme)
                    summary = getString(R.string.edit_monet_theme_summary)
                    setOnPreferenceClickListener {
                        openMonetEditor(isDark = false)
                        true
                    }
                }
            )
            preferenceScreen.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.edit_monet_dark_theme)
                    summary = getString(R.string.edit_monet_theme_summary)
                    setOnPreferenceClickListener {
                        openMonetEditor(isDark = true)
                        true
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        switchPreference.isChecked = followSystemDayNightTheme.getValue()
    }

    private fun openMonetEditor(isDark: Boolean) {
        if (!ThemeMonet.supportsCustomMappingEditor(requireContext())) return
        val themeName = if (isDark) "MonetDark" else "MonetLight"
        val theme = (ThemeManager.getTheme(themeName) as? Theme.Monet)
            ?: if (isDark) ThemeMonet.getDark() else ThemeMonet.getLight()
        monetEditorLauncher.launch(theme)
    }
}
