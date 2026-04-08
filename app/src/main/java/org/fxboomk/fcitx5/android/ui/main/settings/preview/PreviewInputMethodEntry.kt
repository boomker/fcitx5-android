/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.preview

import org.fxboomk.fcitx5.android.core.InputMethodEntry

object PreviewInputMethodEntry {

    fun create(
        layoutName: String = "Preview",
        subModeLabel: String? = null,
        base: InputMethodEntry? = null
    ): InputMethodEntry {
        val selectedSubModeLabel = subModeLabel?.trim().orEmpty()
        return base
            ?.copy(
                uniqueName = layoutName,
                name = layoutName,
                subMode = if (selectedSubModeLabel.isNotEmpty()) {
                    base.subMode.copy(
                        label = selectedSubModeLabel,
                        name = selectedSubModeLabel
                    )
                } else {
                    base.subMode.copy(
                        name = "",
                        label = "",
                        icon = ""
                    )
                }
            )
            ?: InputMethodEntry(layoutName)
    }
}
