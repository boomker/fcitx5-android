/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.icu.text.BreakIterator
import java.util.Locale

data class ClipboardToken(
    val text: String,
    val start: Int,
    val end: Int
)

object ClipboardTextTokenizer {

    private fun createWordBreaker() =
        BreakIterator.getWordInstance(Locale.CHINESE)
    private val coarseTokenPattern = Regex(
        """\p{IsHan}+|[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*|[~\\/]+|[^\s]"""
    )
    private val hanPattern = Regex("""^\p{IsHan}+$""")

    fun tokenize(text: String): List<ClipboardToken> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n")
        val tokens = mutableListOf<ClipboardToken>()
        coarseTokenPattern.findAll(normalized).forEach { match ->
            val chunk = match.value
            val start = match.range.first
            if (hanPattern.matches(chunk)) {
                appendHanTokens(tokens, chunk, start)
            } else {
                tokens += ClipboardToken(chunk, start, start + chunk.length)
            }
        }
        return tokens
    }

    fun joinSelection(sourceText: String, tokens: List<ClipboardToken>): String {
        if (tokens.isEmpty()) return ""
        val sorted = tokens.sortedBy { it.start }
        val builder = StringBuilder()
        sorted.forEachIndexed { index, token ->
            if (index > 0) {
                builder.append(joinerBetween(sourceText, sorted[index - 1], token))
            }
            builder.append(token.text)
        }
        return builder.toString()
    }

    private fun appendHanTokens(
        output: MutableList<ClipboardToken>,
        chunk: String,
        baseStart: Int
    ) {
        val words = runCatching {
            val breaker = createWordBreaker()
            val words = mutableListOf<String>()
            breaker.setText(chunk)
            var start = breaker.first()
            var end = breaker.next()
            while (end != BreakIterator.DONE) {
                val word = chunk.substring(start, end)
                if (word.isNotBlank()) {
                    words += word
                }
                start = end
                end = breaker.next()
            }
            words
        }.getOrElse { listOf(chunk) }
        if (words.isEmpty()) {
            output += ClipboardToken(chunk, baseStart, baseStart + chunk.length)
            return
        }

        var cursor = 0
        for (word in words) {
            val relativeStart = chunk.indexOf(word, cursor)
                .takeIf { it >= 0 }
                ?: chunk.indexOf(word)
            if (relativeStart < 0) {
                output += ClipboardToken(chunk, baseStart, baseStart + chunk.length)
                return
            }
            val relativeEnd = relativeStart + word.length
            output += ClipboardToken(word, baseStart + relativeStart, baseStart + relativeEnd)
            cursor = relativeEnd
        }
    }

    private fun joinerBetween(
        sourceText: String,
        previous: ClipboardToken,
        current: ClipboardToken
    ): String {
        if (current.start <= previous.end || previous.end > sourceText.length || current.start > sourceText.length) {
            return ""
        }
        val gap = sourceText.substring(previous.end, current.start)
        return when {
            gap.any { it == '\n' } -> "\n"
            gap.any(Char::isWhitespace) -> " "
            else -> ""
        }
    }
}
