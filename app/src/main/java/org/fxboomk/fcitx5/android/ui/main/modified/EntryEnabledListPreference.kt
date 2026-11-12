/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.modified

import android.content.Context
import androidx.preference.ListPreference

class EntryEnabledListPreference(
    context: Context,
    val defaultEntryValue: String,
    private val entryEnabled: (String) -> Boolean
) : ListPreference(context) {
    fun isEntryEnabled(value: String): Boolean = entryEnabled(value)

    override fun callChangeListener(newValue: Any?): Boolean =
        newValue is String && isEntryEnabled(newValue) && super.callChangeListener(newValue)
}
