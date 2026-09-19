/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils

import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles

/**
 * 布局配置（文件级）的自定义展示顺序，供布局管理页与键盘内快速切换弹窗共用。
 *
 * 顺序持久化在 AppPrefs 中（换行分隔）；默认配置固定首位，
 * 未记录顺序的配置按名称排序追加。
 */
object TextKeyboardLayoutProfileOrder {

    /** 持久化的配置顺序（换行分隔），忽略空项与默认配置。 */
    fun stored(): List<String> =
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileOrder.getValue()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }

    fun update(transform: (MutableList<String>) -> Unit) {
        val order = stored().toMutableList()
        transform(order)
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileOrder
            .setValue(order.joinToString("\n"))
    }

    /**
     * 配置展示顺序：默认配置固定首位，其余按持久化顺序排列，未记录的配置按名称追加；
     * [currentProfile] 不在列表中时也会补入。
     */
    fun ordered(currentProfile: String?): List<String> {
        val default = UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableList()
        if (currentProfile != null && currentProfile !in profiles) profiles.add(currentProfile)
        val known = profiles.distinct().filter { it != default }
        val ordered = stored().filter { it in known }.distinct()
        val rest = known.filter { it !in ordered }.sorted()
        return listOf(default) + ordered + rest
    }
}
