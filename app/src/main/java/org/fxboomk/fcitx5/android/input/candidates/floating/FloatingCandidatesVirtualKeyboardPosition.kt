/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.floating

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Position for floating candidates window when using virtual keyboard
 */
enum class FloatingCandidatesVirtualKeyboardPosition(override val stringRes: Int) : ManagedPreferenceEnum {
    /**
     * Top-left corner above virtual keyboard
     */
    TopLeft(R.string.top_left),
    /**
     * Top-right corner above virtual keyboard
     */
    TopRight(R.string.top_right),
    /**
     * Bottom-left corner (above navigation bar, below keyboard)
     */
    BottomLeft(R.string.bottom_left),
    /**
     * Bottom-right corner (above navigation bar, below keyboard)
     */
    BottomRight(R.string.bottom_right)
}
