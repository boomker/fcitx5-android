/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import org.fxboomk.fcitx5.android.core.FormattedText

/** Text state for a search field driven by Fcitx rather than an Android EditText. */
class ClipboardSearchInputState {
    var committedText: String = ""
        private set

    var committedCursor: Int = 0
        private set

    var preeditText: String = ""
        private set

    var preeditCursor: Int = 0
        private set

    val text: String
        get() = buildString(committedText.length + preeditText.length) {
            append(committedText, 0, committedCursor)
            append(preeditText)
            append(committedText, committedCursor, committedText.length)
        }

    val displayCursor: Int
        get() = committedCursor + preeditCursor

    fun commit(text: String, cursor: Int = -1) {
        val insertionPoint = committedCursor
        committedText = buildString(committedText.length + text.length) {
            append(committedText, 0, insertionPoint)
            append(text)
            append(committedText, insertionPoint, committedText.length)
        }
        committedCursor = (insertionPoint + if (cursor < 0) text.length else cursor)
            .coerceIn(insertionPoint, insertionPoint + text.length)
        clearPreedit()
    }

    fun setPreedit(formattedText: FormattedText) {
        preeditText = formattedText.toString()
        preeditCursor = safeBoundary(
            preeditText,
            if (formattedText.cursor < 0) preeditText.length else formattedText.cursor
        )
    }

    fun backspace() {
        if (preeditText.isNotEmpty()) {
            val start = previousBoundary(preeditText, preeditCursor)
            preeditText = preeditText.removeRange(start, preeditCursor)
            preeditCursor = start
            return
        }
        if (committedCursor == 0) return
        val start = previousBoundary(committedText, committedCursor)
        committedText = committedText.removeRange(start, committedCursor)
        committedCursor = start
    }

    fun delete() {
        if (preeditText.isNotEmpty()) {
            if (preeditCursor == preeditText.length) return
            val end = nextBoundary(preeditText, preeditCursor)
            preeditText = preeditText.removeRange(preeditCursor, end)
            return
        }
        if (committedCursor == committedText.length) return
        val end = nextBoundary(committedText, committedCursor)
        committedText = committedText.removeRange(committedCursor, end)
    }

    fun moveCursor(delta: Int) {
        if (preeditText.isNotEmpty()) {
            preeditCursor = if (delta < 0) {
                previousBoundary(preeditText, preeditCursor)
            } else {
                nextBoundary(preeditText, preeditCursor)
            }
        } else {
            committedCursor = if (delta < 0) {
                previousBoundary(committedText, committedCursor)
            } else {
                nextBoundary(committedText, committedCursor)
            }
        }
    }

    fun setCursor(displayOffset: Int): Boolean {
        val insertionPoint = committedCursor
        val hadPreedit = preeditText.isNotEmpty()
        val offset = displayOffset.coerceIn(0, text.length)
        val committedOffset = when {
            !hadPreedit -> offset
            offset <= insertionPoint -> offset
            offset >= insertionPoint + preeditText.length -> offset - preeditText.length
            else -> insertionPoint
        }
        committedCursor = safeBoundary(committedText, committedOffset)
        clearPreedit()
        return hadPreedit
    }

    fun deleteSurrounding(before: Int, after: Int) {
        if (before <= 0 && after <= 0) return
        val start = moveByCodePoints(committedText, committedCursor, -before)
        val end = moveByCodePoints(committedText, committedCursor, after)
        committedText = committedText.removeRange(start, end)
        committedCursor = start
    }

    fun clear() {
        committedText = ""
        committedCursor = 0
        clearPreedit()
    }

    private fun clearPreedit() {
        preeditText = ""
        preeditCursor = 0
    }

    private fun safeBoundary(text: String, index: Int): Int {
        val clamped = index.coerceIn(0, text.length)
        return if (clamped > 0 && clamped < text.length &&
            Character.isLowSurrogate(text[clamped]) && Character.isHighSurrogate(text[clamped - 1])
        ) {
            clamped - 1
        } else {
            clamped
        }
    }

    private fun previousBoundary(text: String, index: Int): Int {
        val boundary = safeBoundary(text, index)
        return if (boundary == 0) 0 else Character.offsetByCodePoints(text, boundary, -1)
    }

    private fun nextBoundary(text: String, index: Int): Int {
        val boundary = safeBoundary(text, index)
        return if (boundary == text.length) text.length else Character.offsetByCodePoints(text, boundary, 1)
    }

    private fun moveByCodePoints(text: String, index: Int, delta: Int): Int {
        var result = safeBoundary(text, index)
        repeat(kotlin.math.abs(delta)) {
            result = if (delta < 0) previousBoundary(text, result) else nextBoundary(text, result)
        }
        return result
    }
}
