/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

/**
 * Decides whether the floating keyboard move handle should be placed above or
 * below the keyboard. When both sides fit, the current placement is kept so the
 * handle does not jump while the keyboard is being dragged.
 */
object FloatingMoveHandlePlacement {
    const val ABOVE = 0
    const val BELOW = 1

    fun placement(
        keyboardTop: Float,
        keyboardHeight: Float,
        containerHeight: Float,
        needAbove: Float,
        needBelow: Float,
        currentlyAbove: Boolean?
    ): Int {
        val aboveFits = keyboardTop >= needAbove
        val belowFits = keyboardTop + keyboardHeight + needBelow <= containerHeight
        return when {
            aboveFits && belowFits -> if (currentlyAbove == false) BELOW else ABOVE
            aboveFits -> ABOVE
            belowFits -> BELOW
            else -> if (currentlyAbove == false) BELOW else ABOVE
        }
    }
}
