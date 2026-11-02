/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.picker

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * 符号 / 表情 / 颜文字面板的**呈现风格**。
 *
 * 两种风格共用同一份分组数据、同一套交互（点按上屏、长按弹肤色、最近使用、
 * 自备 catalog、主题配色），差异只在于分组栏摆在哪：
 *
 * - [Sidebar]：分组栏竖在左侧（新版），⌨ / ⌫ 两枚键在分组栏底部；
 * - [TopBar]：分组栏横在顶部（旧版），⌨ / ⌫ 两枚键分居顶栏两端，
 *   分组标签超出宽度时可左右拖动。
 *
 * 之所以做成枚举而不是两个 UI 类：面板的网格、适配器、最近使用、策略过滤
 * 都只有一套实现，拆成两个类会让第三种风格变成三处维护。
 */
enum class SymbolPanelStyle(override val stringRes: Int) : ManagedPreferenceEnum {
    Sidebar(R.string.symbol_panel_style_sidebar),
    TopBar(R.string.symbol_panel_style_top_bar);

    companion object {
        /**
         * 旧版本把这个风格存成 `BottomBar`。枚举改名后旧值会解析失败，
         * 必须继续接受，否则升级用户的偏好会被静默重置。
         */
        private const val LEGACY_TOP_BAR = "BottomBar"

        fun fromStored(raw: String): SymbolPanelStyle = runCatching {
            if (raw == LEGACY_TOP_BAR) TopBar else enumValueOf(raw)
        }.getOrDefault(Sidebar)
    }
}
