/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import android.view.KeyCharacterMap
import android.view.KeyEvent

@JvmInline
value class KeySym(val sym: Int) {

    val keyCode get() = FcitxKeyMapping.symToKeyCode(sym) ?: KeyEvent.KEYCODE_UNKNOWN

    override fun toString() = "0x" + sym.toString(16).padStart(4, '0')

    companion object {
        fun fromKeyEvent(event: KeyEvent): KeySym? {
            val charCode = event.unicodeChar
            // try charCode first, allow upper and lower case characters generating different KeySym
            if (charCode != 0 &&
                // skip \t, because it's charCode is different from KeySym
                charCode != '\t'.code &&
                // skip \n, because fcitx wants \r for return
                charCode != '\n'.code &&
                // skip Android's private-use character
                charCode != KeyCharacterMap.HEX_INPUT.code &&
                charCode != KeyCharacterMap.PICKER_DIALOG_INPUT.code
            ) {
                return KeySym(charCode)
            }
            // Special handling for function keys (F1-F12) to ensure correct mapping
            // On some devices, KeyCharacterMap may not map function keys correctly
            val functionKeySym = when (event.keyCode) {
                KeyEvent.KEYCODE_F1 -> 0xffbe
                KeyEvent.KEYCODE_F2 -> 0xffbf
                KeyEvent.KEYCODE_F3 -> 0xffc0
                KeyEvent.KEYCODE_F4 -> 0xffc1
                KeyEvent.KEYCODE_F5 -> 0xffc2
                KeyEvent.KEYCODE_F6 -> 0xffc3
                KeyEvent.KEYCODE_F7 -> 0xffc4
                KeyEvent.KEYCODE_F8 -> 0xffc5
                KeyEvent.KEYCODE_F9 -> 0xffc6
                KeyEvent.KEYCODE_F10 -> 0xffc7
                KeyEvent.KEYCODE_F11 -> 0xffc8
                KeyEvent.KEYCODE_F12 -> 0xffc9
                else -> null
            }
            if (functionKeySym != null) {
                return KeySym(functionKeySym)
            }
            return KeySym(FcitxKeyMapping.keyCodeToSym(event.keyCode) ?: return null)
        }

    }
}
