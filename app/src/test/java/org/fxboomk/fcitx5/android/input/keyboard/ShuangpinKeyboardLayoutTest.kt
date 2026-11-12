/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinKeyboardLayoutTest {
    @Test
    fun layoutsAreRememberedSeparatelyForBuiltinAndCustomSchemes() {
        val xiaohe = "shuangpin-xiaohe"
        val custom = "shuangpin-my-scheme"
        var compact = ShuangpinKeyboardLayouts.updated(emptySet(), xiaohe, ShuangpinKeyboardLayout.Compact18)
        compact = ShuangpinKeyboardLayouts.updated(compact, custom, ShuangpinKeyboardLayout.Compact18)
        assertEquals(ShuangpinKeyboardLayout.Compact18, ShuangpinKeyboardLayouts.read(xiaohe, compact))
        assertEquals(ShuangpinKeyboardLayout.Compact18, ShuangpinKeyboardLayouts.read(custom, compact))
        assertEquals(ShuangpinKeyboardLayout.Full, ShuangpinKeyboardLayouts.read("shuangpin-ms", compact))
        compact = ShuangpinKeyboardLayouts.updated(compact, xiaohe, ShuangpinKeyboardLayout.Full)
        assertEquals(ShuangpinKeyboardLayout.Full, ShuangpinKeyboardLayouts.read(xiaohe, compact))
        assertEquals(setOf(custom), compact)
    }

    @Test
    fun otherInputMethodsCannotActivateGroupedShuangpin() {
        for (ime in listOf("pinyin", "rime", "keyboard-us", "shuangpin-")) {
            assertFalse(ShuangpinKeyboardLayouts.supports(ime))
            assertEquals(ShuangpinKeyboardLayout.Full, ShuangpinKeyboardLayouts.read(ime, setOf(ime)))
            assertEquals(emptySet<String>(), ShuangpinKeyboardLayouts.updated(emptySet(), ime, ShuangpinKeyboardLayout.Compact18))
        }
        assertTrue(ShuangpinKeyboardLayouts.supports("shuangpin"))
        assertTrue(ShuangpinKeyboardLayouts.supports("shuangpin-custom"))
        val compact = setOf("shuangpin-xiaohe")
        fun entry(addon: String) = InputMethodEntry("shuangpin-xiaohe", "", "", "", "", "zh_CN", addon, true)
        assertTrue(ShuangpinKeyboardLayouts.isCompact(entry("pinyin"), compact))
        assertFalse(ShuangpinKeyboardLayouts.isCompact(entry("rime"), compact))
        assertFalse(ShuangpinKeyboardLayouts.isCompact(entry("keyboard"), compact))
        assertFalse(ShuangpinKeyboardLayouts.isCompact(null, compact))
    }

    @Test
    fun groupsCoverEveryLetterAndMicrosoftSemicolonExactlyOnce() {
        assertEquals("q,we,rt,y,u,io,p,a,sd,fg,h,jk,l;,z,xc,v,bn,m", Shuangpin18Keys.groups.joinToString(","))
        val allLetters = Shuangpin18Keys.groups.joinToString("")
        assertEquals(('a'..'z').toList(), allLetters.filter { it != ';' }.toList().sorted())
        assertEquals(1, allLetters.count { it == ';' })
        assertTrue(Shuangpin18Keys.showSemicolon("shuangpin-ms"))
        assertTrue(Shuangpin18Keys.showSemicolon("shuangpin-custom"))
        assertFalse(Shuangpin18Keys.showSemicolon("shuangpin-xiaohe"))
    }

    @Test
    fun tapsSendPrivateGroupCodesAndKeepVirtualState() {
        for (index in Shuangpin18Keys.groups.indices) {
            val action = Shuangpin18Keys.action(index)
            assertEquals(0x0200E000 + index, action.sym.sym)
            assertEquals(index, Shuangpin18Keys.indexOf(action.sym))
            assertTrue(action.states.has(KeyState.Virtual))
            assertFalse(action.states.has(KeyState.Shift))
        }
        assertNull(Shuangpin18Keys.indexOf(org.fxboomk.fcitx5.android.core.KeySym('w'.code)))
    }

    @Test
    fun layoutModeCodesAreVirtualAndOutsideTheGroupCodeRange() {
        for (compact in listOf(false, true)) {
            val action = Shuangpin18Keys.modeAction(compact)
            assertEquals(if (compact) 0x0200E101 else 0x0200E100, action.sym.sym)
            assertTrue(action.states.has(KeyState.Virtual))
            assertFalse(action.states.has(KeyState.Shift))
            assertNull(Shuangpin18Keys.indexOf(action.sym))
        }
    }

    @Test
    fun groupedRowsKeepSourceProportionsAndCapsKeepsASyllableSeparatorSwipe() {
        val rows = Shuangpin18Keys.layout(showLangSwitch = true, semicolon = true)
        assertEquals(listOf(7, 6, 7, 6), rows.map { it.size })
        for (row in rows.take(3)) {
            assertEquals(1f, row.sumOf { it.appearance.percentWidth.toDouble() }.toFloat(), 0.001f)
        }
        assertTrue(rows.last().any { it is SpaceKey })
        assertTrue(rows.last().last() is ReturnKey)
        val groups = rows.flatten().mapNotNull { key ->
            key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().singleOrNull()?.action as? KeyAction.SymAction
        }.filter { Shuangpin18Keys.indexOf(it.sym) != null }
        assertEquals(18, groups.size)
        assertEquals((0..17).toList(), groups.map { Shuangpin18Keys.indexOf(it.sym) })
        val caps = rows[2].first() as CapsKey
        assertEquals(KeyAction.CapsAction(false),
            caps.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        assertEquals("'", caps.swipeLabel)
        assertTrue(caps.appearance.followsPunctuationPosition)
        assertEquals(MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("apostrophe"))))), caps.swipeUp)
        assertEquals(KeyAction.CapsAction(true),
            caps.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single().action)
        assertFalse((Shuangpin18Keys.layout(false, false)[1].last().appearance as KeyDef.Appearance.Text).displayText.contains(';'))
    }
}
