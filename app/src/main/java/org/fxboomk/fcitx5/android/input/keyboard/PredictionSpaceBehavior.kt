/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class PredictionSpaceBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    CommitSpace(R.string.prediction_space_behavior_commit_space),
    CommitPrediction(R.string.prediction_space_behavior_commit_prediction);
}
