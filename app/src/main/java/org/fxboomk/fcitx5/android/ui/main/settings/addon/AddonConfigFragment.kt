/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.lazyRoute

class AddonConfigFragment : FcitxPreferenceFragment() {
    companion object {
        private const val tableAddon = "table"
        private const val pinyinAddon = "pinyin"
        private const val pinyinHelperAddon = "pinyinhelper"
        private const val androidTableOption = "AndroidTable"
        private const val androidPinyinHelperOption = "AndroidPinyinHelper"
    }

    private val args by lazyRoute<SettingsRoute.AddonConfig>()

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

    override fun getPageTitle(): String = args.name

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig {
        val addon = args.uniqueName
        val raw = fcitx.getAddonConfig(addon)
        if (addon == tableAddon) {
            // append android specific "Manage Table Input Methods" to config of table addon
            raw.findByName("desc")?.findByName("TableGlobalConfig")?.let {
                it.subItems = (it.subItems ?: emptyArray()) + RawConfig(
                    androidTableOption, subItems = arrayOf(
                        RawConfig("Type", "External"),
                        RawConfig("Description", getString(R.string.manage_table_im))
                    )
                )
            }
        } else if (addon == pinyinAddon) {
            val enabled = fcitx.addons().firstOrNull { it.uniqueName == pinyinHelperAddon }?.enabled ?: false
            raw.findByName("cfg")?.appendIfMissing(RawConfig(androidPinyinHelperOption, enabled))
            raw.findByName("desc")?.appendToTopLevelDescriptionIfMissing(
                RawConfig(
                    androidPinyinHelperOption, subItems = arrayOf(
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
        if (args.uniqueName == pinyinAddon) {
            val cfg = newConfig.findByName("cfg")
            val pinyinHelperEnabled = cfg?.findByName(androidPinyinHelperOption)?.value?.toBoolean() ?: false
            val config = newConfig.copy(
                subItems = newConfig.subItems?.map { item ->
                    when (item.name) {
                        "cfg" -> item.copy(
                            subItems = item.subItems
                                ?.filterNot { it.name == androidPinyinHelperOption }
                                ?.toTypedArray()
                        )
                        "desc" -> item.copy(
                            subItems = item.subItems?.mapIndexed { index, descItem ->
                                if (index == 0) {
                                    descItem.copy(
                                        subItems = descItem.subItems
                                            ?.filterNot { it.name == androidPinyinHelperOption }
                                            ?.toTypedArray()
                                    )
                                } else {
                                    descItem
                                }
                            }?.toTypedArray()
                        )
                        else -> item
                    }
                }?.toTypedArray()
            )
            fcitx.setAddonConfig(args.uniqueName, config)

            val requiredDependents = if (!pinyinHelperEnabled) {
                fcitx.getAddonReverseDependencies(pinyinHelperAddon)
                    .filter { it.second == FcitxAPI.AddonDep.Required }
                    .map { it.first }
            } else {
                emptyList()
            }
            val addonNames = arrayOf(pinyinHelperAddon, *requiredDependents.toTypedArray())
            val addonStates = BooleanArray(addonNames.size) { pinyinHelperEnabled }
            fcitx.setAddonState(addonNames, addonStates)
            return
        }
        fcitx.setAddonConfig(args.uniqueName, newConfig)
    }
}
