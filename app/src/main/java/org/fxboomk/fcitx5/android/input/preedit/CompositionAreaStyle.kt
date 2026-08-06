/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class CompositionAreaStyle(override val stringRes: Int) : ManagedPreferenceEnum {
    Default(R.string.preedit_style_default),
    InlineCandidateBar(R.string.preedit_style_inline_candidate_bar);
}
