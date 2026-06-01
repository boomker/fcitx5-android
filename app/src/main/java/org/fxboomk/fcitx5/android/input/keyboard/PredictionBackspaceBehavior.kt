/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class PredictionBackspaceBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    DeleteText(R.string.prediction_backspace_behavior_delete_text),
    DismissCandidates(R.string.prediction_backspace_behavior_dismiss_candidates);
}
