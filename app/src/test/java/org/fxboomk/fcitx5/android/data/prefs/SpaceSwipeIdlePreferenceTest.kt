/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeIdleBehavior
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceSwipeIdlePreferenceTest {
    @Test
    fun existingCheckboxStorageMigratesToOneStringWithoutChangingOtherPreferences() {
        val cases = mapOf(
            emptySet<String>() to SpaceSwipeIdleBehavior.Default,
            setOf("UpSelect", "DownCursor") to SpaceSwipeIdleBehavior.SelectionThenCursor,
            setOf("UpSelect") to SpaceSwipeIdleBehavior.SelectionOnly,
            setOf("DownCursor") to SpaceSwipeIdleBehavior.CursorOnly,
        )
        cases.forEach { (legacy, expected) ->
            val storage = TestStorage()
            storage.values["space_swipe_idle_behavior"] = legacy
            storage.values["unrelated"] = "keep"
            val preference = SpaceSwipeIdlePreference(storage.preferences)
            assertEquals(expected, preference.getValue())
            assertEquals(expected.name, storage.values[preference.key])
            assertEquals("keep", storage.values["unrelated"])
            assertEquals(expected, SpaceSwipeIdlePreference(storage.preferences).getValue())
        }
    }

    @Test
    fun legacyBackupImportedAfterInitializationAlsoMigratesBeforeRead() {
        val storage = TestStorage()
        val preference = SpaceSwipeIdlePreference(storage.preferences)
        preference.setValue(SpaceSwipeIdleBehavior.CursorThenSelection)
        storage.preferences.edit().putStringSet(preference.key, setOf("UpSelect", "DownCursor")).commit()
        assertEquals(SpaceSwipeIdleBehavior.SelectionThenCursor, preference.getValue())
        assertEquals("SelectionThenCursor", storage.values[preference.key])
    }

    @Test
    fun enumValuesSaveAndExportAsSingleStrings() {
        val storage = TestStorage()
        val preference = SpaceSwipeIdlePreference(storage.preferences)
        val exported = TestStorage()
        SpaceSwipeIdleBehavior.entries.forEach { expected ->
            preference.setValue(expected)
            assertEquals(expected, preference.getValue())
            val editor = exported.preferences.edit()
            preference.putValueTo(editor)
            editor.commit()
            assertEquals(expected.name, exported.values[preference.key])
        }
    }

    @Test
    fun missingOrInvalidStorageDoesNotThrowOrAffectOtherValues() {
        val storage = TestStorage()
        val preference = SpaceSwipeIdlePreference(storage.preferences)
        assertEquals(SpaceSwipeIdleBehavior.Default, preference.getValue())
        assertEquals(emptyMap<String, Any>(), storage.values)
        storage.values[preference.key] = 42
        assertEquals(SpaceSwipeIdleBehavior.Default, preference.getValue())
        assertEquals("Default", storage.values[preference.key])
    }

    /** In-memory implementation of the SharedPreferences storage contract used by this preference. */
    private class TestStorage {
        val values = mutableMapOf<String, Any>()
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { editor, method, args ->
                when (method.name) {
                    "putString", "putStringSet" -> {
                        pending[args[0] as String] = args[1]
                        editor
                    }
                    "apply", "commit" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
