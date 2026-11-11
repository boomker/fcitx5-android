/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.content.Context
import androidx.preference.ListPreference
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts

internal fun shuangpinKeyboardLayoutPreference(context: Context, uniqueName: String): ListPreference? {
    if (!ShuangpinKeyboardLayouts.supports(uniqueName)) return null
    return ListPreference(context).apply {
        key = "shuangpin_keyboard_layout"
        isPersistent = false
        setTitle(R.string.shuangpin_keyboard_layout)
        setDialogTitle(R.string.shuangpin_keyboard_layout)
        entries = arrayOf(
            context.getString(R.string.shuangpin_keyboard_layout_full),
            context.getString(R.string.shuangpin_keyboard_layout_compact18),
        )
        entryValues = ShuangpinKeyboardLayout.entries.map { it.name }.toTypedArray()
        value = ShuangpinKeyboardLayouts.read(uniqueName).name
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, newValue ->
            val layout = ShuangpinKeyboardLayout.entries.firstOrNull { it.name == newValue }
                ?: return@setOnPreferenceChangeListener false
            ShuangpinKeyboardLayouts.write(uniqueName, layout)
            true
        }
    }
}
