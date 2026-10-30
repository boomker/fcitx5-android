/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class CapsKeyBehavior(override val stringRes: Int) : ManagedPreferenceEnum {
    Default(R.string.caps_key_behavior_default),
    SingleTapLock(R.string.caps_key_behavior_single_tap_lock);
}

internal fun shouldLockCapsAction(actionLock: Boolean, behavior: CapsKeyBehavior): Boolean =
    actionLock || behavior == CapsKeyBehavior.SingleTapLock

/**
 * MacroKey Caps_Lock is an explicit virtual lock and must survive ordinary typing.
 * Physical typing should only reset transient/local caps state when no macro lock is active.
 */
internal fun shouldResetCapsStateOnHardwareTyping(macroCapsLockOn: Boolean): Boolean =
    !macroCapsLockOn
