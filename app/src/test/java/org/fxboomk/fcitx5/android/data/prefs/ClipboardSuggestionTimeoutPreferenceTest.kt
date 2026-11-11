/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClipboardSuggestionTimeoutPreferenceTest {
    @Test
    fun disabledLegacySwitchBecomesZeroAndDoesNotOverrideLaterChanges() {
        val storage = TestStorage()
        storage.values["clipboard_suggestion"] = false
        storage.values["clipboard_item_timeout"] = 5
        storage.values["unrelated"] = "keep"
        val preference = ClipboardSuggestionTimeoutPreference(storage.preferences)

        assertEquals(0, preference.getValue())
        assertEquals(0, storage.values[preference.key])
        assertFalse(storage.values.containsKey("clipboard_suggestion"))
        assertEquals("keep", storage.values["unrelated"])

        preference.setValue(7)
        assertEquals(7, preference.getValue())
        assertEquals(7, ClipboardSuggestionTimeoutPreference(storage.preferences).getValue())
    }

    @Test
    fun enabledLegacySwitchPreservesTimeoutAndNeverTimeout() {
        listOf(-1, 0, 9).forEach { timeout ->
            val storage = TestStorage()
            storage.values["clipboard_suggestion"] = true
            storage.values["clipboard_item_timeout"] = timeout
            val preference = ClipboardSuggestionTimeoutPreference(storage.preferences)

            assertEquals(timeout, preference.getValue())
            assertFalse(storage.values.containsKey("clipboard_suggestion"))
        }
    }

    @Test
    fun legacyBackupRestoredAfterInitializationMigratesWhenRead() {
        val storage = TestStorage()
        val preference = ClipboardSuggestionTimeoutPreference(storage.preferences)
        assertEquals(3, preference.getValue())
        preference.setValue(10)

        storage.preferences.edit()
            .putBoolean("clipboard_suggestion", false)
            .putInt(preference.key, -1)
            .commit()
        assertEquals(0, preference.getValue())
        assertFalse(storage.values.containsKey("clipboard_suggestion"))

        storage.preferences.edit()
            .putBoolean("clipboard_suggestion", true)
            .putInt(preference.key, 6)
            .commit()
        assertEquals(6, preference.getValue())
        assertFalse(storage.values.containsKey("clipboard_suggestion"))
    }

    @Test
    fun exportContainsOnlyTimeoutAndMissingStorageUsesDefault() {
        val storage = TestStorage()
        val preference = ClipboardSuggestionTimeoutPreference(storage.preferences)
        assertEquals(3, preference.getValue())
        assertEquals(emptyMap<String, Any>(), storage.values)
        storage.values["clipboard_suggestion"] = false
        val exported = TestStorage()
        val editor = exported.preferences.edit()
        preference.putValueTo(editor)
        editor.commit()

        assertEquals(mapOf("clipboard_item_timeout" to 0), exported.values)
    }

    @Test
    fun invalidTimeoutStorageResetsToDefaultAndKeepsUnrelatedValues() {
        val storage = TestStorage()
        storage.values["clipboard_item_timeout"] = "invalid"
        storage.values["unrelated"] = true
        val preference = ClipboardSuggestionTimeoutPreference(storage.preferences)

        assertEquals(3, preference.getValue())
        assertEquals(3, storage.values[preference.key])
        assertEquals(true, storage.values["unrelated"])
    }

    private class TestStorage {
        val values = mutableMapOf<String, Any>()
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { editor, method, args ->
                when (method.name) {
                    "putInt", "putBoolean" -> {
                        pending[args[0] as String] = args[1]
                        editor
                    }
                    "remove" -> {
                        pending[args[0] as String] = null
                        editor
                    }
                    "apply", "commit" -> {
                        pending.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
