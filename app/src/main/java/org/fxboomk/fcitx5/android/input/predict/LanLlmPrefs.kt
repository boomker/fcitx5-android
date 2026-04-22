package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.fxboomk.fcitx5.android.R

object LanLlmPrefs {
    const val KEY_BACKEND = "lan_llm_backend"
    const val KEY_CHAT_API_ENABLED = "lan_llm_chat_api_enabled"
    const val KEY_ENABLED = "lan_llm_enabled"
    const val KEY_PROVIDER = "lan_llm_provider"
    const val KEY_BASE_URL = "lan_llm_base_url"
    const val KEY_MODEL = "lan_llm_model"
    const val KEY_API_KEY = "lan_llm_api_key"
    private const val KEY_MODEL_SCOPE_PREFIX = "lan_llm_model_scope_"
    private const val KEY_API_KEY_SCOPE_PREFIX = "lan_llm_api_key_scope_"
    const val KEY_DEBOUNCE_MS = "lan_llm_debounce_ms"
    const val KEY_SAMPLE_COUNT = "lan_llm_sample_count"
    const val KEY_MAX_OUTPUT_TOKENS = "lan_llm_max_output_tokens"
    const val KEY_MAX_PREDICTION_CANDIDATES = "lan_llm_max_prediction_candidates"
    const val KEY_MAX_CONTEXT_CHARS = "lan_llm_max_context_chars"

    enum class Backend(val value: String) {
        ChatCompletions("chat"),
        Completion("completion");

        companion object {
            fun from(value: String?): Backend = entries.firstOrNull { it.value == value } ?: ChatCompletions
        }
    }

    enum class CompatApi {
        OpenAI,
        Anthropic,
    }

    enum class Provider(
        val value: String,
        val titleRes: Int,
        val defaultBaseUrl: String?,
        val compatApi: CompatApi?,
        val defaultModel: String? = null,
    ) {
        Custom("custom", R.string.lan_llm_provider_custom, null, null),
        OpenAI("openai", R.string.lan_llm_provider_openai, "https://api.openai.com/v1", CompatApi.OpenAI),
        Anthropic("anthropic", R.string.lan_llm_provider_anthropic, "https://api.anthropic.com/v1", CompatApi.Anthropic),
        Gemini("gemini", R.string.lan_llm_provider_gemini, "https://generativelanguage.googleapis.com/v1beta/openai", CompatApi.OpenAI),
        DeepSeek("deepseek", R.string.lan_llm_provider_deepseek, "https://api.deepseek.com", CompatApi.OpenAI),
        Zhipu("zhipu", R.string.lan_llm_provider_zhipu, "https://open.bigmodel.cn/api/paas/v4", CompatApi.OpenAI),
        MiniMax(
            "minimax",
            R.string.lan_llm_provider_minimax,
            "https://api.minimaxi.com/v1",
            CompatApi.OpenAI,
            "MiniMax-M2.7",
        );

        val isVendorProvidedApiService: Boolean
            get() = this != Custom

        companion object {
            fun from(value: String?): Provider = entries.firstOrNull { it.value == value } ?: Custom
        }
    }

    data class Overrides(
        val provider: Provider? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val apiKey: String? = null,
        val backend: Backend? = null,
    )

    private const val DEFAULT_BASE_URL = "http://192.168.1.1:8000"
    private const val DEFAULT_MODEL = "qwen"
    private const val DEFAULT_DEBOUNCE_MS = 450
    private const val DEFAULT_SAMPLE_COUNT = 4
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 512
    private const val DEFAULT_MAX_PREDICTION_CANDIDATES = 4
    private const val DEFAULT_MAX_CONTEXT_CHARS = 64

