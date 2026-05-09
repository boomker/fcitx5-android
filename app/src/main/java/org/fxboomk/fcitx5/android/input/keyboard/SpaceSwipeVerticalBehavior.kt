/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceSwipeVerticalBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    ArrowKeys(R.string.space_swipe_vertical_behavior_arrow_keys),
    CandidateRows(R.string.space_swipe_vertical_behavior_candidate_rows);
}
