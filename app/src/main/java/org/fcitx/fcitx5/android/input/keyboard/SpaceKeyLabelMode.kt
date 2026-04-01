/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceKeyLabelMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Default(R.string.space_key_label_mode_default),
    CompactWhenSubMode(R.string.space_key_label_mode_compact_when_submode);
}
