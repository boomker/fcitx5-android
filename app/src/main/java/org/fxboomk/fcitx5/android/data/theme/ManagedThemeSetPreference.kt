/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference

/**
 * A preference that stores a set of theme names as a pipe-delimited string.
 */
class ManagedThemeSetPreference(
    sharedPreferences: SharedPreferences,
    key: String,
    defaultValue: Set<String> = emptySet()
) : ManagedPreference<Set<String>>(sharedPreferences, key, defaultValue) {

    companion object {
        private const val DELIMITER = "|"

        fun encodeThemes(themes: Set<String>): String = themes.joinToString(DELIMITER)

        fun decodeThemes(raw: String?): Set<String> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(DELIMITER).filter { it.isNotBlank() }.toSet()
        }
    }

    override fun setValue(value: Set<String>) {
        sharedPreferences.edit { putString(key, encodeThemes(value)) }
    }

    override fun getValue(): Set<String> {
        return try {
            sharedPreferences.getString(key, null)?.let { decodeThemes(it) } ?: defaultValue
        } catch (e: Exception) {
            setValue(defaultValue)
            defaultValue
        }
    }

    override fun putValueTo(editor: SharedPreferences.Editor) {
        editor.putString(key, encodeThemes(getValue()))
    }

    fun addTheme(themeName: String): Boolean {
        val current = getValue().toMutableSet()
        val added = current.add(themeName)
        if (added) setValue(current)
        return added
    }

    fun removeTheme(themeName: String): Boolean {
        val current = getValue().toMutableSet()
        val removed = current.remove(themeName)
        if (removed) setValue(current)
        return removed
    }

    fun isSelected(themeName: String): Boolean = themeName in getValue()

    fun getThemes(): List<Theme> {
        val themeNames = getValue()
        return ThemeManager.getAllThemes().filter { it.name in themeNames }
    }

    fun getFirstTheme(): Theme? = getThemes().firstOrNull()
}
