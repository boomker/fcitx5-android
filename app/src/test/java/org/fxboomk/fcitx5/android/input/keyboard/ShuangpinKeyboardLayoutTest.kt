/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinScheme
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeCodec
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinKeyboardLayoutTest {
    @Test
    fun sixColumnLayoutKeepsAllGroupActionsAndRoomForSpace() {
        for (languageKey in listOf(false, true)) {
            val layout = Shuangpin18Keys.layoutSixColumns(languageKey, true)
            assertEquals(4, layout.size)
            assertEquals(listOf(6, 6, 6), layout.take(3).map { it.size })
            val keys = layout.take(3).flatten().filterIsInstance<ShuangpinGroupKey>()
            assertEquals(Shuangpin18Keys.groups, keys.map { it.groupId })
            keys.forEachIndexed { index, key ->
                assertEquals(1f / 6, key.appearance.percentWidth, 0f)
                val action = key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
                    as KeyAction.SymAction
                assertEquals(index, Shuangpin18Keys.indexOf(action.sym))
            }
            val functions = layout.last()
            assertTrue(functions.any { it is CapsKey && it.composeOverride != null })
            assertTrue(functions.any { it is BackspaceKey })
            assertTrue(functions.any { it is SpaceKey && it.appearance.percentWidth == 0f })
            assertTrue(functions.sumOf { it.appearance.percentWidth.toDouble() } < 0.8)
            assertEquals(languageKey, functions.any { it is LanguageKey })
        }
    }

    @Test
    fun compactValidationAcceptsReorderingAndDuplicatesButNamesMissingGroups() {
        val rows = Shuangpin18Keys.layoutSixColumns(false, true)
            .map { row -> row.map(LayoutJsonUtils::keyDefToJson).toMutableList() }.toMutableList()
        rows[0].reverse()
        rows[2].add(rows[0][0].toMutableMap())
        assertTrue(ShuangpinLayoutVariants.validationErrors(rows).isEmpty())
        rows[1].removeAll { it["groupId"] == "h" }
        assertTrue(ShuangpinLayoutVariants.validationErrors(rows).any { it.contains("h") })
        rows[0][0]["groupId"] = "wq"
        rows[0][0]["weight"] = Float.NaN
        val errors = ShuangpinLayoutVariants.validationErrors(rows)
        assertTrue(errors.any { it.contains("groupId") })
        assertTrue(errors.any { it.contains("宽度") })
    }

    @Test
    fun compactEntryIdentityIsIndependentOfEngineSubModes() {
        val entry = ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe")
        assertEquals("shuangpin-xiaohe:__compact18__", entry)
        assertTrue(ShuangpinLayoutVariants.isCompactEntry(entry))
        assertEquals("shuangpin-xiaohe", ShuangpinLayoutVariants.baseKey(entry))
        assertFalse(ShuangpinLayoutVariants.isCompactEntry("rime:朙月拼音"))
    }

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
        assertTrue(Shuangpin18Keys.showSemicolon("shuangpin"))
        assertTrue(Shuangpin18Keys.showSemicolon("shuangpin-ms"))
        assertFalse(Shuangpin18Keys.showSemicolon("shuangpin-xiaohe"))
    }

    @Test
    fun defaultAndRenamedCustomSchemesHideUnusedSemicolon() {
        for (id in listOf("custom", "tiane")) {
            val scheme = CustomShuangpinSchemeCodec.defaultScheme().copy(name = "天鹅双拼", id = id)
            assertFalse(Shuangpin18Keys.showSemicolon("shuangpin-$id", scheme))
            val parsed = CustomShuangpinSchemeCodec.parseSpDat(CustomShuangpinSchemeCodec.toSpDat(scheme))
            assertFalse(Shuangpin18Keys.showSemicolon("shuangpin-$id", parsed))
        }
        assertFalse(Shuangpin18Keys.showSemicolon("shuangpin-custom", CustomShuangpinScheme()))
    }

    @Test
    fun customSemicolonFollowsInitialFinalAndSyllableMappings() {
        val default = CustomShuangpinSchemeCodec.defaultScheme()
        val mappedSchemes = listOf(
            default.copy(initials = default.initials + ("sh" to ";")),
            default.copy(finals = default.finals + ("ing" to ";")),
            default.copy(syllables = mapOf("ying" to listOf("yk", "y;"))),
            default.copy(syllables = mapOf("shi" to listOf(";i"))),
        )
        for (scheme in mappedSchemes) {
            assertTrue(Shuangpin18Keys.showSemicolon("shuangpin-tiane", scheme))
            assertFalse(Shuangpin18Keys.showSemicolon("shuangpin-xiaohe", scheme))
        }
        assertFalse(Shuangpin18Keys.showSemicolon(
            "shuangpin-tiane", default.copy(syllables = mapOf("ying" to listOf("yk")))
        ))
        val mapped = default.copy(finals = default.finals + ("ing" to ";"))
        assertFalse(Shuangpin18Keys.showSemicolon(
            "shuangpin-tiane", mapped.copy(finals = mapped.finals + ("ing" to "k"))
        ))
    }

    @Test
    fun customSemicolonUpdatesKeyLabelAndPopupTogether() {
        val default = CustomShuangpinSchemeCodec.defaultScheme().copy(name = "天鹅双拼", id = "tiane")
        for (semicolon in listOf(false, true)) {
            val scheme = if (semicolon) default.copy(finals = default.finals + ("ing" to ";")) else default
            val key = Shuangpin18Keys.layout(
                showLangSwitch = false,
                semicolon = Shuangpin18Keys.showSemicolon("shuangpin-tiane", scheme),
            )[1].last()
            val expected = if (semicolon) "l;" else "l"
            val appearance = key.appearance as KeyDef.Appearance.AltText
            assertEquals(expected, appearance.displayText)
            assertEquals(expected, appearance.character)
            assertEquals(expected, key.popup.orEmpty()
                .filterIsInstance<KeyDef.Popup.AltPreview>().single().content)
            assertEquals(expected, key.popup.orEmpty()
                .filterIsInstance<KeyDef.Popup.Keyboard.GroupedPreset>().single().group)
            assertEquals(Shuangpin18Keys.action(12),
                key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        }
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
    fun groupedRowsKeepSourceProportions() {
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
        assertTrue(caps.behaviors.none { it is KeyDef.Behavior.Swipe })
        assertEquals(KeyAction.CapsAction(true),
            caps.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single().action)
        assertEquals(KeyAction.CapsAction(true),
            caps.behaviors.filterIsInstance<KeyDef.Behavior.DoubleTap>().single().action)
        assertFalse((Shuangpin18Keys.layout(false, false)[1].last().appearance as KeyDef.Appearance.Text).displayText.contains(';'))
    }

    @Test
    fun groupedLabelsDefaultToLowercaseAndFollowTheUppercasePreference() {
        for (semicolon in listOf(false, true)) {
            val appearances = Shuangpin18Keys.layout(false, semicolon).take(3).flatten()
                .mapNotNull { it.appearance as? KeyDef.Appearance.AltText }
            val expected = Shuangpin18Keys.groups.map {
                if (semicolon) it else it.replace(";", "")
            }
            assertEquals(expected, appearances.map { it.displayText })
            appearances.zip(expected).forEach { (appearance, label) ->
                assertTrue("Missing uppercase hints for $label", appearance.supportsUppercaseHint)
                for (capsOn in listOf(false, true)) {
                    for (keepUppercase in listOf(false, true)) {
                        assertEquals(
                            if (capsOn || keepUppercase) label.uppercase() else label,
                            resolveCapsAwareAltText(appearance, label, capsOn, keepUppercase),
                        )
                    }
                }
                assertEquals(label, resolveCapsAwareAltText(appearance, label.uppercase(), false, false))
            }
        }
    }

    @Test
    fun groupedKeysUsePresetBackedPopupKeystrokes() {
        val groupKeys = Shuangpin18Keys.layout(false, semicolon = true).take(3).flatten()
            .filter { key ->
                val action = key.behaviors
                    .filterIsInstance<KeyDef.Behavior.Press>()
                    .singleOrNull()?.action as? KeyAction.SymAction
                action != null && Shuangpin18Keys.indexOf(action.sym) != null
            }

        assertEquals(18, groupKeys.size)
        val corners = listOf(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "0", "(", ")", "-",
            "~", "!", "@", "?", ":",
        )
        groupKeys.forEachIndexed { index, key ->
            val grouped = key.popup.orEmpty()
                .filterIsInstance<KeyDef.Popup.Keyboard.GroupedPreset>()
                .single()
            assertEquals(Shuangpin18Keys.groups[index], grouped.group)
            assertEquals(corners[index], grouped.corner)
            assertTrue(key.popup.orEmpty().none { it is KeyDef.Popup.Keyboard.Explicit })
        }
    }

    @Test
    fun composingReplacesOnlyTheCompactCapsKeyWithATapSeparator() {
        for (semicolon in listOf(false, true)) {
            val caps = Shuangpin18Keys.layout(false, semicolon)[2].first()
            val separator = requireNotNull(caps.composeOverride)
            assertEquals("'词", (separator.appearance as KeyDef.Appearance.Text).displayText)
            assertEquals(caps.appearance.percentWidth, separator.appearance.percentWidth, 0f)
            val action = separator.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
                as KeyAction.SymAction
            assertEquals(FcitxKeyMapping.FcitxKey_apostrophe, action.sym.sym)
            assertTrue(action.states.has(KeyState.Virtual))
            assertFalse(action.states.has(KeyState.Shift))
            assertFalse(action.states.has(KeyState.CapsLock))
            assertEquals(1, separator.behaviors.size)
            assertTrue(separator.popup.isNullOrEmpty())
        }
        assertNull(TextKeyboard.getDefaultLayout(false)[2].first().composeOverride)
    }
}
