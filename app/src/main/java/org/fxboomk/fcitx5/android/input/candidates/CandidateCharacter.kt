/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class CandidateEdgeCharacters(
    val first: String,
    val last: String
)

internal fun String.candidateCharacters(): List<String> {
    if (isBlank()) return emptyList()

    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply {
        setText(this@candidateCharacters)
    }
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            add(substring(start, end))
            start = end
            end = iterator.next()
        }
    }
}

internal data class CandidateCharacterGrid(
    val rows: Int,
    val columns: Int
)

internal fun candidateCharacterGrid(characterCount: Int): CandidateCharacterGrid {
    val count = characterCount.coerceAtLeast(1)
    if (count < 4) return CandidateCharacterGrid(rows = 1, columns = count)
    if (count == 6) return CandidateCharacterGrid(rows = 2, columns = 3)

    val size = ceil(sqrt(count.toDouble())).toInt()
    return CandidateCharacterGrid(rows = size, columns = size)
}

internal fun isInsideCandidateCharacterPopup(
    x: Float,
    y: Float,
    width: Int,
    height: Int
): Boolean = x >= 0f && x < width && y >= 0f && y < height

internal fun String.isCandidateFrequencyResetActionText(): Boolean {
    val normalized = trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return false
    if (normalized in knownFrequencyResetActionTexts) return true
    val frequencyTerms = listOf("candidate", "word", "frequency")
    return listOf("forget", "reset").any(normalized::contains) &&
            frequencyTerms.any(normalized::contains)
}

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

private val knownFrequencyResetActionTexts = setOf(
    "forget candidate",
    "forget word",
    "oublie la proposition",
    "oublie le mot",
    "glem ord",
    "wort vergessen",
    "забыть слово",
    "забыть слово-кандидат",
    "단어 잊기",
    "忘记候选词",
    "忘记词汇",
    "忘記候選詞",
    "忽略字詞",
    "重置词频",
    "重置詞頻"
)
