/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

/**
 * Prepends a space between Chinese and English text or between English words,
 * for the "insert space between Chinese and English" preference.
 * Never adds a trailing space.
 *
 * Applies to text committed out of the composition: picking a candidate, or a
 * key such as Return (also with Ctrl / Shift) committing the preedit as is.
 */
internal object ChineseEnglishSpacing {

    data class PaddedCommit(val text: String, val cursor: Int)

    /**
     * @param text text fcitx is committing.
     * @param cursor commit cursor reported by fcitx, `-1` when unset.
     * @param previousCodePoint code point right before the position [text] lands
     * at, or `null` when nothing precedes it.
     */
    fun pad(text: String, cursor: Int, previousCodePoint: Int?): PaddedCommit {
        if (text.isEmpty()) {
            return PaddedCommit(text, cursor)
        }
        val firstCodePoint = text.codePointAt(0)
        val prependSpace = when {
            previousCodePoint == null -> false
            isAsciiLetter(previousCodePoint) ->
                isAsciiLetter(firstCodePoint) || isChineseCodePoint(firstCodePoint)
            else -> isChineseCodePoint(previousCodePoint) && isAsciiLetter(firstCodePoint)
        }
        if (!prependSpace) {
            return PaddedCommit(text, cursor)
        }
        val paddedText = " $text"
        val paddedCursor = if (cursor == -1) {
            -1
        } else {
            cursor + 1
        }
        return PaddedCommit(paddedText, paddedCursor)
    }

    fun isAsciiLetter(codePoint: Int): Boolean =
        codePoint in 'A'.code..'Z'.code ||
            codePoint in 'a'.code..'z'.code

    fun isChineseCodePoint(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F ||
            codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0x2CEB0..0x2EBEF ||
            codePoint in 0x30000..0x3134F ||
            codePoint in 0x31350..0x323AF
}
