/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.preview

import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewInputMethodEntryTest {

    @Test
    fun `rime layout preview uses rime engine identity even when active ime is builtin shuangpin`() {
        // 当前活动输入法是内置双拼，其 label 为 "双"
        val shuangpin = inputMethod(
            uniqueName = "pinyin",
            name = "拼音",
            icon = "fcitx-pinyin",
            label = "双",
            addon = "pinyin",
        )

        val preview = PreviewInputMethodEntry.create(
            layoutName = "rime",
            base = shuangpin,
            displayName = "Rime",
        )

        // 编辑 Rime 输入方案时，空格键应显示 Rime 引擎标识 "㞢"，而非继承的 "双"
        assertEquals("㞢", preview.label)
        assertEquals("rime", preview.addon)
        assertEquals("fcitx-rime", preview.icon)
    }

    @Test
    fun `rime layout name is case insensitive`() {
        val shuangpin = inputMethod(
            uniqueName = "pinyin",
            name = "拼音",
            icon = "fcitx-pinyin",
            label = "双",
            addon = "pinyin",
        )

        val preview = PreviewInputMethodEntry.create(
            layoutName = "Rime",
            base = shuangpin,
        )

        assertEquals("㞢", preview.label)
        assertEquals("rime", preview.addon)
        assertEquals("fcitx-rime", preview.icon)
    }

    @Test
    fun `non-rime layout preview keeps the base engine identity`() {
        val shuangpin = inputMethod(
            uniqueName = "pinyin",
            name = "拼音",
            icon = "fcitx-pinyin",
            label = "双",
            addon = "pinyin",
        )

        val preview = PreviewInputMethodEntry.create(
            layoutName = "qwerty",
            base = shuangpin,
            displayName = "English",
        )

        // 非 Rime 布局保留原有身份字段
        assertEquals("双", preview.label)
        assertEquals("pinyin", preview.addon)
        assertEquals("fcitx-pinyin", preview.icon)
        assertEquals("qwerty", preview.uniqueName)
        assertEquals("English", preview.name)
    }

    @Test
    fun `rime layout preview without base still carries rime identity`() {
        val preview = PreviewInputMethodEntry.create(layoutName = "rime")

        assertEquals("㞢", preview.label)
        assertEquals("rime", preview.addon)
        assertEquals("fcitx-rime", preview.icon)
        // 无 base 时走单参构造：name 取 layoutName，uniqueName 为空
        assertEquals("rime", preview.name)
    }

    @Test
    fun `submode label is applied on top of rime identity`() {
        val shuangpin = inputMethod(
            uniqueName = "pinyin",
            name = "拼音",
            icon = "fcitx-pinyin",
            label = "双",
            addon = "pinyin",
        )

        val preview = PreviewInputMethodEntry.create(
            layoutName = "rime",
            subModeLabel = "朙月拼音",
            base = shuangpin,
        )

        assertEquals("㞢", preview.label)
        assertEquals("朙月拼音", preview.subMode.label)
        assertEquals("朙月拼音", preview.subMode.name)
    }

    private fun inputMethod(
        uniqueName: String,
        name: String,
        icon: String,
        label: String,
        addon: String,
    ) = InputMethodEntry(
        uniqueName = uniqueName,
        name = name,
        icon = icon,
        nativeName = name,
        label = label,
        languageCode = "zh",
        addon = addon,
        isConfigurable = false,
    )
}
