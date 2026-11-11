/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.content.Context
import androidx.fragment.app.FragmentManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonConfigLayouts
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute

class CustomShuangpinPreferencesFragment : FcitxPreferenceFragment() {
    override val shouldUpdateToolbarTitle = false
    override val preferenceTabIndex: Int = CustomShuangpinFragment.TAB_PREFERENCES
    override val preferenceFragmentManager: FragmentManager
        get() = parentFragment?.parentFragmentManager ?: parentFragmentManager

    override fun getPageTitle(): String =
        getString(R.string.custom_shuangpin_tab_preferences)

    override fun getShuangpinKeyboardInputMethod(): String =
        CustomShuangpinSchemeStore.currentUniqueName()

    override fun getConfigLayout(context: Context): ConfigLayout? =
        AddonConfigLayouts.getForInputMethod(
            context,
            CustomShuangpinSchemeStore.currentUniqueName()
        )

    override fun getConfigSectionRoute(
        context: Context,
        sectionIndex: Int
    ): SettingsRoute? {
        val uniqueName = CustomShuangpinSchemeStore.currentUniqueName()
        val layout = AddonConfigLayouts.getForInputMethod(context, uniqueName) ?: return null
        val sectionTitle = layout.sectionTitle(context, sectionIndex)
            ?: layout.fallbackSectionTitle?.let(context::getString)
            ?: return null
        return if (AddonConfigLayouts.isFuzzySection(layout, sectionIndex)) {
            SettingsRoute.FuzzyConfig(
                getString(R.string.custom_shuangpin_tab_preferences), uniqueName, false, sectionTitle
            )
        } else if (AddonConfigLayouts.isPinyinTextSection(layout, sectionIndex)) {
            SettingsRoute.PinyinTextConfig(sectionTitle)
        } else SettingsRoute.ConfigSection(
            getString(R.string.custom_shuangpin_tab_preferences),
            uniqueName,
            false,
            sectionIndex,
            sectionTitle
        )
    }

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        fcitx.getImConfig(CustomShuangpinSchemeStore.currentUniqueName())

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(CustomShuangpinSchemeStore.currentUniqueName(), newConfig)
    }
}
