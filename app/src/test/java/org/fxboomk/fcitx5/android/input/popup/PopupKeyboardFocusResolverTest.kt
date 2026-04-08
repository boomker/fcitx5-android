/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupKeyboardFocusResolverTest {

    @Test
    fun directHoverStillSelectsCandidate() {
        val result = PopupKeyboardFocusResolver.resolve(
            x = 34f,
            y = 5f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 0,
            focusedColumn = 2,
            lastTouchY = null,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f,
            rowLockDirection = 0
        )

        assertFalse(result.dismiss)
        assertEquals(2, result.targetRow)
        assertEquals(3, result.targetColumn)
        assertEquals(5f, result.lastTouchY)
        assertEquals(0f, result.cumulativeDeltaY, 0.001f)
    }

    @Test
    fun upwardMoveCanAdvanceRowBeforeHoveringNextCandidate() {
        val result = PopupKeyboardFocusResolver.resolve(
            x = 25f,
            y = 58f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 0,
            focusedColumn = 2,
            lastTouchY = 66f,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f,
            rowLockDirection = 0
        )

        assertFalse(result.dismiss)
        assertEquals(1, result.targetRow)
        assertEquals(2, result.targetColumn)
        assertEquals(58f, result.lastTouchY)
        assertEquals(0f, result.cumulativeDeltaY, 0.001f)
        assertEquals(1, result.rowLockDirection)
    }

    @Test
    fun horizontalMoveStillWorksWithinSameRowTolerance() {
        val result = PopupKeyboardFocusResolver.resolve(
            x = 35f,
            y = 63f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 0,
            focusedColumn = 2,
            lastTouchY = 63f,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f,
            rowLockDirection = 0
        )

        assertFalse(result.dismiss)
        assertEquals(0, result.targetRow)
        assertEquals(3, result.targetColumn)
        assertEquals(63f, result.lastTouchY)
        assertEquals(0f, result.cumulativeDeltaY, 0.001f)
    }

    @Test
    fun rowLockPreventsImmediateSnapBackWhileMovingUp() {
        val result = PopupKeyboardFocusResolver.resolve(
            x = 25f,
            y = 56f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 1,
            focusedColumn = 2,
            lastTouchY = 58f,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f,
            rowLockDirection = 1
        )

        assertFalse(result.dismiss)
        assertEquals(1, result.targetRow)
        assertEquals(2, result.targetColumn)
        assertEquals(56f, result.lastTouchY)
        assertEquals(-2f, result.cumulativeDeltaY, 0.001f)
        assertEquals(1, result.rowLockDirection)
    }

    @Test
    fun horizontalMoveKeepsFocusOnLockedRow() {
        val result = PopupKeyboardFocusResolver.resolve(
            x = 35f,
            y = 75f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 1,
            focusedColumn = 2,
            lastTouchY = 75f,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f,
            rowLockDirection = 1
        )

        assertFalse(result.dismiss)
        assertEquals(1, result.targetRow)
        assertEquals(3, result.targetColumn)
        assertEquals(75f, result.lastTouchY)
        assertEquals(0f, result.cumulativeDeltaY, 0.001f)
        assertEquals(1, result.rowLockDirection)
    }

    @Test
    fun largeOutOfBoundsMovementDismissesPopup() {
        val result = PopupKeyboardFocusResolver.resolve(
            x = -25f,
            y = 30f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 1,
            focusedColumn = 2,
            lastTouchY = 30f,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f,
            rowLockDirection = 0
        )

        assertTrue(result.dismiss)
        assertEquals(null, result.targetRow)
        assertEquals(null, result.targetColumn)
    }
}
