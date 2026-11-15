/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.common.preference

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference

private val mDefault by lazy {
    Preference::class.java
        .getDeclaredField("mDefaultValue")
        .apply { isAccessible = true }
}

private fun <T : Preference> T.def() =
    mDefault.get(this)

fun <T : EditTextPreference> T.resetToDefault() {
    // must `callChangeListener` before `setText`
    // https://android.googlesource.com/platform/frameworks/support/+/872b66efac82f0b0a3fac4bb14a789464ab19f96/preference/preference/src/main/java/androidx/preference/EditTextPreferenceDialogFragmentCompat.java#146
    (def() as? String)?.let {
        if (callChangeListener(it)) {
            text = it
        }
    }
}

fun <T : ListPreference> T.resetToDefault() {
    (def() as? String)?.let {
        if (callChangeListener(it)) {
            value = it
        }
    }
}

fun <T : SwitchPreference> T.resetToDefault() {
    (def() as? Boolean)?.let {
        if (callChangeListener(it)) {
            isChecked = it
        }
    }
}
