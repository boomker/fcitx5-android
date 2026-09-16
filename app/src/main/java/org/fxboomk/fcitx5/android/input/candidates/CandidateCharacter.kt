/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import java.text.BreakIterator
import java.util.Locale

internal data class CandidateEdgeCharacters(
    val first: String,
    val last: String
)

internal fun String.candidateEdgeCharacters(): CandidateEdgeCharacters? {
    if (isBlank() || isPunctuationOrSymbolCandidate() || isKeycapEmojiCandidate()) return null

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply {
        setText(this@candidateEdgeCharacters)
    }
    val firstEnd = iterator.following(0)
    if (firstEnd == BreakIterator.DONE || firstEnd == length) return null
    val lastStart = iterator.preceding(length)
    if (lastStart == BreakIterator.DONE || lastStart == 0) return null

    return CandidateEdgeCharacters(
        first = substring(0, firstEnd),
        last = substring(lastStart)
    )
}

private fun String.isPunctuationOrSymbolCandidate(): Boolean =
    codePoints().allMatch { codePoint ->
        Character.isWhitespace(codePoint) || when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt() -> true

            else -> false
        }
    }

private fun String.isKeycapEmojiCandidate(): Boolean {
    val codePoints = codePoints().toArray()
    return COMBINING_ENCLOSING_KEYCAP in codePoints && codePoints.all { codePoint ->
        codePoint in '0'.code..'9'.code ||
                codePoint == '#'.code ||
                codePoint == '*'.code ||
                codePoint == VARIATION_SELECTOR_16 ||
                codePoint == COMBINING_ENCLOSING_KEYCAP
    }
}

private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
