/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmThinkingSuppressionStoreTest {
    @Test
    fun suppressPersistsByProviderBaseUrlModelAndProtocol() {
        val store = LanLlmSharedPrefsThinkingSuppressionStore(FakeSharedPreferences())
        val config = config(model = "claude-3-7-sonnet")

        assertFalse(store.isSuppressed(config, "anthropic_messages"))

        store.suppress(config, "anthropic_messages")

        assertTrue(store.isSuppressed(config, "anthropic_messages"))
        assertFalse(store.isSuppressed(config.copy(model = "other-model"), "anthropic_messages"))
        assertFalse(store.isSuppressed(config, "openai_chat_completions"))
    }

    private fun config(model: String): LanLlmPrefs.Config = LanLlmPrefs.Config(
        enabled = true,
        backend = LanLlmPrefs.Backend.ChatCompletions,
        provider = LanLlmPrefs.Provider.Anthropic,
        baseUrl = "https://api.anthropic.com/v1",
        model = model,
        apiKey = "sk-ant-test",
        debounceMs = 450,
        sampleCount = 1,
        maxContextChars = 64,
        preferLastCommit = true,
    )

    private class FakeSharedPreferences(
        private val data: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = data

        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (data[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            data[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            data[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            data[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(data)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private class Editor(
            private val data: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) data.clear()
                pending.forEach { (key, value) ->
                    if (value == null) data.remove(key) else data[key] = value
                }
                pending.clear()
                clearRequested = false
            }
        }
    }
}
