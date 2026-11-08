/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class WubiConfigLayoutTest {
    @Test
    fun lookupShuangpinNamesAreSortedWithoutChangingTheirValues() {
        val entries = listOf(
            "Do not display" to "不要显示",
            "Ziranma" to "自然码",
            "MS" to "微软",
            "Ziguang" to "紫光",
            "ABC" to "智能ABC",
            "Zhongwenzhixing" to "中文之星",
            "PinyinJiajia" to "拼音加加",
            "Xiaohe" to "小鹤",
            "Custom" to "自定义"
        )
        val transform = WubiConfigLayout.getGlobal().enumEntryTransforms.getValue("ShuangpinProfile")

        assertEquals(
            listOf(
                "Do not display" to "不要显示",
                "MS" to "微软",
                "Ziguang" to "紫光",
                "Xiaohe" to "小鹤",
                "Ziranma" to "自然码",
                "PinyinJiajia" to "拼音加加",
                "ABC" to "智能ABC",
                "Custom" to "自定义"
            ),
            transform(entries)
        )
    }

    @Test
    fun lookupShuangpinSortUsesLocalizedLabels() {
        val entries = listOf(
            "Do not display" to "Off",
            "Ziranma" to "A",
            "MS" to "Longer name",
            "Custom" to "Custom",
            "Zhongwenzhixing" to "B"
        )
        val transform = WubiConfigLayout.getGlobal().enumEntryTransforms.getValue("ShuangpinProfile")

        assertEquals(listOf(entries[0], entries[1], entries[2], entries[3]), transform(entries))
    }

    @Test
    fun allNativeTableOptionsHaveExactlyOneTheme() {
        val root = File("..").takeIf { File(it, "lib").isDirectory } ?: File(".")
        val header = File(
            root,
            "lib/fcitx5-chinese-addons/src/main/cpp/fcitx5-chinese-addons/im/table/ime.h"
        ).readText().substringAfter("TableConfig,").substringBefore("FCITX_CONFIGURATION(PartialIMInfo")
        val nativeOptions = Regex("(?:\\bthis\\s*,|\\.path\\s*=)\\s*\"([^\"]+)\"")
            .findAll(header).map { it.groupValues[1] }.toSet()
        val groupedOptions = WubiConfigLayout.get().sections.flatMap { it.options }

        assertEquals(nativeOptions, groupedOptions.toSet())
        assertEquals("An option must not appear in multiple themes", groupedOptions.size, groupedOptions.toSet().size)
    }
}
