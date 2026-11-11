/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit

/** Migrate the removed suggestion switch, including when reading a restored backup. */
internal class ClipboardSuggestionTimeoutPreference(sharedPreferences: SharedPreferences) :
    ManagedPreference<Int>(sharedPreferences, "clipboard_item_timeout", 3) {

    private val legacyKey = "clipboard_suggestion"

    override fun setValue(value: Int) {
        sharedPreferences.edit {
            putInt(key, value)
            remove(legacyKey)
        }
    }

    override fun getValue(): Int {
        val stored = sharedPreferences.all
        val timeout = stored[key] as? Int ?: defaultValue
        val value = if (stored[legacyKey] == false) 0 else timeout
        if (legacyKey in stored || (key in stored && stored[key] !is Int)) {
            setValue(value)
        }
        return value
    }

    override fun putValueTo(editor: SharedPreferences.Editor) {
        editor.putInt(key, getValue())
    }
}
