/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceSwipeIdleBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    UpSelect(R.string.space_swipe_idle_up_select),
    DownCursor(R.string.space_swipe_idle_down_cursor),
    Default(R.string.default_);

    companion object {
        fun updateSelection(
            selected: Set<String>,
            behavior: SpaceSwipeIdleBehavior,
            checked: Boolean
        ): Set<String> {
            if (behavior == Default && checked) return emptySet()
            val gestures = selected.intersect(setOf(UpSelect.name, DownCursor.name))
            if (behavior == Default) return gestures
            return if (checked) gestures + behavior.name else gestures - behavior.name
        }
    }
}
