/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.ui.main.keyboardtest.installKeyboardTestMenu
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeSettingsSupport.addManagedPreference
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeSettingsSupport.addThemePreferences
import org.fxboomk.fcitx5.android.utils.addCategory

abstract class ThemeGroupFragment(
    provider: ManagedPreferenceProvider
) : ManagedPreferenceFragment(provider) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        installKeyboardTestMenu()
    }

    final override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.removeAll()
        onBuildPreferenceScreen(screen)
    }

    protected abstract fun onBuildPreferenceScreen(screen: PreferenceScreen)

    override fun onStop() {
        ThemeManager.syncToDeviceEncryptedStorage()
        super.onStop()
    }
}

class ThemeKeyStyleFragment : ThemeGroupFragment(ThemeManager.prefs) {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        val keyboard = AppPrefs.getInstance().keyboard
        val keyboardKeys = ThemeSettingsSupport.keyStyleKeyboardKeys.toSet()
        ThemeSettingsSupport.keyStyleDisplayKeys.forEachIndexed { index, key ->
            val provider = if (key in keyboardKeys) keyboard else ThemeManager.prefs
            addManagedPreference(screen, provider, key)?.order = index
        }
    }
}

class ThemeToolbarStyleFragment : ThemeGroupFragment(ThemeManager.prefs) {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        addThemePreferences(
            screen,
            ThemeManager.prefs,
            ThemeSettingsSupport.toolbarStyleThemeSwitchKeys
        )
        addThemePreferences(
            screen,
            AppPrefs.getInstance().keyboard,
            ThemeSettingsSupport.toolbarStyleKeyboardSwitchKeys
        )
        addThemePreferences(
            screen,
            ThemeManager.prefs,
            ThemeSettingsSupport.toolbarStyleThemeNonSwitchKeys
        )
    }
}

class ThemeCandidateStyleFragment : ThemeGroupFragment(AppPrefs.getInstance().candidates) {

    private var horizontalCategory: PreferenceCategory? = null
    private var candidateWindowCategory: PreferenceCategory? = null
    private var candidateItemCategory: PreferenceCategory? = null

    private val onCandidateDisplayModeChangeListener =
        ManagedPreferenceProvider.OnChangeListener { key ->
            if (key == AppPrefs.CANDIDATE_DISPLAY_MODE_KEY) {
                updateCandidateSettingVisibility()
            }
        }

    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        val candidates = AppPrefs.getInstance().candidates
        val keyboard = AppPrefs.getInstance().keyboard
        addManagedPreference(screen, candidates, AppPrefs.CANDIDATE_DISPLAY_MODE_KEY)
        screen.addCategory(R.string.candidate_display_mode_horizontal) {
            horizontalCategory = this
            addThemePreferences(
                this,
                keyboard,
                ThemeSettingsSupport.candidateHorizontalKeysBeforeAppearance
            )
            addThemePreferences(
                this,
                candidates,
                ThemeSettingsSupport.candidateHorizontalAppearanceKeys
            )
            addThemePreferences(
                this,
                keyboard,
                ThemeSettingsSupport.candidateHorizontalKeysAfterAppearance
            )
        }
        screen.addCategory(R.string.candidates_window) {
            candidateWindowCategory = this
            addThemePreferences(
                this,
                candidates,
                ThemeSettingsSupport.candidateWindowKeys
            )
        }
        screen.addCategory(R.string.candidates_items) {
            candidateItemCategory = this
            addThemePreferences(
                this,
                candidates,
                ThemeSettingsSupport.candidateItemKeys
            )
        }
        updateCandidateSettingVisibility()
        candidates.registerOnChangeListener(onCandidateDisplayModeChangeListener)
    }

    private fun updateCandidateSettingVisibility() {
        when (AppPrefs.getInstance().candidates.mode.getValue()) {
            FloatingCandidatesMode.InputDevice -> {
                horizontalCategory?.isVisible = true
                candidateWindowCategory?.isVisible = false
                candidateItemCategory?.isVisible = false
            }
            FloatingCandidatesMode.Always -> {
                horizontalCategory?.isVisible = false
                candidateWindowCategory?.isVisible = true
                candidateItemCategory?.isVisible = true
            }
            FloatingCandidatesMode.SystemDefault,
            FloatingCandidatesMode.Disabled -> {
                horizontalCategory?.isVisible = false
                candidateWindowCategory?.isVisible = false
                candidateItemCategory?.isVisible = false
            }
        }
    }

    override fun onDestroy() {
        AppPrefs.getInstance().candidates.unregisterOnChangeListener(
            onCandidateDisplayModeChangeListener
        )
        super.onDestroy()
    }
}

class ThemeAnimationFragment : ThemeGroupFragment(ThemeManager.prefs) {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        addThemePreferences(
            screen,
            ThemeManager.prefs,
            ThemeSettingsSupport.animationThemeKeys
        )
        addThemePreferences(
            screen,
            AppPrefs.getInstance().advanced,
            ThemeSettingsSupport.animationAdvancedKeys
        )
    }
}

class ThemeModeFragment : ThemeGroupFragment(ThemeManager.prefs) {

    private val followSystemDayNightTheme = ThemeManager.prefs.followSystemDayNightTheme
    private val themeSelectionChangeListener =
        org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider.OnChangeListener { key ->
            if (key == ThemeManager.prefs.lightModeThemes.key ||
                key == ThemeManager.prefs.darkModeThemes.key
            ) {
                updateThemeSelectionSummaries()
            }
        }

    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        addThemePreferences(screen, ThemeManager.prefs, ThemeSettingsSupport.themeModeKeys)
        ThemeManager.prefs.registerOnChangeListener(themeSelectionChangeListener)
    }

    override fun onResume() {
        super.onResume()
        findPreference<SwitchPreference>(followSystemDayNightTheme.key)?.isChecked =
            followSystemDayNightTheme.getValue()
        updateThemeSelectionSummaries()
    }

    private fun updateThemeSelectionSummaries() {
        val prefs = ThemeManager.prefs
        findPreference<Preference>(prefs.lightModeThemes.key)?.summary =
            getString(R.string.theme_mode_selected_summary, prefs.lightModeThemes.getThemes().size)
        findPreference<Preference>(prefs.darkModeThemes.key)?.summary =
            getString(R.string.theme_mode_selected_summary, prefs.darkModeThemes.getThemes().size)
    }

    override fun onDestroy() {
        ThemeManager.prefs.unregisterOnChangeListener(themeSelectionChangeListener)
        super.onDestroy()
    }
}

class ThemeOtherStyleFragment : ThemeGroupFragment(ThemeManager.prefs) {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        addThemePreferences(
            screen,
            ThemeManager.prefs,
            ThemeSettingsSupport.otherStyleThemeKeys
        )
    }
}
