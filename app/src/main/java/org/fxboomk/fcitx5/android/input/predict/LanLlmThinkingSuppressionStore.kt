package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal interface LanLlmThinkingSuppressionStore {
    fun isSuppressed(config: LanLlmPrefs.Config, protocol: String): Boolean

    fun suppress(config: LanLlmPrefs.Config, protocol: String)
}

internal object NoOpLanLlmThinkingSuppressionStore : LanLlmThinkingSuppressionStore {
    override fun isSuppressed(config: LanLlmPrefs.Config, protocol: String): Boolean = false

    override fun suppress(config: LanLlmPrefs.Config, protocol: String) = Unit
}

internal class LanLlmSharedPrefsThinkingSuppressionStore(
    private val prefs: SharedPreferences,
) : LanLlmThinkingSuppressionStore {
    companion object {
        private const val KEY_PREFIX = "lan_llm_thinking_suppressed_"

        fun fromContext(context: Context): LanLlmSharedPrefsThinkingSuppressionStore =
            LanLlmSharedPrefsThinkingSuppressionStore(PreferenceManager.getDefaultSharedPreferences(context))
    }

    override fun isSuppressed(config: LanLlmPrefs.Config, protocol: String): Boolean =
        prefs.getBoolean(prefKey(config, protocol), false)

    override fun suppress(config: LanLlmPrefs.Config, protocol: String) {
        prefs.edit().putBoolean(prefKey(config, protocol), true).apply()
    }

    private fun prefKey(config: LanLlmPrefs.Config, protocol: String): String = buildString {
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
