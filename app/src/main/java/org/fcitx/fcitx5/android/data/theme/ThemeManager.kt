/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    val DefaultTheme = ThemePreset.PixelDark

    private var monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name }
            ?: monetThemes.find { it.name == name }
            ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + monetThemes + BuiltinThemes

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        activeTheme = evaluateActiveTheme()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            if (_activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        ThemeFilesManager.saveThemeFiles(theme)
        customThemes.indexOfFirst { it.name == theme.name }.also {
            if (it >= 0) customThemes[it] = theme else customThemes.add(0, theme)
        }
        if (activeTheme.name == theme.name) {
            activeTheme = theme
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also {
            // Pass all themes except the one being deleted, so we can clean up unused directories
            val otherThemes = customThemes.filter { it.name != name }
            ThemeFilesManager.deleteThemeFiles(it, otherThemes)
            customThemes.remove(it)
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        _activeTheme = theme
        prefs.normalModeTheme.setValue(theme)
    }

    /**
     * Check if the current active theme is in the dark mode themes list.
     */
    fun isUsingConfiguredDarkTheme(): Boolean {
        val darkThemes = prefs.darkModeThemes.getValue()
        return activeTheme.name in darkThemes
    }

    /**
     * Check if the current active theme is in the light mode themes list.
     */
    fun isUsingConfiguredLightTheme(): Boolean {
        val lightThemes = prefs.lightModeThemes.getValue()
        return activeTheme.name in lightThemes
    }

    /**
     * Get a random light theme from the selected light themes.
     */
    fun getCurrentLightTheme(): Theme {
        val themes = prefs.lightModeThemes.getThemes()
        if (themes.isEmpty()) return ThemePreset.PixelLight
        return if (prefs.followSystemDayNightTheme.getValue()) {
            // Random selection when following system
            themes.random()
        } else {
            // Use stored index for manual selection
            val index = prefs.currentLightThemeIndex.getValue().coerceIn(0, themes.size - 1)
            themes[index]
        }
    }

    /**
     * Get a random dark theme from the selected dark themes.
     */
    fun getCurrentDarkTheme(): Theme {
        val themes = prefs.darkModeThemes.getThemes()
        if (themes.isEmpty()) return ThemePreset.PixelDark
        return if (prefs.followSystemDayNightTheme.getValue()) {
            // Random selection when following system
            themes.random()
        } else {
            // Use stored index for manual selection
            val index = prefs.currentDarkThemeIndex.getValue().coerceIn(0, themes.size - 1)
            themes[index]
        }
    }

    /**
     * Toggle between light and dark mode themes, cycling to the next theme in the target mode.
     */
    fun toggleConfiguredDayNightTheme(): Theme {
        val nextTheme = if (isUsingConfiguredDarkTheme()) {
            // Currently dark, switch to light (cycle light themes)
            cycleToNextLightTheme()
        } else {
            // Currently light or not in either list, switch to dark (cycle dark themes)
            cycleToNextDarkTheme()
        }
        _activeTheme = nextTheme
        prefs.normalModeTheme.setValue(nextTheme)
        if (prefs.followSystemDayNightTheme.getValue()) {
            prefs.followSystemDayNightTheme.setValue(false)
        }
        return nextTheme
    }

    /**
     * Cycle to the next light theme in the list and return it.
     */
    private fun cycleToNextLightTheme(): Theme {
        val themes = prefs.lightModeThemes.getThemes()
        if (themes.isEmpty()) return ThemePreset.PixelLight
        val currentIndex = prefs.currentLightThemeIndex.getValue()
        val nextIndex = (currentIndex + 1) % themes.size
        prefs.currentLightThemeIndex.setValue(nextIndex)
        return themes[nextIndex]
    }

    /**
     * Cycle to the next dark theme in the list and return it.
     */
    private fun cycleToNextDarkTheme(): Theme {
        val themes = prefs.darkModeThemes.getThemes()
        if (themes.isEmpty()) return ThemePreset.PixelDark
        val currentIndex = prefs.currentDarkThemeIndex.getValue()
        val nextIndex = (currentIndex + 1) % themes.size
        prefs.currentDarkThemeIndex.setValue(nextIndex)
        return themes[nextIndex]
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) getCurrentDarkTheme() else getCurrentLightTheme()
        } else {
            prefs.normalModeTheme.getValue()
        }
    }

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        isDarkMode = configuration.isDarkMode()
        // fire all `OnThemeChangedListener`s on theme preferences change
        prefs.registerOnChangeListener(onThemePrefsChange)
        _activeTheme = evaluateActiveTheme()
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        isDarkMode = newConfig.isDarkMode()
        monetThemes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
        // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
        // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
        activeTheme = evaluateActiveTheme()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }

}