    data class Config(
        val enabled: Boolean,
        val backend: Backend,
        val provider: Provider = Provider.Custom,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val debounceMs: Long,
        val sampleCount: Int,
        val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        val maxPredictionCandidates: Int = DEFAULT_MAX_PREDICTION_CANDIDATES,
        val maxContextChars: Int,
        val preferLastCommit: Boolean,
    ) {
        val isUsable: Boolean
            get() = enabled && resolvedBaseUrl.isNotBlank() && model.isNotBlank()

        private val inferredCompatApi: CompatApi
            get() = if (
                baseUrl.endsWith("/messages") ||
                baseUrl.contains("anthropic", ignoreCase = true) ||
                model.startsWith("claude", ignoreCase = true) ||
                apiKey.startsWith("sk-ant-", ignoreCase = true)
            ) {
                CompatApi.Anthropic
            } else {
                CompatApi.OpenAI
            }

        val compatApi: CompatApi
            get() = provider.compatApi ?: inferredCompatApi

        val resolvedBaseUrl: String
            get() = normalizeBaseUrl(baseUrl.ifBlank { provider.defaultBaseUrl.orEmpty() })

        private val openAiRoot: String
            get() = trimKnownSuffix(
                resolvedBaseUrl,
                listOf("/chat/completions", "/models", "/completion", "/responses", "/api/generate"),
            )

        private val openAiRoots: List<String>
            get() = if (provider == Provider.Custom && compatApi == CompatApi.OpenAI && hasBareHostPath(openAiRoot)) {
                listOf(
                    joinPrefix(openAiRoot, "/v1"),
                    joinPrefix(openAiRoot, "/api"),
                    joinPrefix(openAiRoot, "/v1/api"),
                    openAiRoot,
                ).distinct()
            } else {
                listOf(openAiRoot)
            }

        private val anthropicRoot: String
            get() = trimKnownSuffix(
                resolvedBaseUrl,
                listOf("/messages", "/models", "/chat/completions", "/completion", "/responses", "/api/generate"),
            )

        val chatEndpoint: String
            get() = openAiRoots.firstOrNull()?.let { root -> joinPath(root, "/chat/completions") }.orEmpty()

        val messagesEndpoint: String
            get() = joinPath(anthropicRoot, "/messages")

        val modelsEndpoint: String
            get() = modelsCompatEndpoints.firstOrNull().orEmpty()

        val modelsCompatEndpoints: List<String>
            get() = when (compatApi) {
                CompatApi.OpenAI -> openAiRoots.map { root -> joinPath(root, "/models") }.distinct()
                CompatApi.Anthropic -> joinPath(anthropicRoot, "/models")
                    .let(::listOf)
            }

        val chatCompatEndpoints: List<String>
            get() = when (compatApi) {
                CompatApi.OpenAI -> openAiRoots.map { root -> joinPath(root, "/chat/completions") }.distinct()
                CompatApi.Anthropic -> listOf(messagesEndpoint)
            }

        val completionCompatEndpoints: List<String>
            get() = chatCompatEndpoints

        val fetchWindowChars: Int
            get() = (maxContextChars * 3).coerceIn(96, 512)
    }

    fun read(context: Context, overrides: Overrides = Overrides()): Config =
        read(PreferenceManager.getDefaultSharedPreferences(context), overrides)

