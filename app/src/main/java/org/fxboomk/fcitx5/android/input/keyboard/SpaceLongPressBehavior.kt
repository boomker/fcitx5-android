/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class SpaceLongPressBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    None(R.string.space_behavior_none),
    VoiceHold(R.string.space_behavior_voice_input),
    Enumerate(R.string.space_behavior_enumerate),
    RepeatSpace(R.string.space_behavior_repeat_space),
    ToggleActivate(R.string.space_behavior_activate),
    SwitchToEnglish(R.string.space_behavior_switch_to_english);

    companion object {
        /**
         * Order displayed in the settings dropdown. Keep this in sync with
         * the actual entryValues used by AppPrefs; the enum declaration alone
         * does not control the visible order.
         */
        val settingsEntries: List<SpaceLongPressBehavior> = listOf(
            None,
            VoiceHold,
            Enumerate,
            RepeatSpace,
            SwitchToEnglish
        )

        /** Decode both the current name and names persisted by older versions. */
        fun fromPersistedName(raw: String): SpaceLongPressBehavior = when (raw) {
            ToggleActivate.name -> Enumerate
            "ShowPicker" -> Enumerate
            // The behavior was renamed from VoiceInput to VoiceHold; keep
            // existing users' hold-to-talk setting instead of resetting it.
            "VoiceInput" -> VoiceHold
            else -> enumValueOf(raw)
        }
    }
}
