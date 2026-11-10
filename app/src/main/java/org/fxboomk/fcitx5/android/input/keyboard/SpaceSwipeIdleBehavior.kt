/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceSwipeIdleBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    SelectionThenCursor(R.string.space_swipe_idle_selection_cursor),
    CursorThenSelection(R.string.space_swipe_idle_cursor_selection),
    SelectionOnly(R.string.space_swipe_idle_selection_only),
    CursorOnly(R.string.space_swipe_idle_cursor_only),
    Default(R.string.space_swipe_idle_default);

    companion object {
        /** Accept the previous checkbox storage as well as the current single enum value. */
        fun fromStoredValue(value: Any?): SpaceSwipeIdleBehavior = when (value) {
            is Set<*> -> when {
                "UpSelect" in value && "DownCursor" in value -> SelectionThenCursor
                "UpSelect" in value -> SelectionOnly
                "DownCursor" in value -> CursorOnly
                else -> Default
            }
            is String -> entries.firstOrNull { it.name == value } ?: when (value) {
                "UpSelect" -> SelectionOnly
                "DownCursor" -> CursorOnly
                else -> Default
            }
            else -> Default
        }
    }
}
