/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import androidx.fragment.app.FragmentManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment

class CustomShuangpinPreferencesFragment : FcitxPreferenceFragment() {
    override val shouldUpdateToolbarTitle = false
    override val preferenceFragmentManager: FragmentManager
        get() = parentFragment?.parentFragmentManager ?: parentFragmentManager

    override fun getPageTitle(): String =
        getString(R.string.custom_shuangpin_tab_preferences)

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        fcitx.getImConfig(CustomShuangpinSchemeStore.UNIQUE_NAME)

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(CustomShuangpinSchemeStore.UNIQUE_NAME, newConfig)
    }
}
