/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeIdleBehavior

/** Read and migrate legacy checkbox backups before treating the same key as an enum. */
internal class SpaceSwipeIdlePreference(sharedPreferences: SharedPreferences) :
    ManagedPreference<SpaceSwipeIdleBehavior>(sharedPreferences, "space_swipe_idle_behavior", SpaceSwipeIdleBehavior.Default) {

    override fun setValue(value: SpaceSwipeIdleBehavior) {
        sharedPreferences.edit { putString(key, value.name) }
    }

    override fun getValue(): SpaceSwipeIdleBehavior {
        val stored = sharedPreferences.all[key]
        val value = SpaceSwipeIdleBehavior.fromStoredValue(stored)
        if (stored != null && stored != value.name) setValue(value)
        return value
    }

    override fun putValueTo(editor: SharedPreferences.Editor) {
        editor.putString(key, getValue().name)
    }
}
