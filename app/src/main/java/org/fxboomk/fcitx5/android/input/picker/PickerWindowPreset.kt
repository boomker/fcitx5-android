/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.picker

/**
 * 三个面板的组装点。
 *
 * 布局参数对应 Foxy 的 catalog 语义：
 * - 符号 / 表情条目短，用多列网格（Foxy 为 6 列）；
 * - 颜文字是 `multiLine` catalog，条目可长达 60+ 字符，Foxy 对 multiLine 用 **1 列**，
 *   这里保持一致，否则 `AutoScaleTextView` 会把长颜文字压到看不清。
 *
 * 面板内部只有左栏底部的 ⌨ / ⌫ 两枚键（与 Foxy 的 `ly.j` / `ly.k` 对应）。
 * **面板之间的跳转不在面板里**，由布局按键（LayoutSwitchKey 的 Symbol / Emoji /
 * Kaomoji 目标）配置，目标名见 [PickerWindow.Key.layerTargetNames]。
 */
fun symbolPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Symbol,
    catalogType = SymbolCatalogType.Symbols,
    columns = 6,
    textSize = 18f
)

fun emojiPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Emoji,
    catalogType = SymbolCatalogType.Emoji,
    columns = 6,
    textSize = 22f,
    policy = EmojiPickerPolicy()
)

fun kaomojiPicker(): PickerWindow = PickerWindow(
    key = PickerWindow.Key.Kaomoji,
    catalogType = SymbolCatalogType.Kaomoji,
    columns = 1,
    textSize = 15f
)
