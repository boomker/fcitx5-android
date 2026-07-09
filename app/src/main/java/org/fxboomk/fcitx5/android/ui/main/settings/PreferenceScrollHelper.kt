/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.fxboomk.fcitx5.android.ui.main.MainViewModel

object PreferenceScrollHelper {

    fun scrollToPendingPreference(
        fragment: PreferenceFragmentCompat,
        viewModel: MainViewModel
    ) {
        val key = viewModel.peekPendingPreferenceScrollKey() ?: return
        fragment.findPreference<Preference>(key) ?: return
        fragment.listView.post {
            fragment.scrollToPreference(key)
            viewModel.consumePendingPreferenceScrollKey(key)
        }
    }
}
