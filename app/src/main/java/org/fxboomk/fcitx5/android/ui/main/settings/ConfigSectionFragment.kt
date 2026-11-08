/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonConfigLayouts
import org.fxboomk.fcitx5.android.utils.lazyRoute

/** Displays a single group from an addon or input-method config. */
class ConfigSectionFragment : FcitxPreferenceFragment() {
    private val args by lazyRoute<SettingsRoute.ConfigSection>()

    override fun getPageTitle(): String = args.sectionTitle

    override fun getConfigLayout(context: Context): ConfigLayout? =
        if (args.isAddon) {
            AddonConfigLayouts.get(context, args.uniqueName)
        } else {
            AddonConfigLayouts.getForInputMethod(context, args.uniqueName)
        }

    override fun getPreeditStyleScope(): PreeditStyleScope? =
        if (args.isAddon) {
            PreeditStyleScope.addon(args.uniqueName)
        } else {
            AddonConfigLayouts.engineAddonForInputMethod(args.uniqueName)
                ?.let { PreeditStyleScope.forInputMethod(args.uniqueName, it) }
        }

    override fun getConfigSection(): Int = args.sectionIndex

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        if (args.isAddon) fcitx.getAddonConfig(args.uniqueName)
        else fcitx.getImConfig(args.uniqueName)

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        if (args.isAddon) fcitx.setAddonConfig(args.uniqueName, newConfig)
        else fcitx.setImConfig(args.uniqueName, newConfig)
    }
}
