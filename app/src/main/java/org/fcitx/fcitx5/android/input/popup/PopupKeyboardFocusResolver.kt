/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.abs
import kotlin.math.floor

internal data class PopupKeyboardFocusResult(
    val dismiss: Boolean,
    val targetRow: Int? = null,
    val targetColumn: Int? = null,
    val lastTouchY: Float? = null,
    val cumulativeDeltaY: Float = 0f
)

internal object PopupKeyboardFocusResolver {

    private const val DismissPaddingCells = 2

    fun resolve(
        x: Float,
        y: Float,
        rowCount: Int,
        columnCount: Int,
        keyWidth: Int,
        keyHeight: Int,
        focusedRow: Int,
        focusedColumn: Int,
        lastTouchY: Float?,
        cumulativeDeltaY: Float,
        moveThreshold: Float
    ): PopupKeyboardFocusResult {
        if (isDismissed(x, y, rowCount, columnCount, keyWidth, keyHeight)) {
            return PopupKeyboardFocusResult(
                dismiss = true,
                lastTouchY = y,
                cumulativeDeltaY = 0f
            )
        }

        val insideGrid = isInsideGrid(x, y, rowCount, columnCount, keyWidth, keyHeight)
        val hoveredColumn = hoveredColumn(x, columnCount, keyWidth)
        val hoveredRow = if (insideGrid) {
            hoveredRow(y, rowCount, keyHeight)
        } else {
            null
        }
        if (insideGrid && (hoveredRow != focusedRow || hoveredColumn != focusedColumn)) {
            return PopupKeyboardFocusResult(
                dismiss = false,
                targetRow = hoveredRow,
                targetColumn = hoveredColumn,
                lastTouchY = y,
                cumulativeDeltaY = 0f
            )
        }

        val nextLastTouchY = y
        val deltaY = lastTouchY?.let { y - it } ?: 0f
        val nextCumulativeDeltaY = cumulativeDeltaY + deltaY
        val threshold = keyHeight * moveThreshold
        if (threshold <= 0f || abs(nextCumulativeDeltaY) < threshold) {
            return PopupKeyboardFocusResult(
                dismiss = false,
                lastTouchY = nextLastTouchY,
                cumulativeDeltaY = nextCumulativeDeltaY
            )
        }

        val steps = floor(abs(nextCumulativeDeltaY) / threshold).toInt().coerceAtLeast(1)
        val direction = if (nextCumulativeDeltaY < 0f) 1 else -1
        val consumedDelta = if (nextCumulativeDeltaY < 0f) -steps * threshold else steps * threshold
        val remainingDelta = nextCumulativeDeltaY - consumedDelta
        val targetRow = (focusedRow + direction * steps).coerceIn(0, rowCount - 1)
        val targetColumn = hoveredColumn.coerceIn(0, columnCount - 1)
        return PopupKeyboardFocusResult(
            dismiss = false,
            targetRow = targetRow,
            targetColumn = targetColumn,
            lastTouchY = nextLastTouchY,
            cumulativeDeltaY = remainingDelta
        )
    }

    private fun isDismissed(
        x: Float,
        y: Float,
        rowCount: Int,
        columnCount: Int,
        keyWidth: Int,
        keyHeight: Int
    ): Boolean {
        return x < -(DismissPaddingCells * keyWidth) ||
                x >= (columnCount + DismissPaddingCells) * keyWidth ||
                y < -(DismissPaddingCells * keyHeight) ||
                y >= (rowCount + DismissPaddingCells) * keyHeight
    }

    private fun isInsideGrid(
        x: Float,
        y: Float,
        rowCount: Int,
        columnCount: Int,
        keyWidth: Int,
        keyHeight: Int
    ): Boolean {
        return x >= 0f &&
                x < columnCount * keyWidth &&
                y >= 0f &&
                y < rowCount * keyHeight
    }

    private fun hoveredColumn(x: Float, columnCount: Int, keyWidth: Int): Int {
        return floor(x / keyWidth).toInt().coerceIn(0, columnCount - 1)
    }

    private fun hoveredRow(y: Float, rowCount: Int, keyHeight: Int): Int {
        val visualRow = floor(y / keyHeight).toInt().coerceIn(0, rowCount - 1)
        return rowCount - 1 - visualRow
    }
}
