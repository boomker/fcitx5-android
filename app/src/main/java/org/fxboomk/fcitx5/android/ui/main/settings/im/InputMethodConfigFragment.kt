/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.content.Context
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.PreeditStyleScope
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonConfigLayouts
import org.fxboomk.fcitx5.android.utils.lazyRoute

class InputMethodConfigFragment : FcitxPreferenceFragment() {
    val args by lazyRoute<SettingsRoute.InputMethodConfig>()

    override fun getPageTitle(): String = args.name

    override fun getShuangpinKeyboardInputMethod(): String = args.uniqueName

    override fun getConfigLayout(context: Context): ConfigLayout? =
        AddonConfigLayouts.getForInputMethod(context, args.uniqueName)

    override fun getPreeditStyleScope(): PreeditStyleScope? =
        AddonConfigLayouts.engineAddonForInputMethod(args.uniqueName)
            ?.let { PreeditStyleScope.forInputMethod(args.uniqueName, it) }

    override fun getConfigSectionRoute(
        context: Context,
        sectionIndex: Int
    ): SettingsRoute? = AddonConfigLayouts.getForInputMethod(context, args.uniqueName)
        ?.let { layout ->
            val sectionTitle = layout.sectionTitle(context, sectionIndex)
                ?: layout.fallbackSectionTitle?.let(context::getString)
                ?: return@let null
            if (AddonConfigLayouts.isFuzzySection(layout, sectionIndex)) {
                SettingsRoute.FuzzyConfig(args.name, args.uniqueName, false, sectionTitle)
            } else if (AddonConfigLayouts.isPinyinTextSection(layout, sectionIndex)) {
                SettingsRoute.PinyinTextConfig(sectionTitle)
            } else SettingsRoute.ConfigSection(
                args.name, args.uniqueName, false, sectionIndex, sectionTitle
            )
        }

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        return fcitx.getImConfig(args.uniqueName)
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        fcitx.setImConfig(args.uniqueName, newConfig)
    }
}
