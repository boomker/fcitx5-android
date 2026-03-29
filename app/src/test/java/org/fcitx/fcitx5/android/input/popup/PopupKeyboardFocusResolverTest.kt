/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

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
            moveThreshold = 0.4f
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
            y = 49f,
            rowCount = 3,
            columnCount = 5,
            keyWidth = 10,
            keyHeight = 20,
            focusedRow = 0,
            focusedColumn = 2,
            lastTouchY = 58f,
            cumulativeDeltaY = 0f,
            moveThreshold = 0.4f
        )

        assertFalse(result.dismiss)
        assertEquals(1, result.targetRow)
        assertEquals(2, result.targetColumn)
        assertEquals(49f, result.lastTouchY)
        assertEquals(-1f, result.cumulativeDeltaY, 0.001f)
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
            moveThreshold = 0.4f
        )

        assertTrue(result.dismiss)
        assertEquals(null, result.targetRow)
        assertEquals(null, result.targetColumn)
    }
}
