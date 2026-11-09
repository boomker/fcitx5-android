/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseEnglishSpacingTest {

    private fun pad(text: String, cursor: Int = -1, previous: Int? = null) =
        ChineseEnglishSpacing.pad(text, cursor, previous)

    @Test
    fun prependsSpaceWhenChinesePrecedesAsciiLetterCommit() {
        val result = pad("app", previous = '好'.code)
        assertEquals(" app", result.text)
    }

    @Test
    fun doesNotAppendSpaceAfterAsciiWordWithoutChinesePrefix() {
        val result = pad("app", previous = ' '.code)
        assertEquals("app", result.text)
    }

    @Test
    fun prependsSpaceWhenEnglishPrecedesChineseCommit() {
        val result = pad("你好", previous = 'a'.code)
        assertEquals(" 你好", result.text)
    }

    @Test
    fun prependsSpaceBetweenEnglishCommits() {
        val result = pad("hello", previous = 'x'.code)
        assertEquals(" hello", result.text)
    }

    @Test
    fun doesNotInsertSpaceBetweenChineseCommits() {
        val result = pad("世界", previous = '好'.code)
        assertEquals("世界", result.text)
    }

    @Test
    fun preservesExistingTrailingSpace() {
        val result = pad("app ", previous = '好'.code)
        assertEquals(" app ", result.text)
    }

    @Test
    fun prependsForLetterLedCommitButNeverAppendsForMixedCommit() {
        // A Chinese neighbour triggers a prepend even for a mixed commit.
        assertEquals(" a1", pad("a1", previous = '好'.code).text)
        // Punctuation does not start with a letter.
        assertEquals("，", pad("，", previous = 'a'.code).text)
    }

    @Test
    fun treatsNullPrecedingCharAsNoPrepend() {
        val result = pad("word", previous = null)
        assertEquals("word", result.text)
    }

    @Test
    fun supplementaryChinesePrefixIsRecognized() {
        // U+20000, a CJK Extension B ideograph, as a surrogate pair.
        val supplementary = 0x20000
        val result = pad("app", previous = supplementary)
        assertEquals(" app", result.text)
    }

    @Test
    fun prependsSpaceWhenEnglishPrecedesSupplementaryChineseCommit() {
        val text = String(Character.toChars(0x20000))
        val result = pad(text, cursor = text.length, previous = 'a'.code)
        assertEquals(" $text", result.text)
        assertEquals(text.length + 1, result.cursor)
    }

    @Test
    fun separatesSuccessiveEnglishAndChineseCommitsWithoutTrailingSpace() {
        var committed = ""
        for (text in listOf("hello", "世界", "app", "word")) {
            val previous = if (committed.isEmpty()) null else committed.codePointBefore(committed.length)
            committed += pad(text, previous = previous).text
        }
        assertEquals("hello 世界 app word", committed)
    }

    @Test
    fun keepsCommitCursorWhenUnset() {
        assertEquals(-1, pad("app", cursor = -1, previous = '好'.code).cursor)
    }

    @Test
    fun shiftsEndCursorOnlyForPrependedSpace() {
        val result = pad("app", cursor = 3, previous = '好'.code)
        assertEquals(" app", result.text)
        assertEquals(4, result.cursor)
    }

    @Test
    fun shiftsCursorForPrependOnlyWhenCursorNotAtWordEnd() {
        val result = pad("app", cursor = 1, previous = '好'.code)
        assertEquals(" app", result.text)
        assertEquals(2, result.cursor)
    }

    @Test
    fun keepsExplicitCursorWhenNoSpaceIsPrepended() {
        for (cursor in listOf(0, 1, 3)) {
            val result = pad("app", cursor = cursor, previous = ' '.code)
            assertEquals("app", result.text)
            assertEquals(cursor, result.cursor)
        }
    }

    @Test
    fun shiftsCursorOnlyForPrependedSpaceAfterEnglishCommit() {
        for (text in listOf("world", "世界")) {
            for (cursor in listOf(-1, 0, 1, text.length)) {
                val result = pad(text, cursor = cursor, previous = 'o'.code)
                assertEquals(" $text", result.text)
                assertEquals(if (cursor == -1) -1 else cursor + 1, result.cursor)
            }
        }
    }

    @Test
    fun preservesExistingLeadingSpace() {
        for (previous in listOf('好'.code, 'o'.code)) {
            val result = pad(" app", cursor = 4, previous = previous)
            assertEquals(" app", result.text)
            assertEquals(4, result.cursor)
        }
    }

    @Test
    fun emptyCommitIsReturnedUnchanged() {
        val result = pad("", cursor = 0, previous = '好'.code)
        assertEquals("", result.text)
        assertEquals(0, result.cursor)
    }
}
