/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinScheme
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs

internal enum class ShuangpinKeyboardLayout { Full, Compact18 }

internal object ShuangpinKeyboardLayouts {
    fun supports(uniqueName: String): Boolean =
        uniqueName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY ||
            ShuangpinLayoutKeys.isSchemeUniqueName(uniqueName)

    fun read(uniqueName: String, compactInputMethods: Set<String>): ShuangpinKeyboardLayout =
        if (supports(uniqueName) && uniqueName in compactInputMethods) {
            ShuangpinKeyboardLayout.Compact18
        } else {
            ShuangpinKeyboardLayout.Full
        }

    fun updated(
        compactInputMethods: Set<String>,
        uniqueName: String,
        layout: ShuangpinKeyboardLayout,
    ): Set<String> = if (!supports(uniqueName)) {
        compactInputMethods
    } else if (layout == ShuangpinKeyboardLayout.Compact18) {
        compactInputMethods + uniqueName
    } else {
        compactInputMethods - uniqueName
    }

    fun read(uniqueName: String): ShuangpinKeyboardLayout = read(
        uniqueName, AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue()
    )

    fun write(uniqueName: String, layout: ShuangpinKeyboardLayout) {
        val preference = AppPrefs.getInstance().internal.compactShuangpinInputMethods
        preference.setValue(updated(preference.getValue(), uniqueName, layout))
        TextKeyboard.refreshShuangpinLayout()
    }

    fun isCompact(entry: InputMethodEntry?): Boolean =
        entry?.addon == "pinyin" && read(entry.uniqueName) == ShuangpinKeyboardLayout.Compact18

    fun isCompact(entry: InputMethodEntry?, compactInputMethods: Set<String>): Boolean =
        entry?.addon == "pinyin" &&
            read(entry.uniqueName, compactInputMethods) == ShuangpinKeyboardLayout.Compact18
}

/** Dedicated non-text keysyms shared with the native pinyin engine. */
internal object Shuangpin18Keys {
    private const val GROUP_KEYSYM_BASE = 0x0200E000
    private const val MODE_KEYSYM_BASE = 0x0200E100
    private const val CONFIG_BEGIN = 0x0200E200
    private const val CONFIG_END_GROUP = 0x0200E201
    private const val CONFIG_COMMIT = 0x0200E202
    private const val CONFIG_CHARACTER_BASE = 0x0200E210
    private const val REPRESENTATIVE_KEYSYM_BASE = 0x0200E300
    private const val GROUP_CHARACTERS = "abcdefghijklmnopqrstuvwxyz;"

    val groups = listOf(
        "q", "we", "rt", "y", "u", "io", "p",
        "a", "sd", "fg", "h", "jk", "l;",
        "z", "xc", "v", "bn", "m",
    )
    private val corners = listOf(
        "1", "2", "3", "4", "5", "6", "7",
        "8", "9", "0", "(", ")", "-",
        "~", "!", "@", "?", ":",
    )

    fun defaultCorner(groupId: String): String {
        require(isValidGroup(groupId)) { "Invalid shuangpin group: $groupId" }
        return corners.getOrNull(groups.indexOf(groupId)).orEmpty()
    }

    fun displayGroup(groupId: String, semicolon: Boolean): String {
        require(isValidGroup(groupId)) { "Invalid shuangpin group: $groupId" }
        return if (semicolon || groupId == ";") groupId else groupId.replace(";", "")
    }

    fun isValidGroup(groupId: String?): Boolean =
        !groupId.isNullOrEmpty() && groupId.all { it in GROUP_CHARACTERS } &&
            groupId.toSet().size == groupId.length

    /** Identical buttons may repeat; distinct groups must form a complete partition. */
    fun groupingErrors(groupIds: List<String>, requireSemicolon: Boolean = false): List<String> = buildList {
        val owners = mutableMapOf<Char, String>()
        groupIds.distinct().forEach { groupId ->
            if (!isValidGroup(groupId)) {
                add("键位分组无效：$groupId（使用不重复的小写字母 a-z 或分号）")
                return@forEach
            }
            groupId.forEach { character ->
                val previous = owners.putIfAbsent(character, groupId)
                if (previous != null) {
                    add("字符 $character 同时属于分组 $previous 和 $groupId")
                }
            }
        }
        val required = if (requireSemicolon) GROUP_CHARACTERS else GROUP_CHARACTERS.dropLast(1)
        val missing = required.filter { it !in owners }
        if (missing.isNotEmpty()) add("18 键布局缺少字符：${missing.toList().joinToString("、")}")
    }

