package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal interface LlmThinkingSuppressionStore {
    fun isSuppressed(config: LlmPrefs.Config, protocol: String): Boolean

    fun suppress(config: LlmPrefs.Config, protocol: String)
}

internal object NoOpLlmThinkingSuppressionStore : LlmThinkingSuppressionStore {
    override fun isSuppressed(config: LlmPrefs.Config, protocol: String): Boolean = false

    override fun suppress(config: LlmPrefs.Config, protocol: String) = Unit
}

internal class LlmSharedPrefsThinkingSuppressionStore(
    private val prefs: SharedPreferences,
) : LlmThinkingSuppressionStore {
    companion object {
        private const val KEY_PREFIX = "llm_thinking_suppressed_"

        fun fromContext(context: Context): LlmSharedPrefsThinkingSuppressionStore =
            LlmSharedPrefsThinkingSuppressionStore(PreferenceManager.getDefaultSharedPreferences(context))
    }

    override fun isSuppressed(config: LlmPrefs.Config, protocol: String): Boolean =
        prefs.getBoolean(prefKey(config, protocol), false)

    override fun suppress(config: LlmPrefs.Config, protocol: String) {
        prefs.edit().putBoolean(prefKey(config, protocol), true).apply()
    }

    private fun prefKey(config: LlmPrefs.Config, protocol: String): String = buildString {
        append(KEY_PREFIX)
        append(config.provider.value)
        append('|')
        append(encode(config.resolvedBaseUrl))
        append('|')
        append(encode(config.model.trim()))
        append('|')
        append(protocol)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