    fun read(prefs: SharedPreferences, overrides: Overrides = Overrides()): Config {
        val debounceMs = readBoundedIntPreference(prefs, KEY_DEBOUNCE_MS, DEFAULT_DEBOUNCE_MS, 100..3000).toLong()
        val chatApiEnabled = if (prefs.contains(KEY_CHAT_API_ENABLED)) {
            prefs.getBoolean(KEY_CHAT_API_ENABLED, false)
        } else {
            prefs.getString(KEY_BACKEND, Backend.Completion.value) == Backend.ChatCompletions.value
        }
        val provider = overrides.provider ?: Provider.from(prefs.getString(KEY_PROVIDER, Provider.Custom.value))
        val sampleCount = if (provider.isVendorProvidedApiService) {
            1
        } else {
            readBoundedIntPreference(prefs, KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT, 1..6)
        }
        val maxOutputTokens = readBoundedIntPreference(
            prefs,
            KEY_MAX_OUTPUT_TOKENS,
            DEFAULT_MAX_OUTPUT_TOKENS,
            1..16384,
        )
        val maxPredictionCandidates = readBoundedIntPreference(
            prefs,
            KEY_MAX_PREDICTION_CANDIDATES,
            DEFAULT_MAX_PREDICTION_CANDIDATES,
            1..8,
        )
        val maxContextChars = readBoundedIntPreference(
            prefs,
            KEY_MAX_CONTEXT_CHARS,
            DEFAULT_MAX_CONTEXT_CHARS,
            8..512,
        )
        val baseUrl = overrides.baseUrl ?: prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            backend = overrides.backend ?: if (chatApiEnabled) Backend.ChatCompletions else Backend.Completion,
            provider = provider,
            baseUrl = normalizeBaseUrl(baseUrl),
            model = (overrides.model ?: getScopedModel(prefs, provider, baseUrl).ifBlank {
                prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            }).trim().ifBlank { providerDefaultModel(provider) },
            apiKey = (overrides.apiKey ?: getScopedApiKey(prefs, provider, baseUrl)).trim(),
            debounceMs = debounceMs,
            sampleCount = sampleCount,
            maxOutputTokens = maxOutputTokens,
            maxPredictionCandidates = maxPredictionCandidates,
            maxContextChars = maxContextChars,
            preferLastCommit = true,
        )
    }

    fun migrateSeekBarBackedPreferences(prefs: SharedPreferences) {
        migrateIntPreference(prefs, KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT, 1..6)
        migrateIntPreference(
            prefs,
            KEY_MAX_PREDICTION_CANDIDATES,
            DEFAULT_MAX_PREDICTION_CANDIDATES,
            1..8,
        )
    }

    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    fun customDefaultBaseUrl(): String = DEFAULT_BASE_URL

    fun providerDefaultBaseUrl(provider: Provider): String =
        provider.defaultBaseUrl ?: customDefaultBaseUrl()

    fun providerDefaultModel(provider: Provider): String =
        provider.defaultModel.orEmpty()

    fun getScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        return prefs.getString(scopedApiKeyKey(provider, baseUrl), "").orEmpty().ifBlank {
            prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        }
    }

    fun getScopedModel(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        return prefs.getString(scopedModelKey(provider, baseUrl), "").orEmpty().trim()
    }

    fun persistScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        apiKey: String,
    ) {
        val normalizedApiKey = apiKey.trim()
        val editor = prefs.edit()
        if (normalizedApiKey.isBlank()) {
            editor.remove(scopedApiKeyKey(provider, baseUrl))
        } else {
            editor.putString(scopedApiKeyKey(provider, baseUrl), normalizedApiKey)
        }
        editor.putString(KEY_API_KEY, normalizedApiKey).apply()
    }

    fun persistScopedModel(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        model: String,
    ) {
        val normalizedModel = model.trim()
        val editor = prefs.edit()
        if (normalizedModel.isBlank()) {
            editor.remove(scopedModelKey(provider, baseUrl))
        } else {
            editor.putString(scopedModelKey(provider, baseUrl), normalizedModel)
        }
        editor.putString(KEY_MODEL, normalizedModel).apply()
    }

    fun syncScopedApiKeyToActivePreferences(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        val apiKey = getScopedApiKey(prefs, provider, baseUrl)
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
        return apiKey
    }

    fun syncScopedModelToActivePreferences(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        legacyFallback: String = "",
    ): String {
        val restoredModel = getScopedModel(prefs, provider, baseUrl)
            .ifBlank { legacyFallback.trim() }
            .ifBlank { providerDefaultModel(provider) }
        val editor = prefs.edit()
        if (restoredModel.isBlank()) {
            editor.remove(scopedModelKey(provider, baseUrl))
        } else {
            editor.putString(scopedModelKey(provider, baseUrl), restoredModel)
        }
        editor.putString(KEY_MODEL, restoredModel).apply()
        return restoredModel
    }

    fun currentProvider(prefs: SharedPreferences): Provider =
        Provider.from(prefs.getString(KEY_PROVIDER, Provider.Custom.value))

    fun currentBaseUrl(prefs: SharedPreferences, provider: Provider = currentProvider(prefs)): String =
        normalizeBaseUrl(
            prefs.getString(KEY_BASE_URL, providerDefaultBaseUrl(provider)).orEmpty()
                .ifBlank { providerDefaultBaseUrl(provider) }
        )

    private fun apiKeyScope(provider: Provider, baseUrl: String): String =
        "${provider.value}|${normalizeBaseUrl(baseUrl.ifBlank { providerDefaultBaseUrl(provider) })}"

    private fun scopedModelKey(provider: Provider, baseUrl: String): String =
        KEY_MODEL_SCOPE_PREFIX + URLEncoder.encode(
            apiKeyScope(provider, baseUrl),
            StandardCharsets.UTF_8.name(),
        )

    private fun scopedApiKeyKey(provider: Provider, baseUrl: String): String =
        KEY_API_KEY_SCOPE_PREFIX + URLEncoder.encode(
            apiKeyScope(provider, baseUrl),
            StandardCharsets.UTF_8.name(),
        )

    private fun migrateIntPreference(
        prefs: SharedPreferences,
        key: String,
        defaultValue: Int,
        validRange: IntRange,
    ) {
        if (prefs.all[key] is Int) return
        val value = readBoundedIntPreference(prefs, key, defaultValue, validRange)
        prefs.edit().putInt(key, value).apply()
    }

    private fun readBoundedIntPreference(
        prefs: SharedPreferences,
        key: String,
        defaultValue: Int,
        validRange: IntRange,
    ): Int {
        val rawValue = prefs.all[key]
        val parsed = when (rawValue) {
            is Int -> rawValue
            is Long -> rawValue.toInt()
            is Float -> rawValue.toInt()
            is Double -> rawValue.toInt()
            is String -> rawValue.toIntOrNull()
            else -> null
        } ?: defaultValue
        return parsed.coerceIn(validRange.first, validRange.last)
    }

    private fun trimKnownSuffix(raw: String, suffixes: List<String>): String {
        val normalized = raw.removeSuffix("/")
        val suffix = suffixes.firstOrNull { normalized.endsWith(it) } ?: return normalized
        return normalized.removeSuffix(suffix)
    }

    private fun hasBareHostPath(raw: String): Boolean {
        val path = runCatching { URI(raw).path.orEmpty() }.getOrDefault("")
        return path.isBlank() || path == "/"
    }

    private fun joinPrefix(root: String, prefix: String): String {
        if (root.isBlank()) return ""
        return root.removeSuffix("/") + prefix
    }

    private fun joinPath(root: String, path: String): String {
        if (root.isBlank()) return ""
        return if (root.endsWith(path)) root else root.removeSuffix("/") + path
    }
}
