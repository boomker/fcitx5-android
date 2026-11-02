/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.picker

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `symbol_panel_style` 已写进用户偏好，枚举改名后必须继续能读旧值。
 *
 * 旧版本把「顶部分组栏」这个风格存成 `BottomBar`；若这里退化成默认值，
 * 升级用户的面板风格会被静默重置。
 */
class SymbolPanelStyleDecodeTest {

    @Test
    fun legacyBottomBarValueDecodesToTopBar() {
        assertEquals(SymbolPanelStyle.TopBar, SymbolPanelStyle.fromStored("BottomBar"))
    }

    @Test
    fun currentNamesDecodeAsThemselves() {
        assertEquals(SymbolPanelStyle.Sidebar, SymbolPanelStyle.fromStored("Sidebar"))
        assertEquals(SymbolPanelStyle.TopBar, SymbolPanelStyle.fromStored("TopBar"))
    }

    @Test
    fun unknownValueFallsBackToDefaultInsteadOfCrashing() {
        assertEquals(SymbolPanelStyle.Sidebar, SymbolPanelStyle.fromStored("no-such-style"))
        assertEquals(SymbolPanelStyle.Sidebar, SymbolPanelStyle.fromStored(""))
    }
}
