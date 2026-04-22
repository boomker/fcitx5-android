/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class LanLlmPrefsTest {

    @Test
    fun completionCompatEndpointsTryKnownOpenAiCompatiblePrefixesForCustomBaseHost() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:11434",
            model = "scirime-ime",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/chat/completions",
                "http://127.0.0.1:11434/api/chat/completions",
                "http://127.0.0.1:11434/v1/api/chat/completions",
                "http://127.0.0.1:11434/chat/completions",
            ),
            config.completionCompatEndpoints,
        )
        assertEquals("http://127.0.0.1:11434/v1/models", config.modelsEndpoint)
        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/models",
                "http://127.0.0.1:11434/api/models",
                "http://127.0.0.1:11434/v1/api/models",
                "http://127.0.0.1:11434/models",
            ),
            config.modelsCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsKeepsExplicitCompletionEndpointOnly() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:8080/completion",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf(
                "http://127.0.0.1:8080/v1/chat/completions",
                "http://127.0.0.1:8080/api/chat/completions",
                "http://127.0.0.1:8080/v1/api/chat/completions",
                "http://127.0.0.1:8080/chat/completions",
            ),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsMigratesLegacyOllamaEndpointToOpenAiAndAnthropicCandidates() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:11434/api/generate",
            model = "scirime-ime",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/chat/completions",
                "http://127.0.0.1:11434/api/chat/completions",
                "http://127.0.0.1:11434/v1/api/chat/completions",
                "http://127.0.0.1:11434/chat/completions",
            ),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsMigratesLegacyResponsesEndpointToOpenAiAndAnthropicCandidates() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:1337/v1/responses",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf("http://127.0.0.1:1337/v1/chat/completions"),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsUsesOpenAiOnlyForGenericV1Base() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:1337/v1",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf("http://127.0.0.1:1337/v1/chat/completions"),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsUsesAnthropicForClaudeModel() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            provider = LanLlmPrefs.Provider.Anthropic,
            baseUrl = "https://api.anthropic.com/v1",
            model = "claude-3-7-sonnet",
            apiKey = "sk-ant-test",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf("https://api.anthropic.com/v1/messages"),
            config.completionCompatEndpoints,
        )
        assertEquals(
            listOf("https://api.anthropic.com/v1/messages"),
            config.chatCompatEndpoints,
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            config.modelsEndpoint,
        )
        assertEquals(
            listOf("https://api.anthropic.com/v1/models"),
            config.modelsCompatEndpoints,
        )
    }

    @Test
    fun chatEndpointNormalizesFromExplicitCompletionEndpoint() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "http://127.0.0.1:8080/completion",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            config.chatEndpoint,
        )
    }

    @Test
    fun completionEndpointNormalizesFromExplicitChatEndpoint() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:11434/v1/chat/completions",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            "http://127.0.0.1:11434/v1/messages",
            config.messagesEndpoint,
        )
    }

    @Test
    fun builtInOpenAiProviderUsesDefaultBaseUrlWhenApiAddressBlank() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            provider = LanLlmPrefs.Provider.OpenAI,
            baseUrl = "",
            model = "gpt-4o-mini",
            apiKey = "sk-openai",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("https://api.openai.com/v1", config.resolvedBaseUrl)
        assertEquals("https://api.openai.com/v1/chat/completions", config.chatEndpoint)
        assertEquals("https://api.openai.com/v1/models", config.modelsEndpoint)
    }

    @Test
    fun builtInGeminiProviderUsesOpenAiCompatibleDefaultBaseUrl() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            provider = LanLlmPrefs.Provider.Gemini,
            baseUrl = "",
            model = "gemini-2.5-flash",
            apiKey = "AIza-test",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            config.chatEndpoint,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            config.modelsEndpoint,
        )
    }

    @Test
    fun builtInDeepSeekProviderUsesVendorDefaultBaseUrl() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            provider = LanLlmPrefs.Provider.DeepSeek,
            baseUrl = "",
            model = "deepseek-chat",
            apiKey = "sk-deepseek",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("https://api.deepseek.com/chat/completions", config.chatEndpoint)
        assertEquals("https://api.deepseek.com/models", config.modelsEndpoint)
    }

    @Test
    fun builtInMiniMaxProviderUsesUpdatedVendorDefaultBaseUrl() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            provider = LanLlmPrefs.Provider.MiniMax,
            baseUrl = "",
            model = "",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("https://api.minimaxi.com/v1/chat/completions", config.chatEndpoint)
        assertEquals("https://api.minimaxi.com/v1/models", config.modelsEndpoint)
        assertEquals("MiniMax-M2.7", LanLlmPrefs.providerDefaultModel(LanLlmPrefs.Provider.MiniMax))
    }

    @Test
    fun readFallsBackToMiniMaxDefaultModelWhenCurrentModelBlank() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_PROVIDER to LanLlmPrefs.Provider.MiniMax.value,
                LanLlmPrefs.KEY_BASE_URL to "https://api.minimaxi.com/v1",
                LanLlmPrefs.KEY_MODEL to "",
                LanLlmPrefs.KEY_API_KEY to "minimax-key",
            )
        )

        val config = LanLlmPrefs.read(prefs)

        assertEquals("MiniMax-M2.7", config.model)
    }

    @Test
    fun readUsesScopedApiKeyForProviderAndApiAddress() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_ENABLED to true,
                LanLlmPrefs.KEY_PROVIDER to LanLlmPrefs.Provider.OpenAI.value,
                LanLlmPrefs.KEY_BASE_URL to "https://api.openai.com/v1",
                LanLlmPrefs.KEY_MODEL to "gpt-4o-mini",
            )
        )
        LanLlmPrefs.persistScopedApiKey(
            prefs,
            LanLlmPrefs.Provider.OpenAI,
            "https://api.openai.com/v1",
            "sk-openai-scoped",
        )

        val config = LanLlmPrefs.read(prefs)

        assertEquals("sk-openai-scoped", config.apiKey)
    }

    @Test
    fun syncScopedApiKeyToActivePreferencesReturnsMatchingStoredKey() {
        val prefs = FakeSharedPreferences()
        LanLlmPrefs.persistScopedApiKey(
            prefs,
            LanLlmPrefs.Provider.DeepSeek,
            "https://api.deepseek.com",
            "sk-deepseek",
        )
        LanLlmPrefs.persistScopedApiKey(
            prefs,
            LanLlmPrefs.Provider.OpenAI,
            "https://api.openai.com/v1",
            "sk-openai",
        )

        val restored = LanLlmPrefs.syncScopedApiKeyToActivePreferences(
            prefs,
            LanLlmPrefs.Provider.DeepSeek,
            "https://api.deepseek.com",
        )

        assertEquals("sk-deepseek", restored)
        assertEquals("sk-deepseek", prefs.getString(LanLlmPrefs.KEY_API_KEY, ""))
    }

    @Test
    fun readUsesScopedModelForProviderAndApiAddress() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_PROVIDER to LanLlmPrefs.Provider.Custom.value,
                LanLlmPrefs.KEY_BASE_URL to "http://192.168.10.45:11444",
                LanLlmPrefs.KEY_MODEL to "grok-4-1-fast-non-reasoning",
            )
        )
        LanLlmPrefs.persistScopedModel(
            prefs,
            LanLlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
            "qwen3-8b",
        )

        val config = LanLlmPrefs.read(prefs)

        assertEquals("qwen3-8b", config.model)
    }

    @Test
    fun syncScopedModelToActivePreferencesRestoresModelForMatchingScope() {
        val prefs = FakeSharedPreferences()
        LanLlmPrefs.persistScopedModel(
            prefs,
            LanLlmPrefs.Provider.DeepSeek,
            "https://api.deepseek.com",
            "deepseek-chat",
        )
        LanLlmPrefs.persistScopedModel(
            prefs,
            LanLlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
            "qwen3-8b",
        )

        val restored = LanLlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            LanLlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
        )

        assertEquals("qwen3-8b", restored)
        assertEquals("qwen3-8b", prefs.getString(LanLlmPrefs.KEY_MODEL, ""))
    }

    @Test
    fun syncScopedModelToActivePreferencesUsesMiniMaxDefaultWhenScopeEmpty() {
        val prefs = FakeSharedPreferences()

        val restored = LanLlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            LanLlmPrefs.Provider.MiniMax,
            "https://api.minimaxi.com/v1",
        )

        assertEquals("MiniMax-M2.7", restored)
        assertEquals("MiniMax-M2.7", prefs.getString(LanLlmPrefs.KEY_MODEL, ""))
    }

    @Test
    fun syncScopedModelToActivePreferencesMigratesLegacyCurrentValueOnlyForActiveScope() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_MODEL to "qwen3-8b",
            )
        )

        val restored = LanLlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            LanLlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
            legacyFallback = "qwen3-8b",
        )

        assertEquals("qwen3-8b", restored)
        assertEquals(
            "qwen3-8b",
            LanLlmPrefs.getScopedModel(
                prefs,
                LanLlmPrefs.Provider.Custom,
                "http://192.168.10.45:11444",
            ),
        )
    }

    @Test
    fun readSupportsIntBackedSampleCountAndMaxOutputTokens() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_PROVIDER to LanLlmPrefs.Provider.Custom.value,
                LanLlmPrefs.KEY_SAMPLE_COUNT to 6,
                LanLlmPrefs.KEY_MAX_OUTPUT_TOKENS to 256,
                LanLlmPrefs.KEY_MAX_PREDICTION_CANDIDATES to 7,
            )
        )

        val config = LanLlmPrefs.read(prefs)

        assertEquals(6, config.sampleCount)
        assertEquals(256, config.maxOutputTokens)
        assertEquals(7, config.maxPredictionCandidates)
    }

    @Test
    fun readForcesVendorProviderSampleCountToOne() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_PROVIDER to LanLlmPrefs.Provider.OpenAI.value,
                LanLlmPrefs.KEY_SAMPLE_COUNT to 6,
            )
        )

        val config = LanLlmPrefs.read(prefs)

        assertEquals(1, config.sampleCount)
    }

    @Test
    fun migrateSeekBarBackedPreferencesConvertsLegacySampleCountStringToInt() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LanLlmPrefs.KEY_SAMPLE_COUNT to "8",
                LanLlmPrefs.KEY_MAX_PREDICTION_CANDIDATES to "10",
            )
        )

        LanLlmPrefs.migrateSeekBarBackedPreferences(prefs)

        assertEquals(6, prefs.getInt(LanLlmPrefs.KEY_SAMPLE_COUNT, 0))
        assertEquals(8, prefs.getInt(LanLlmPrefs.KEY_MAX_PREDICTION_CANDIDATES, 0))
    }

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