    fun action(index: Int): KeyAction.SymAction {
        require(index in groups.indices)
        return KeyAction.SymAction(
            KeySym(GROUP_KEYSYM_BASE + index), KeyStates.Virtual
        )
    }

    fun indexOf(sym: KeySym): Int? =
        (sym.sym - GROUP_KEYSYM_BASE).takeIf { it in groups.indices }

    /** The representative is independent of the group's position in the layout. */
    fun action(groupId: String): KeyAction.SymAction {
        require(isValidGroup(groupId)) { "Invalid shuangpin group: $groupId" }
        return virtualAction(REPRESENTATIVE_KEYSYM_BASE + GROUP_CHARACTERS.indexOf(groupId.first()))
    }

    fun isGroupAction(sym: KeySym): Boolean = indexOf(sym) != null ||
        sym.sym - REPRESENTATIVE_KEYSYM_BASE in GROUP_CHARACTERS.indices

    /** Commit a complete partition; mode notification or a group press activates it. */
    fun configurationActions(groupIds: List<String>): List<KeyAction.SymAction> {
        val errors = groupingErrors(groupIds)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        return buildList {
            add(virtualAction(CONFIG_BEGIN))
            groupIds.distinct().forEach { groupId ->
                groupId.forEach { character ->
                    add(virtualAction(CONFIG_CHARACTER_BASE + GROUP_CHARACTERS.indexOf(character)))
                }
                add(virtualAction(CONFIG_END_GROUP))
            }
            add(virtualAction(CONFIG_COMMIT))
        }
    }

    private fun virtualAction(sym: Int) = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)

    fun modeAction(compact: Boolean): KeyAction.SymAction = KeyAction.SymAction(
        KeySym(MODE_KEYSYM_BASE + if (compact) 1 else 0), KeyStates.Virtual
    )

    fun showSemicolon(
        uniqueName: String,
        customScheme: CustomShuangpinScheme? = null,
    ): Boolean = when {
        uniqueName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY || uniqueName == "shuangpin-ms" -> true
        ShuangpinLayoutKeys.isCustomSchemeUniqueName(uniqueName) -> {
            val scheme = customScheme ?: CustomShuangpinSchemeStore.load()
            ";" in scheme.initials.values || ";" in scheme.finals.values ||
                scheme.syllables.values.any { keys -> keys.any { ';' in it } }
        }
        else -> false
    }

    fun layout(
        showLangSwitch: Boolean,
        semicolon: Boolean,
    ): List<List<KeyDef>> {
        fun groupKey(index: Int, width: Float): KeyDef =
            ShuangpinGroupKey(groups[index], semicolon, weight = width)
        return listOf(
            (0..6).map { groupKey(it, 1f / 7) },
            (7..12).map { groupKey(it, 1f / 6) },
            listOf(compactCapsKey(0.15f)) +
                (13..17).map { groupKey(it, 0.14f) } + BackspaceKey(0.15f),
            TextKeyboard.getDefaultLayout(showLangSwitch).last(),
        )
    }

    fun layoutSixColumns(showLangSwitch: Boolean, semicolon: Boolean): List<List<KeyDef>> =
        groups.chunked(6).map { row ->
            row.map { ShuangpinGroupKey(it, semicolon, weight = 1f / 6) }
        } + listOf(buildList {
            add(compactCapsKey(0.12f))
            add(LayoutSwitchKey("?123", "", percentWidth = 0.12f))
            add(CommaKey(0.08f, KeyDef.Appearance.Variant.Alternative))
            if (showLangSwitch) add(LanguageKey(0.08f))
            add(SpaceKey())
            add(SymbolKey(".", 0.08f, KeyDef.Appearance.Variant.Alternative))
            add(BackspaceKey(0.12f))
            add(ReturnKey(percentWidth = 0.12f))
        })

    private fun compactCapsKey(width: Float): CapsKey = CapsKey(percentWidth = width).apply {
        composeOverride = KeyDef(
            KeyDef.Appearance.Text(
                displayText = "'词", textSize = 23f, percentWidth = width,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            setOf(KeyDef.Behavior.Press(KeyAction.SymAction(
                KeySym(FcitxKeyMapping.FcitxKey_apostrophe), KeyStates.Virtual,
            ))),
        )
    }
}
