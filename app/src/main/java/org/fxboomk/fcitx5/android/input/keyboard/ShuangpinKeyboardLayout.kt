/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
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

    fun action(index: Int): KeyAction.SymAction {
        require(index in groups.indices)
        return KeyAction.SymAction(
            KeySym(GROUP_KEYSYM_BASE + index), KeyStates.Virtual
        )
    }

    fun indexOf(sym: KeySym): Int? =
        (sym.sym - GROUP_KEYSYM_BASE).takeIf { it in groups.indices }

    fun modeAction(compact: Boolean): KeyAction.SymAction = KeyAction.SymAction(
        KeySym(MODE_KEYSYM_BASE + if (compact) 1 else 0), KeyStates.Virtual
    )

    fun showSemicolon(uniqueName: String): Boolean =
        uniqueName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY || uniqueName == "shuangpin-ms" ||
            ShuangpinLayoutKeys.isCustomSchemeUniqueName(uniqueName)

    fun layout(
        showLangSwitch: Boolean,
        semicolon: Boolean,
    ): List<List<KeyDef>> {
        fun groupKey(index: Int, width: Float): KeyDef {
            val group = groups[index].let { if (semicolon) it else it.replace(";", "") }
            val label = group.uppercase()
            val corner = corners[index]
            return KeyDef(
                KeyDef.Appearance.AltText(
                    displayText = label, altText = corner, character = group, textSize = 23f,
                    percentWidth = width, followsCapsState = false,
                ),
                setOf(
                    KeyDef.Behavior.Press(action(index)),
                    KeyDef.Behavior.Swipe(KeyAction.FcitxKeyAction(corner)),
                ),
                arrayOf(
                    KeyDef.Popup.AltPreview(label, corner),
                    KeyDef.Popup.Keyboard.Explicit(
                        (group.map { if (it == ';') ":" else it.uppercase() } +
                            corner + group.map(Char::toString)).distinct().toTypedArray()
                    ),
                ),
            )
        }
        return listOf(
            (0..6).map { groupKey(it, 1f / 7) },
            (7..12).map { groupKey(it, 1f / 6) },
            listOf(CapsKey(percentWidth = 0.15f).apply {
                composeOverride = KeyDef(
                    KeyDef.Appearance.Text(
                        displayText = "'词", textSize = 23f, percentWidth = 0.15f,
                        variant = KeyDef.Appearance.Variant.Alternative,
                    ),
                    setOf(KeyDef.Behavior.Press(KeyAction.SymAction(
                        KeySym(FcitxKeyMapping.FcitxKey_apostrophe), KeyStates.Virtual,
                    ))),
                )
            }) +
                (13..17).map { groupKey(it, 0.14f) } + BackspaceKey(0.15f),
            TextKeyboard.getDefaultLayout(showLangSwitch).last(),
        )
    }
}
