package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import androidx.preference.PreferenceManager

object LanLlmPrefs {
    const val KEY_BACKEND = "lan_llm_backend"
    const val KEY_ENABLED = "lan_llm_enabled"
    const val KEY_BASE_URL = "lan_llm_base_url"
    const val KEY_MODEL = "lan_llm_model"
    const val KEY_API_KEY = "lan_llm_api_key"
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

    private const val DEFAULT_BASE_URL = "http://192.168.1.1:8000"
    private const val DEFAULT_MODEL = "qwen"
    private const val DEFAULT_DEBOUNCE_MS = 450
    private const val DEFAULT_SAMPLE_COUNT = 4
    private const val DEFAULT_MAX_CONTEXT_CHARS = 64

    data class Config(
        val enabled: Boolean,
        val backend: Backend,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val debounceMs: Long,
        val sampleCount: Int,
        val maxContextChars: Int,
        val preferLastCommit: Boolean,
    ) {
        val isUsable: Boolean
            get() = enabled && baseUrl.isNotBlank() && model.isNotBlank()

        val chatEndpoint: String
            get() = when {
                baseUrl.endsWith("/chat/completions") -> baseUrl
                baseUrl.endsWith("/v1") -> "$baseUrl/chat/completions"
                else -> "$baseUrl/v1/chat/completions"
            }

        val completionEndpoint: String
            get() = when {
                baseUrl.endsWith("/completion") -> baseUrl
                baseUrl.endsWith("/v1") -> baseUrl.removeSuffix("/v1") + "/completion"
                else -> "$baseUrl/completion"
            }

        val fetchWindowChars: Int
            get() = (maxContextChars * 3).coerceIn(96, 512)
    }

    fun read(context: Context): Config {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
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
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            backend = Backend.from(prefs.getString(KEY_BACKEND, Backend.ChatCompletions.value)),
            baseUrl = normalizeBaseUrl(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().trim(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim(),
            debounceMs = debounceMs,
            sampleCount = sampleCount,
            maxContextChars = maxContextChars,
            preferLastCommit = true,
        )
    }

    private fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }
}
