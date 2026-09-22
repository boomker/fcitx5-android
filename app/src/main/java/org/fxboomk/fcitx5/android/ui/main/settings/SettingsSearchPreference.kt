/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fxboomk.fcitx5.android.R

class SettingsSearchPreference(context: Context) : Preference(context) {
    var query: String = ""
    var onQueryChanged: ((String) -> Unit)? = null

    private var searchActivated = false

    init {
        key = KEY
        layoutResource = R.layout.preference_settings_search
        isSelectable = false
        isIconSpaceReserved = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val container = holder.itemView as ViewGroup
        val placeholder = container.findViewById<View>(R.id.settings_search_placeholder)
        val searchView = container.findViewById<SearchView>(R.id.settings_search_view)

        container.descendantFocusability = if (searchActivated) {
            ViewGroup.FOCUS_AFTER_DESCENDANTS
        } else {
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        placeholder.visibility = if (searchActivated) View.GONE else View.VISIBLE

        placeholder.setOnClickListener {
            activateSearch(container, placeholder)
        }
        if (searchActivated) {
            bindSearchView(searchView ?: createSearchView(container))
        } else if (searchView != null) {
            searchView.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(searchView.windowToken, 0)
            container.removeView(searchView)
        }
    }

    private fun activateSearch(
        container: ViewGroup,
        placeholder: View
    ) {
        searchActivated = true
        container.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        placeholder.visibility = View.GONE
        val searchView = createSearchView(container)
        bindSearchView(searchView)
        searchView.post {
            val input = searchView.findViewById<View>(androidx.appcompat.R.id.search_src_text)
                ?: return@post
            input.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun deactivateSearch() {
        if (!searchActivated) return
        searchActivated = false
        notifyChanged()
    }

    private fun createSearchView(container: ViewGroup): SearchView {
        return SearchView(context).apply {
            id = R.id.settings_search_view
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                48f,
                resources.displayMetrics
            ).toInt()
            setIconifiedByDefault(false)
            queryHint = context.getString(R.string.settings_search_hint)
            val background = TypedValue()
            if (context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    background,
                    true
                )
            ) {
                setBackgroundResource(background.resourceId)
            }
        }.also(container::addView)
    }

    private fun bindSearchView(searchView: SearchView) {
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
