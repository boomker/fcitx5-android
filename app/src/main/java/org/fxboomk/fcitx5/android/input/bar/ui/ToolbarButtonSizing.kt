/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui

import androidx.core.view.updateLayoutParams
import splitties.dimensions.dp

internal const val TOOLBAR_BUTTON_WIDTH_DP = 40

/** Keep toolbar icons at their normal 40dp-row size regardless of the row height. */
internal fun ToolButton.applyToolbarIconSize() {
    image.setPadding(0, 0, 0, 0)
    image.updateLayoutParams {
        width = context.dp(20)
        height = context.dp(20)
    }
}
