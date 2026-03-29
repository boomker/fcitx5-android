/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

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
        val absoluteTarget = absoluteTarget(x, y, rowCount, columnCount, keyWidth, keyHeight)
        if (absoluteTarget.dismiss) {
            return PopupKeyboardFocusResult(
                dismiss = true,
                lastTouchY = y
            )
        }

        val absoluteRow = absoluteTarget.targetRow ?: focusedRow
        val absoluteColumn = absoluteTarget.targetColumn ?: focusedColumn
        if (absoluteRow != focusedRow || absoluteColumn != focusedColumn) {
            return PopupKeyboardFocusResult(
                dismiss = false,
                targetRow = absoluteRow,
                targetColumn = absoluteColumn,
                lastTouchY = y
            )
        }

        val nextLastTouchY = y
        val nextCumulativeDeltaY = cumulativeDeltaY + (lastTouchY?.let { y - it } ?: 0f)
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
        val targetRow = (focusedRow + direction * steps).coerceIn(0, rowCount - 1)
        return PopupKeyboardFocusResult(
            dismiss = false,
            targetRow = targetRow,
            targetColumn = absoluteColumn,
            lastTouchY = nextLastTouchY,
            cumulativeDeltaY = nextCumulativeDeltaY - consumedDelta
        )
    }

    private fun absoluteTarget(
        x: Float,
        y: Float,
        rowCount: Int,
        columnCount: Int,
        keyWidth: Int,
        keyHeight: Int
    ): PopupKeyboardFocusResult {
        var targetRow = rowCount - (y / keyHeight - 0.2f).roundToInt()
        var targetColumn = floor(x / keyWidth).toInt()
        val dismiss = targetRow < -DismissPaddingCells ||
                targetRow > rowCount + DismissPaddingCells - 1 ||
                targetColumn < -DismissPaddingCells ||
                targetColumn > columnCount + DismissPaddingCells - 1
        if (dismiss) {
            return PopupKeyboardFocusResult(dismiss = true)
        }
        targetRow = PopupContainerUi.limitIndex(targetRow, rowCount)
        targetColumn = PopupContainerUi.limitIndex(targetColumn, columnCount)
        return PopupKeyboardFocusResult(
            dismiss = false,
            targetRow = targetRow,
            targetColumn = targetColumn
        )
    }

    private fun hoveredColumn(x: Float, columnCount: Int, keyWidth: Int): Int {
        return floor(x / keyWidth).toInt().coerceIn(0, columnCount - 1)
    }
}
