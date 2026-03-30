/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.theme.ResponsiveThemeListView
import org.fcitx.fcitx5.android.ui.main.settings.theme.SimpleThemeListAdapter
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi

/**
 * A preference that allows multi-selection of themes with checkboxes.
 */
class ThemeMultiSelectPreference(
    context: Context,
    private val defaultSelected: Set<String> = emptySet()
) : Preference(context) {

    private var selectedThemes: Set<String> = defaultSelected

    init {
        setDefaultValue(defaultSelected)
    }

    private val currentSelectedNames: Set<String>
        get() = try {
            getPersistedString(defaultSelected.joinToString("|")).let {
                if (it.isBlank()) emptySet() else it.split("|").toSet()
            }
        } catch (e: Exception) {
            defaultSelected
        }

    override fun onClick() {
        showMultiSelectDialog()
    }

    private fun showMultiSelectDialog() {
        val view = ResponsiveThemeListView(context).apply {
            minimumHeight = Int.MAX_VALUE
        }
        val allThemes = ThemeManager.getAllThemes()
        val adapter = MultiSelectThemeAdapter(allThemes, currentSelectedNames)
        view.adapter = adapter

        AlertDialog.Builder(context)
            .setTitle(title)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newSelected = adapter.getSelectedThemeNames()
                if (callChangeListener(newSelected)) {
                    persistString(newSelected.joinToString("|"))
                    selectedThemes = newSelected
                    notifyChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setView(view)
            .show()
    }

    fun getSelectedThemes(): Set<String> = selectedThemes

    class MultiSelectThemeAdapter(
        private val themes: List<Theme>,
        initialSelected: Set<String>
    ) : SimpleThemeListAdapter<Theme>(themes) {

        private val selectedSet = initialSelected.toMutableSet()

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            (holder.ui as ThemeThumbnailUi).apply {
                val theme = themes[position]
                setTheme(theme)
                editButton.visibility = View.GONE
                setChecked(theme.name in selectedSet)
                root.setOnClickListener {
                    toggleSelection(theme.name)
                    setChecked(theme.name in selectedSet)
                }
            }
        }

        private fun toggleSelection(themeName: String) {
            if (themeName in selectedSet) {
                selectedSet.remove(themeName)
            } else {
                selectedSet.add(themeName)
            }
        }

        fun getSelectedThemeNames(): Set<String> = selectedSet.toSet()
    }
}
