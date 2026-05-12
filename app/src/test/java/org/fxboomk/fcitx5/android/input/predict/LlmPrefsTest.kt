/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPrefsTest {

    @Test
    fun localRuntimeIsUsableWithoutBaseUrl() {
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertTrue(config.isUsable)
        assertTrue(config.isLocalOnDevice)
    }

    @Test
    fun readUsesLocalRuntimeWhenProviderIsLocalAi() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_ENABLED to true,
                LlmPrefs.KEY_PROVIDER to LlmPrefs.Provider.LocalAI.value,
                LlmPrefs.KEY_RUNTIME to LlmPrefs.Runtime.Remote.value,
                LlmPrefs.KEY_MODEL to "",
            )
        )

        val config = LlmPrefs.read(prefs)

        assertEquals(LlmPrefs.Runtime.LocalOnDevice, config.runtime)
        assertEquals(LlmPrefs.Provider.LocalAI, config.provider)
        assertTrue(config.isLocalOnDevice)
        assertEquals("qwen3-0.6b-onnx-local", config.model)
    }

    @Test
    fun completionCompatEndpointsTryKnownOpenAiCompatiblePrefixesForCustomBaseHost() {
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
            provider = LlmPrefs.Provider.Anthropic,
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
        assertEquals("https://api.anthropic.com/v1/messages", config.chatEndpoint)
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.Completion,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.OpenAI,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.Gemini,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.DeepSeek,
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
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.MiniMax,
            baseUrl = "",
            model = "",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("https://api.minimaxi.com/anthropic/v1/messages", config.chatEndpoint)
        assertEquals("https://api.minimaxi.com/anthropic/v1/models", config.modelsEndpoint)
        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/messages",
                "https://api.minimax.io/anthropic/v1/messages",
            ),
            config.chatCompatEndpoints,
        )
        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/models",
                "https://api.minimax.io/anthropic/v1/models",
            ),
            config.modelsCompatEndpoints,
        )
        assertEquals("MiniMax-M2.7", LlmPrefs.providerDefaultModel(LlmPrefs.Provider.MiniMax))
    }

    @Test
    fun builtInMiniMaxProviderKeepsOfficialIoHostAsFallbackWhenUserUsesComHost() {
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/anthropic",
            model = "MiniMax-M2.7",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/messages",
                "https://api.minimax.io/anthropic/v1/messages",
            ),
            config.chatCompatEndpoints,
        )
    }

    @Test
    fun builtInMiniMaxProviderDoesNotDuplicateV1WhenBaseUrlAlreadyIncludesIt() {
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/anthropic/v1",
            model = "MiniMax-M2.7",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/messages",
                "https://api.minimax.io/anthropic/v1/messages",
            ),
            config.chatCompatEndpoints,
        )
        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/models",
                "https://api.minimax.io/anthropic/v1/models",
            ),
            config.modelsCompatEndpoints,
        )
    }

    @Test
    fun readFallsBackToMiniMaxDefaultModelWhenCurrentModelBlank() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_PROVIDER to LlmPrefs.Provider.MiniMax.value,
                LlmPrefs.KEY_BASE_URL to "https://api.minimaxi.com/anthropic",
                LlmPrefs.KEY_MODEL to "",
                LlmPrefs.KEY_API_KEY to "minimax-key",
            )
        )

        val config = LlmPrefs.read(prefs)

        assertEquals("MiniMax-M2.7", config.model)
    }

    @Test
    fun readUsesScopedApiKeyForProviderAndApiAddress() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_ENABLED to true,
                LlmPrefs.KEY_PROVIDER to LlmPrefs.Provider.OpenAI.value,
                LlmPrefs.KEY_BASE_URL to "https://api.openai.com/v1",
                LlmPrefs.KEY_MODEL to "gpt-4o-mini",
            )
        )
        LlmPrefs.persistScopedApiKey(
            prefs,
            LlmPrefs.Provider.OpenAI,
            "https://api.openai.com/v1",
            "sk-openai-scoped",
        )

        val config = LlmPrefs.read(prefs)

        assertEquals("sk-openai-scoped", config.apiKey)
    }

    @Test
    fun syncScopedApiKeyToActivePreferencesReturnsMatchingStoredKey() {
        val prefs = FakeSharedPreferences()
        LlmPrefs.persistScopedApiKey(
            prefs,
            LlmPrefs.Provider.DeepSeek,
            "https://api.deepseek.com",
            "sk-deepseek",
        )
        LlmPrefs.persistScopedApiKey(
            prefs,
            LlmPrefs.Provider.OpenAI,
            "https://api.openai.com/v1",
            "sk-openai",
        )

        val restored = LlmPrefs.syncScopedApiKeyToActivePreferences(
            prefs,
            LlmPrefs.Provider.DeepSeek,
            "https://api.deepseek.com",
        )

        assertEquals("sk-deepseek", restored)
        assertEquals("sk-deepseek", prefs.getString(LlmPrefs.KEY_API_KEY, ""))
    }

    @Test
    fun customProviderUsesPersistedDefaultBaseUrl() {
        val prefs = FakeSharedPreferences()

        LlmPrefs.persistCustomDefaultBaseUrl(prefs, "10.0.0.9:9000")

        assertEquals(
            "http://10.0.0.9:9000",
            LlmPrefs.providerDefaultBaseUrl(LlmPrefs.Provider.Custom, prefs),
        )
    }

    @Test
    fun customProviderUsesPersistedDefaultApiKeyWhenScopeEmpty() {
        val prefs = FakeSharedPreferences()

        LlmPrefs.persistScopedApiKey(
            prefs,
            LlmPrefs.Provider.Custom,
            "http://10.0.0.9:9000",
            "sk-custom-default",
        )

        assertEquals(
            "sk-custom-default",
            LlmPrefs.getScopedApiKey(
                prefs,
                LlmPrefs.Provider.Custom,
                "http://10.0.0.10:9000",
            ),
        )
    }

    @Test
    fun readUsesScopedModelForProviderAndApiAddress() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_PROVIDER to LlmPrefs.Provider.Custom.value,
                LlmPrefs.KEY_BASE_URL to "http://192.168.10.45:11444",
                LlmPrefs.KEY_MODEL to "grok-4-1-fast-non-reasoning",
            )
        )
        LlmPrefs.persistScopedModel(
            prefs,
            LlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
            "qwen3-8b",
        )

        val config = LlmPrefs.read(prefs)

        assertEquals("qwen3-8b", config.model)
    }

    @Test
    fun syncScopedModelToActivePreferencesRestoresModelForMatchingScope() {
        val prefs = FakeSharedPreferences()
        LlmPrefs.persistScopedModel(
            prefs,
            LlmPrefs.Provider.DeepSeek,
            "https://api.deepseek.com",
            "deepseek-chat",
        )
        LlmPrefs.persistScopedModel(
            prefs,
            LlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
            "qwen3-8b",
        )

        val restored = LlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            LlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
        )

        assertEquals("qwen3-8b", restored)
        assertEquals("qwen3-8b", prefs.getString(LlmPrefs.KEY_MODEL, ""))
    }

    @Test
    fun syncScopedModelToActivePreferencesUsesMiniMaxDefaultWhenScopeEmpty() {
        val prefs = FakeSharedPreferences()

        val restored = LlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            LlmPrefs.Provider.MiniMax,
            "https://api.minimaxi.com/anthropic",
        )

        assertEquals("MiniMax-M2.7", restored)
        assertEquals("MiniMax-M2.7", prefs.getString(LlmPrefs.KEY_MODEL, ""))
    }

    @Test
    fun syncScopedModelToActivePreferencesMigratesLegacyCurrentValueOnlyForActiveScope() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_MODEL to "qwen3-8b",
            )
        )

        val restored = LlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            LlmPrefs.Provider.Custom,
            "http://192.168.10.45:11444",
            legacyFallback = "qwen3-8b",
        )

        assertEquals("qwen3-8b", restored)
        assertEquals(
            "qwen3-8b",
            LlmPrefs.getScopedModel(
                prefs,
                LlmPrefs.Provider.Custom,
                "http://192.168.10.45:11444",
            ),
        )
    }

    @Test
    fun readSupportsIntBackedSampleCountAndMaxOutputTokens() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_PROVIDER to LlmPrefs.Provider.Custom.value,
                LlmPrefs.KEY_AUTO_PREDICT_ENABLED to true,
                LlmPrefs.KEY_SAMPLE_COUNT to 6,
                LlmPrefs.KEY_MAX_OUTPUT_TOKENS to 256,
                LlmPrefs.KEY_MAX_PREDICTION_CANDIDATES to 7,
            )
        )

        val config = LlmPrefs.read(prefs)

        assertEquals(6, config.sampleCount)
        assertEquals(256, config.maxOutputTokens)
        assertEquals(7, config.maxPredictionCandidates)
        assertEquals(true, config.autoPredictEnabled)
    }

    @Test
    fun readUsesInternalFixedDebounce() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                "llm_debounce_ms" to 999,
            )
        )

        val config = LlmPrefs.read(prefs)

        assertEquals(200L, config.debounceMs)
    }

    @Test
    fun readCustomPersonaNamesSortsAndFiltersBlankValues() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_CUSTOM_PERSONA_NAMES to setOf("  ", "活泼学姐", "冷静顾问"),
            )
        )

        assertEquals(
            listOf("冷静顾问", "活泼学姐"),
            LlmPrefs.readCustomPersonaNames(prefs),
        )
    }

    @Test
    fun personaDetailsAreStoredPerPersonaValue() {
        val prefs = FakeSharedPreferences()

        LlmPrefs.writePersonaDetail(prefs, LlmPrefs.PersonaPreset.Custom.value, "默认详情")
        LlmPrefs.writePersonaDetail(prefs, "活泼学姐", "学姐详情")

        assertEquals("默认详情", LlmPrefs.readPersonaDetail(prefs, LlmPrefs.PersonaPreset.Custom.value))
        assertEquals("学姐详情", LlmPrefs.readPersonaDetail(prefs, "活泼学姐"))
    }

    @Test
    fun readPreservesVendorProviderSampleCount() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_PROVIDER to LlmPrefs.Provider.OpenAI.value,
                LlmPrefs.KEY_SAMPLE_COUNT to 6,
            )
        )

        val config = LlmPrefs.read(prefs)

        assertEquals(6, config.sampleCount)
    }

    @Test
    fun migrateSeekBarBackedPreferencesConvertsLegacySampleCountStringToInt() {
        val prefs = FakeSharedPreferences(
            mutableMapOf(
                LlmPrefs.KEY_SAMPLE_COUNT to "8",
                LlmPrefs.KEY_MAX_PREDICTION_CANDIDATES to "10",
                LlmPrefs.KEY_MAX_CONTEXT_CHARS to "1024",
            )
        )

        LlmPrefs.migrateSeekBarBackedPreferences(prefs)

        assertEquals(6, prefs.getInt(LlmPrefs.KEY_SAMPLE_COUNT, 0))
        assertEquals(8, prefs.getInt(LlmPrefs.KEY_MAX_PREDICTION_CANDIDATES, 0))
        assertEquals(512, prefs.getInt(LlmPrefs.KEY_MAX_CONTEXT_CHARS, 0))
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
