/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.annotation.StringRes
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.ui.common.ThemeMultiSelectPreference

class ManagedThemeMultiSelectPreferenceUi(
    @StringRes
    val title: Int,
    key: String,
    val defaultSelected: Set<String> = emptySet(),
    @StringRes
    val summary: Int? = null,
    enableUiOn: (() -> Boolean)? = null
) : ManagedPreferenceUi<ThemeMultiSelectPreference>(key, enableUiOn) {
    override fun createUi(context: Context) = ThemeMultiSelectPreference(context, defaultSelected).apply {
        key = this@ManagedThemeMultiSelectPreferenceUi.key
        isIconSpaceReserved = false
        isSingleLineTitle = false
        if (this@ManagedThemeMultiSelectPreferenceUi.summary != null)
            setSummary(this@ManagedThemeMultiSelectPreferenceUi.summary)
        setTitle(this@ManagedThemeMultiSelectPreferenceUi.title)
    }
}
