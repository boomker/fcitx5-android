/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LanLlmPrefs
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.toast

class LanLlmSettingsFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(MySwitchPreference(context).apply {
                key = LanLlmPrefs.KEY_ENABLED
                setTitle(R.string.lan_llm_enable)
                setSummary(R.string.lan_llm_enable_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(ListPreference(context).apply {
                key = LanLlmPrefs.KEY_BACKEND
                setTitle(R.string.lan_llm_backend)
                setDialogTitle(R.string.lan_llm_backend)
                entries = arrayOf(
                    context.getString(R.string.lan_llm_backend_chat),
                    context.getString(R.string.lan_llm_backend_completion),
                )
                entryValues = arrayOf(
                    LanLlmPrefs.Backend.ChatCompletions.value,
                    LanLlmPrefs.Backend.Completion.value,
                )
                setDefaultValue(LanLlmPrefs.Backend.ChatCompletions.value)
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })

            addPreference(textPreference(
                key = LanLlmPrefs.KEY_BASE_URL,
                titleRes = R.string.lan_llm_base_url,
                defaultValue = "http://192.168.1.1:8000",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_MODEL,
                titleRes = R.string.lan_llm_model,
                defaultValue = "qwen",
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_API_KEY,
                titleRes = R.string.lan_llm_api_key,
                defaultValue = "",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    if (pref.text.isNullOrBlank()) {
                        context.getString(R.string._not_available_)
                    } else {
                        context.getString(R.string.lan_llm_api_key_set)
                    }
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_DEBOUNCE_MS,
                titleRes = R.string.lan_llm_debounce_ms,
                defaultValue = "450",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text?.toIntOrNull()
                    if (value == null) context.getString(R.string.invalid_value) else "$value ms"
                },
                inputType = InputType.TYPE_CLASS_NUMBER,
                onChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value == null || value !in 100..3000) {
                        context.toast(R.string.invalid_value)
                        false
                    } else {
                        true
                    }
                },
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_SAMPLE_COUNT,
                titleRes = R.string.lan_llm_sample_count,
                defaultValue = "4",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text?.toIntOrNull()
                    if (value == null) context.getString(R.string.invalid_value) else value.toString()
                },
                inputType = InputType.TYPE_CLASS_NUMBER,
                onChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value == null || value !in 1..8) {
                        context.toast(R.string.invalid_value)
                        false
                    } else {
                        true
                    }
                },
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_MAX_CONTEXT_CHARS,
                titleRes = R.string.lan_llm_max_context_chars,
                defaultValue = "64",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text?.toIntOrNull()
                    if (value == null) context.getString(R.string.invalid_value) else value.toString()
                },
                inputType = InputType.TYPE_CLASS_NUMBER,
                onChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value == null || value !in 8..512) {
                        context.toast(R.string.invalid_value)
                        false
                    } else {
                        true
                    }
                },
            ))
        }
    }

    private fun textPreference(
        key: String,
        titleRes: Int,
        defaultValue: String,
        summaryProvider: Preference.SummaryProvider<EditTextPreference>? =
            EditTextPreference.SimpleSummaryProvider.getInstance(),
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onChange: ((String) -> Boolean)? = null,
    ) = EditTextPreference(requireContext()).apply {
        this.key = key
        setTitle(titleRes)
        setDialogTitle(titleRes)
        setDefaultValue(defaultValue)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        this.summaryProvider = summaryProvider
        setOnBindEditTextListener { editText ->
            editText.inputType = inputType
            editText.setSingleLine(true)
        }
        if (onChange != null) {
            setOnPreferenceChangeListener { _, newValue ->
                onChange(newValue?.toString().orEmpty())
            }
        }
    }
}
