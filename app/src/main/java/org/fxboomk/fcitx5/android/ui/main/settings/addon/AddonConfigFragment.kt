/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import android.content.Context
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.PreeditStyleScope
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.lazyRoute

class AddonConfigFragment : FcitxPreferenceFragment() {

    companion object {
        private const val PINYIN_ADDON = "pinyin"
        private const val PINYIN_HELPER_ADDON = "pinyinhelper"
        private const val ANDROID_PINYIN_HELPER_OPTION = "AndroidPinyinHelper"
    }

    private val args by lazyRoute<SettingsRoute.AddonConfig>()

    override fun getPageTitle(): String = args.name

    override fun getConfigLayout(context: Context): ConfigLayout? =
        AddonConfigLayouts.get(context, args.uniqueName)

    override fun getPreeditStyleScope(): PreeditStyleScope =
        PreeditStyleScope.addon(args.uniqueName)

    override fun getConfigSectionRoute(
        context: Context,
        sectionIndex: Int
    ): SettingsRoute? = AddonConfigLayouts.get(context, args.uniqueName)
        ?.let { layout ->
            val sectionTitle = layout.sectionTitle(context, sectionIndex)
                ?: layout.fallbackSectionTitle?.let(context::getString)
                ?: return@let null
            if (AddonConfigLayouts.isFuzzySection(layout, sectionIndex)) {
                SettingsRoute.FuzzyConfig(args.name, args.uniqueName, true, sectionTitle)
            } else if (AddonConfigLayouts.isPinyinTextSection(layout, sectionIndex)) {
                SettingsRoute.PinyinTextConfig(sectionTitle)
            } else SettingsRoute.ConfigSection(
                args.name, args.uniqueName, true, sectionIndex, sectionTitle
            )
        }

    private fun RawConfig.appendIfMissing(option: RawConfig) {
        if (findByName(option.name) == null) {
            subItems = (subItems ?: emptyArray()) + option
        }
    }

    private fun RawConfig.appendToTopLevelDescriptionIfMissing(option: RawConfig) {
        val topLevel = subItems?.firstOrNull() ?: return
        if (topLevel.findByName(option.name) == null) {
            topLevel.subItems = (topLevel.subItems ?: emptyArray()) + option
        }
    }

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        val addon = args.uniqueName
        val raw = fcitx.getAddonConfig(addon)
        if (addon == PINYIN_ADDON) {
            val enabled = fcitx.addons()
                .firstOrNull { it.uniqueName == PINYIN_HELPER_ADDON }
                ?.enabled ?: false
            raw.findByName("cfg")?.appendIfMissing(
                RawConfig(ANDROID_PINYIN_HELPER_OPTION, enabled)
            )
            raw.findByName("desc")?.appendToTopLevelDescriptionIfMissing(
                RawConfig(
                    ANDROID_PINYIN_HELPER_OPTION,
                    subItems = arrayOf(
                        RawConfig("Type", "Boolean"),
                        RawConfig(
                            "Description",
                            fcitx.translate("Extra Pinyin functionality", "fcitx5-chinese-addons")
                        )
                    )
                )
            )
        }
        return raw
    }

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        if (args.uniqueName != PINYIN_ADDON) {
            fcitx.setAddonConfig(args.uniqueName, newConfig)
            return
        }
        val helperEnabled = newConfig.findByName(ANDROID_PINYIN_HELPER_OPTION)
            ?.value?.equals("True", ignoreCase = true) ?: false
        // FcitxPreferenceFragment passes only the editable `cfg` subtree here.
        // Keep the synthetic Android-only switch out of the daemon payload.
        val config = newConfig.withoutAndroidPinyinHelperOption()
        fcitx.setAddonConfig(PINYIN_ADDON, config)

        val requiredDependents = if (!helperEnabled) {
            fcitx.getAddonReverseDependencies(PINYIN_HELPER_ADDON)
                .filter { it.second == FcitxAPI.AddonDep.Required }
                .map { it.first }
        } else {
            emptyList()
        }
        val addonNames = arrayOf(PINYIN_HELPER_ADDON, *requiredDependents.toTypedArray())
        fcitx.setAddonState(addonNames, BooleanArray(addonNames.size) { helperEnabled })
    }
}

internal fun RawConfig.withoutAndroidPinyinHelperOption(): RawConfig =
    copy(
        subItems = subItems
            ?.filterNot { it.name == "AndroidPinyinHelper" }
            ?.toTypedArray()
    )
