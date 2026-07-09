/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fxboomk.fcitx5.android.R

class SettingsSearchPreference(context: Context) : Preference(context) {
    var query: String = ""
    var onQueryChanged: ((String) -> Unit)? = null

    init {
        key = KEY
        layoutResource = R.layout.preference_settings_search
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val searchView = holder.findViewById(R.id.settings_search_view) as SearchView
        searchView.setQuery(query, false)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                onQueryChanged?.invoke(query.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                onQueryChanged?.invoke(newText.orEmpty())
                return true
            }
        })
    }

    companion object {
        const val KEY = "settings_search"
    }
}
