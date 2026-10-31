/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fxboomk.fcitx5.android.ui.main.settings.theme.MonetThemePrefs
import org.fxboomk.fcitx5.android.utils.WeakHashSet
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.isDarkMode
import org.fxboomk.fcitx5.android.utils.userManager

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun interface OnThemeListChangeListener {
        fun onThemeListChange(themes: List<Theme>)
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
    ) + BundledThemes.loadBuiltins(
        listAssets = { appContext.assets.list(it)?.toList().orEmpty() },
        openAsset = { appContext.assets.open(it) }
    )

    val DefaultTheme = ThemePreset.PixelDark

    private var monetThemes = defaultMonetThemes()

    private fun defaultMonetThemes(): List<Theme.Monet> {
        return listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
    }

    private fun loadMonetThemes(): List<Theme.Monet> {
        if (!appContext.userManager.isUserUnlocked) {
            return defaultMonetThemes()
        }
        // 检查是否存在自定义映射配置
        val lightMapping = MonetThemePrefs.getMapping("MonetLight")
        val darkMapping = MonetThemePrefs.getMapping("MonetDark")
        
        val lightTheme = if (lightMapping != null) {
            ThemeMonet.createFromMapping(isDark = false, mapping = lightMapping)
        } else {
            ThemeMonet.getLight()
        }
        
        val darkTheme = if (darkMapping != null) {
            ThemeMonet.createFromMapping(isDark = true, mapping = darkMapping)
        } else {
            ThemeMonet.getDark()
        }
        
        return listOf(lightTheme, darkTheme)
    }

    private val customThemes: MutableList<Theme.Custom> = ThemeFilesManager.listThemes()

    fun getTheme(name: String) =
        customThemes.find { it.name == name }
            ?: monetThemes.find { it.name == name }
            ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes + monetThemes + BuiltinThemes

    fun refreshThemes() {
        customThemes.clear()
        customThemes.addAll(ThemeFilesManager.listThemes())
        monetThemes = loadMonetThemes()
        activeTheme = evaluateActiveTheme()
        fireThemeListChange()
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
    private val onThemeListChangeListeners = WeakHashSet<OnThemeListChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    fun addOnThemeListChangedListener(listener: OnThemeListChangeListener) {
        onThemeListChangeListeners.add(listener)
    }

    fun removeOnThemeListChangedListener(listener: OnThemeListChangeListener) {
        onThemeListChangeListeners.remove(listener)
    }

    private fun fireChange() {
        val theme = _activeTheme
        dispatchOnMain {
            onChangeListeners.toList().forEach { it.onThemeChange(theme) }
        }
    }

    private fun fireThemeListChange() {
        val themes = getAllThemes().toList()
        dispatchOnMain {
            onThemeListChangeListeners.toList().forEach { it.onThemeListChange(themes) }
        }
    }

    private inline fun dispatchOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
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
        fireThemeListChange()
    }

    fun nonActiveImportName(name: String): String {
        if (activeTheme.name != name || getTheme(name) !is Theme.Custom) return name
        val base = "$name (imported)"
        if (getTheme(base) == null) return base
        var index = 2
        while (true) {
            val candidate = "$base $index"
            if (getTheme(candidate) == null) return candidate
            index++
        }
    }

    fun deleteTheme(name: String) {
        customThemes.find { it.name == name }?.also { theme ->
            // Pass all themes except the one being deleted, so we can clean up unused directories.
            // Remove every in-memory duplicate as well; stale files can otherwise leave a card behind.
            val otherThemes = customThemes.filter { it.name != name }
            ThemeFilesManager.deleteThemeFiles(theme, otherThemes)
            customThemes.removeAll { it.name == name }
        }
        if (activeTheme.name == name) {
            activeTheme = evaluateActiveTheme()
        }
        fireThemeListChange()
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
     * Whether the theme belongs to either the light or the dark mode theme pool.
     */
    fun isThemeInAnyPool(name: String): Boolean =
        prefs.lightModeThemes.isSelected(name) || prefs.darkModeThemes.isSelected(name)

    /**
     * Toggle the theme's pool membership: if it is in either pool, remove it from
     * both; otherwise add it to the pool matching its day/night variant.
     * @return whether the theme is in a pool after toggling
     */
    fun toggleThemePoolMembership(theme: Theme): Boolean {
        val remove = isThemeInAnyPool(theme.name)
        val allThemes = getAllThemes()
        // Commit both pools and their indices together: preference listeners must
        // never evaluate a new pool against an index from its previous ordering.
        prefs.lightModeThemes.sharedPreferences.edit {
            listOf(
                prefs.lightModeThemes to prefs.currentLightThemeIndex,
                prefs.darkModeThemes to prefs.currentDarkThemeIndex
            ).forEachIndexed { mode, (pool, index) ->
                if (!remove && theme.isDark != (mode == 1)) return@forEachIndexed
                val names = pool.getValue()
                val previousThemes = allThemes.filter { it.name in names }
                val previousIndex = index.getValue()
                    .coerceIn(0, (previousThemes.size - 1).coerceAtLeast(0))
                val selectedName = previousThemes.getOrNull(previousIndex)?.name
                val updatedNames = if (remove) names - theme.name else names + theme.name
                val updatedThemes = allThemes.filter { it.name in updatedNames }
                val selectedIndex = updatedThemes.indexOfFirst { it.name == selectedName }
                // If the selected theme was removed, use its next neighbour, or
                // the last remaining theme. Empty pools reset to index zero.
                val updatedIndex = if (selectedIndex >= 0) selectedIndex else
                    previousIndex.coerceAtMost((updatedThemes.size - 1).coerceAtLeast(0))
                putString(pool.key, ManagedThemeSetPreference.encodeThemes(updatedNames))
                putInt(index.key, updatedIndex)
            }
        }
        return !remove
    }

    /**
     * Get the currently selected light theme.
     *
     * The stored index is used in both manual and follow-system modes so that
     * reevaluating the active theme does not unexpectedly select another theme.
     */
    fun getCurrentLightTheme(): Theme {
        val themes = prefs.lightModeThemes.getThemes()
        if (themes.isEmpty()) return ThemePreset.PixelLight
        val index = prefs.currentLightThemeIndex.getValue().coerceIn(0, themes.size - 1)
        return themes[index]
    }

    /**
     * Get the currently selected dark theme.
     *
     * The stored index is used in both manual and follow-system modes so that
     * reevaluating the active theme does not unexpectedly select another theme.
     */
    fun getCurrentDarkTheme(): Theme {
        val themes = prefs.darkModeThemes.getThemes()
        if (themes.isEmpty()) return ThemePreset.PixelDark
        val index = prefs.currentDarkThemeIndex.getValue().coerceIn(0, themes.size - 1)
        return themes[index]
    }

    /**
     * Toggle between light and dark mode themes, cycling to the next theme in the target mode.
     * When following system day/night mode, the system mode decides the target pool:
     * dark mode cycles within dark themes only, light mode within light themes only.
     */
    fun toggleConfiguredDayNightTheme(): Theme {
        val nextTheme = if (prefs.followSystemDayNightTheme.getValue()) {
            // Following system: never leave the current system mode's theme pool
            if (isDarkMode) cycleToNextDarkTheme() else cycleToNextLightTheme()
        } else if (isUsingConfiguredDarkTheme()) {
            // Currently dark, switch to light (cycle light themes)
            cycleToNextLightTheme()
        } else {
            // Currently light or not in either list, switch to dark (cycle dark themes)
            cycleToNextDarkTheme()
        }
        _activeTheme = nextTheme
        prefs.normalModeTheme.setValue(nextTheme)
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
        val rawTheme = if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) getCurrentDarkTheme() else getCurrentLightTheme()
        } else {
            prefs.normalModeTheme.getValue()
        }
        return normalizeThemeKeyAreaColors(rawTheme)
    }

    private fun normalizeThemeKeyAreaColors(theme: Theme): Theme {
        val mainOpacity = normalizeOpacityPercent(
            prefs.gboardMainKeyOpacity.getValue(),
            ThemePrefs.DefaultMainKeyOpacity
        )
        val nonMainOpacity = normalizeOpacityPercent(
            prefs.gboardNonMainKeyOpacity.getValue(),
            ThemePrefs.DefaultNonMainKeyOpacity
        )
        // 100% means "keep the theme's own alpha": imported themes may rely on
        // translucent key colors (e.g. keys over a gradient background), forcing
        // full opacity there destroys the design.
        if (mainOpacity >= 100 && nonMainOpacity >= 100) return theme

        val mainKeyColor =
            if (mainOpacity >= 100) theme.keyBackgroundColor
            else withOpacity(theme.keyBackgroundColor, mainOpacity)
        val nonMainKeyColor =
            if (nonMainOpacity >= 100) theme.altKeyBackgroundColor
            else withOpacity(theme.altKeyBackgroundColor, nonMainOpacity)
        val spaceBarColor =
            if (mainOpacity >= 100) theme.spaceBarColor
            else withOpacity(theme.spaceBarColor, mainOpacity)
        val clipboardColor =
            if (mainOpacity >= 100) theme.clipboardEntryColor
            else withOpacity(theme.clipboardEntryColor, mainOpacity)
        return when (theme) {
            is Theme.Builtin -> theme.copy(
                keyBackgroundColor = mainKeyColor,
                altKeyBackgroundColor = nonMainKeyColor,
                spaceBarColor = spaceBarColor,
                clipboardEntryColor = clipboardColor
            )

            is Theme.Custom -> theme.copy(
                keyBackgroundColor = mainKeyColor,
                altKeyBackgroundColor = nonMainKeyColor,
                spaceBarColor = spaceBarColor,
                clipboardEntryColor = clipboardColor
            )

            is Theme.Monet -> theme.copy(
                keyBackgroundColor = mainKeyColor,
                altKeyBackgroundColor = nonMainKeyColor,
                spaceBarColor = spaceBarColor,
                clipboardEntryColor = clipboardColor
            )
        }
    }

    private fun normalizeOpacityPercent(rawValue: Int, defaultValue: Int): Int {
        return when {
            rawValue in 0..100 -> rawValue
            rawValue in -1..255 -> {
                // Migration for previous "tone" values.
                val tone = if (rawValue < 0) 255 else rawValue
                (tone * 100 / 255).coerceIn(0, 100)
            }

            else -> defaultValue
        }
    }

    private fun withOpacity(color: Int, opacityPercent: Int): Int {
        val alpha = (opacityPercent.coerceIn(0, 100) * 255 / 100)
        return ColorUtils.setAlphaComponent(color, alpha)
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
        // 重新加载 Monet 主题（包括自定义映射）
        monetThemes = loadMonetThemes()
        // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
        // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
        activeTheme = evaluateActiveTheme()
        fireThemeListChange()
    }

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
