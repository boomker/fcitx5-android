/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.utils

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceCategory

/**
 * A preference group whose header can be clicked to collapse or expand its
 * children. The state is stored in SharedPreferences per [stateKey], so it
 * survives fragment recreation and app restarts.
 */
class CollapsiblePreferenceCategory(
    context: Context,
    private val stateKey: String,
    initiallyCollapsed: Boolean = false
) : PreferenceCategory(context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var baseTitle: CharSequence? = null

    var isCollapsed: Boolean = preferences.getBoolean(stateKey, initiallyCollapsed)
        private set

    init {
        isPersistent = false
        isSelectable = true
        isSingleLineTitle = false
        isIconSpaceReserved = false
        setOnPreferenceClickListener {
            setCollapsed(!isCollapsed)
            true
        }
    }

    // PreferenceCategory is disabled by default so headers do not react to
    // clicks. A collapsible header must be enabled for performClick() to fire.
    override fun isEnabled(): Boolean = true

    fun setCollapsibleTitle(title: CharSequence?) {
        baseTitle = title
        updateDisplayedTitle()
    }

    fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed
        preferences.edit().putBoolean(stateKey, collapsed).apply()
        applyCollapsedState()
    }

    override fun onPrepareAddPreference(preference: Preference): Boolean {
        val result = super.onPrepareAddPreference(preference)
        preference.isVisible = !isCollapsed
        return result
    }

    private fun applyCollapsedState() {
        for (index in 0 until preferenceCount) {
            getPreference(index).isVisible = !isCollapsed
        }
        updateDisplayedTitle()
    }

    private fun updateDisplayedTitle() {
        val title = baseTitle ?: return
        super.setTitle(if (isCollapsed) "$title ▸" else "$title ▾")
    }

    private companion object {
        const val PREFS_NAME = "collapsible_preference_state"
    }
}
