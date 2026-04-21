package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
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
    private const val KEY_API_KEY_SCOPE_PREFIX = "lan_llm_api_key_scope_"
    const val KEY_DEBOUNCE_MS = "lan_llm_debounce_ms"
    const val KEY_SAMPLE_COUNT = "lan_llm_sample_count"
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
    ) {
        Custom("custom", R.string.lan_llm_provider_custom, null, null),
        OpenAI("openai", R.string.lan_llm_provider_openai, "https://api.openai.com/v1", CompatApi.OpenAI),
        Anthropic("anthropic", R.string.lan_llm_provider_anthropic, "https://api.anthropic.com/v1", CompatApi.Anthropic),
        Gemini("gemini", R.string.lan_llm_provider_gemini, "https://generativelanguage.googleapis.com/v1beta/openai", CompatApi.OpenAI),
        DeepSeek("deepseek", R.string.lan_llm_provider_deepseek, "https://api.deepseek.com", CompatApi.OpenAI),
        Zhipu("zhipu", R.string.lan_llm_provider_zhipu, "https://open.bigmodel.cn/api/paas/v4", CompatApi.OpenAI),
        MiniMax("minimax", R.string.lan_llm_provider_minimax, "https://api.minimaxi.com/v1", CompatApi.OpenAI);

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

        private val anthropicRoot: String
            get() = trimKnownSuffix(
                resolvedBaseUrl,
                listOf("/messages", "/models", "/chat/completions", "/completion", "/responses", "/api/generate"),
            )

        val chatEndpoint: String
            get() = joinPath(openAiRoot, "/chat/completions")

        val messagesEndpoint: String
            get() = joinPath(anthropicRoot, "/messages")

        val modelsEndpoint: String
            get() = when (compatApi) {
                CompatApi.OpenAI -> joinPath(openAiRoot, "/models")
                CompatApi.Anthropic -> joinPath(anthropicRoot, "/models")
            }

        val chatCompatEndpoints: List<String>
            get() = when (compatApi) {
                CompatApi.OpenAI -> listOf(chatEndpoint)
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
        val debounceMs = prefs.getString(KEY_DEBOUNCE_MS, DEFAULT_DEBOUNCE_MS.toString())
            ?.toLongOrNull()
            ?.coerceIn(100L, 3000L)
            ?: DEFAULT_DEBOUNCE_MS.toLong()
        val sampleCount = prefs.getString(KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT.toString())
            ?.toIntOrNull()
            ?.coerceIn(1, 8)
            ?: DEFAULT_SAMPLE_COUNT
        val maxContextChars = prefs.getString(KEY_MAX_CONTEXT_CHARS, DEFAULT_MAX_CONTEXT_CHARS.toString())
            ?.toIntOrNull()
            ?.coerceIn(8, 512)
            ?: DEFAULT_MAX_CONTEXT_CHARS
        val chatApiEnabled = if (prefs.contains(KEY_CHAT_API_ENABLED)) {
            prefs.getBoolean(KEY_CHAT_API_ENABLED, false)
        } else {
            prefs.getString(KEY_BACKEND, Backend.Completion.value) == Backend.ChatCompletions.value
        }
        val provider = overrides.provider ?: Provider.from(prefs.getString(KEY_PROVIDER, Provider.Custom.value))
        val baseUrl = overrides.baseUrl ?: prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            backend = overrides.backend ?: if (chatApiEnabled) Backend.ChatCompletions else Backend.Completion,
            provider = provider,
            baseUrl = normalizeBaseUrl(baseUrl),
            model = (overrides.model ?: prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()).trim(),
            apiKey = (overrides.apiKey ?: getScopedApiKey(prefs, provider, baseUrl)).trim(),
            debounceMs = debounceMs,
            sampleCount = sampleCount,
            maxContextChars = maxContextChars,
            preferLastCommit = true,
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

    fun getScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        return prefs.getString(scopedApiKeyKey(provider, baseUrl), "").orEmpty().ifBlank {
            prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        }
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

    fun syncScopedApiKeyToActivePreferences(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        val apiKey = getScopedApiKey(prefs, provider, baseUrl)
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
        return apiKey
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

    private fun scopedApiKeyKey(provider: Provider, baseUrl: String): String =
        KEY_API_KEY_SCOPE_PREFIX + URLEncoder.encode(
            apiKeyScope(provider, baseUrl),
            StandardCharsets.UTF_8.name(),
        )

    private fun trimKnownSuffix(raw: String, suffixes: List<String>): String {
        val normalized = raw.removeSuffix("/")
        val suffix = suffixes.firstOrNull { normalized.endsWith(it) } ?: return normalized
        return normalized.removeSuffix(suffix)
    }

    private fun joinPath(root: String, path: String): String {
        if (root.isBlank()) return ""
        return if (root.endsWith(path)) root else root.removeSuffix("/") + path
    }
}
