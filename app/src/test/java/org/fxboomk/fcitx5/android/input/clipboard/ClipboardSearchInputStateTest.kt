/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import org.fxboomk.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardSearchInputStateTest {

    @Test
    fun cursorMovementAndDeletionRespectEmojiBoundaries() {
        val state = ClipboardSearchInputState()
        state.commit("a😀b")

        state.moveCursor(-1)
        assertEquals(3, state.committedCursor)
        state.backspace()
        assertEquals("ab", state.committedText)
        assertEquals(1, state.committedCursor)
        state.delete()
        assertEquals("a", state.committedText)
    }

    @Test
    fun boundaryOperationsAreNoOps() {
        val state = ClipboardSearchInputState()
        state.backspace()
        state.delete()
        state.moveCursor(-1)
        state.moveCursor(1)
        state.deleteSurrounding(before = 4, after = 4)
        assertEquals("", state.text)

        state.commit("😀")
        state.moveCursor(-1)
        state.moveCursor(-1)
        assertEquals(0, state.committedCursor)
        state.moveCursor(1)
        state.moveCursor(1)
        assertEquals(2, state.committedCursor)
    }

    @Test
    fun deleteSurroundingClampsToAvailableCodePoints() {
        val state = ClipboardSearchInputState()
        state.commit("a😀b")
        state.moveCursor(-1)
        state.deleteSurrounding(before = 3, after = 3)

        assertEquals("", state.committedText)
        assertEquals(0, state.committedCursor)
    }

    @Test
    fun preeditIsDisplayedAndCommitReplacesIt() {
        val state = ClipboardSearchInputState()
        state.commit("ab")
        state.moveCursor(-1)
        state.setPreedit(FormattedText(arrayOf("😀"), intArrayOf(0), 2))
        assertEquals("a😀b", state.text)
        assertEquals(2, state.preeditCursor)
        assertEquals(3, state.displayCursor)

        state.commit("中")
        assertEquals("a中b", state.text)
        assertEquals(2, state.committedCursor)
        assertEquals("", state.preeditText)
    }

    @Test
    fun commitCursorIsClampedToInsertedText() {
        val state = ClipboardSearchInputState()
        state.commit("abc", cursor = 100)
        assertEquals("abc", state.text)
        assertEquals(3, state.committedCursor)
    }

    @Test
    fun touchCursorPositionRespectsEmojiBoundaries() {
        val state = ClipboardSearchInputState()
        state.commit("a😀b")

        assertEquals(false, state.setCursor(2))
        assertEquals(1, state.committedCursor)
        state.delete()
        assertEquals("ab", state.text)
    }

    @Test
    fun touchCursorPositionClearsPreeditAtInsertionPoint() {
        val state = ClipboardSearchInputState()
        state.commit("ab")
        state.moveCursor(-1)
        state.setPreedit(FormattedText(arrayOf("中"), intArrayOf(0), 1))

        assertEquals(true, state.setCursor(3))
        assertEquals("ab", state.text)
        assertEquals(2, state.committedCursor)
        assertEquals("", state.preeditText)
    }
}
