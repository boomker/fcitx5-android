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

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference.key) {
            LanLlmPrefs.KEY_BASE_URL -> {
                if (childFragmentManager.findFragmentByTag(LanLlmApiUrlPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LanLlmApiUrlPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LanLlmApiUrlPreferenceDialogFragment::class.java.name)
                return
            }
            LanLlmPrefs.KEY_API_KEY -> {
                if (childFragmentManager.findFragmentByTag(LanLlmApiKeyPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LanLlmApiKeyPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LanLlmApiKeyPreferenceDialogFragment::class.java.name)
                return
            }
            LanLlmPrefs.KEY_MODEL -> {
                if (childFragmentManager.findFragmentByTag(LanLlmModelPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LanLlmModelPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LanLlmModelPreferenceDialogFragment::class.java.name)
                return
            }
        }
        super.onDisplayPreferenceDialog(preference)
    }

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
            addPreference(providerPreference())
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_BASE_URL,
                titleRes = R.string.lan_llm_api_url,
                defaultValue = "http://192.168.1.1:8000",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val raw = pref.text.orEmpty().trim()
                    if (raw.isNotBlank()) {
                        raw
                    } else {
                        val provider = LanLlmPrefs.Provider.from(
                            pref.preferenceManager.sharedPreferences?.getString(LanLlmPrefs.KEY_PROVIDER, null)
                        )
                        provider.defaultBaseUrl?.let {
                            context.getString(R.string.lan_llm_api_url_default_summary, it)
                        } ?: context.getString(R.string._not_available_)
                    }
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
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
                key = LanLlmPrefs.KEY_MODEL,
                titleRes = R.string.lan_llm_model,
                defaultValue = "qwen",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    if (pref.text.isNullOrBlank()) {
                        context.getString(R.string.lan_llm_model_summary_hint)
                    } else {
                        pref.text
                    }
                },
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
        ensureProviderDefaultsAndScopedApiKey()
    }

    private fun providerPreference() = ListPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_PROVIDER
        setTitle(R.string.lan_llm_provider)
        setDialogTitle(R.string.lan_llm_provider)
        entries = LanLlmPrefs.Provider.entries.map { context.getString(it.titleRes) }.toTypedArray()
        entryValues = LanLlmPrefs.Provider.entries.map { it.value }.toTypedArray()
        setDefaultValue(LanLlmPrefs.Provider.Custom.value)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, newValue ->
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceChangeListener true
            val nextProvider = LanLlmPrefs.Provider.from(newValue?.toString())
            val nextBase = LanLlmPrefs.providerDefaultBaseUrl(nextProvider)
            findPreference<EditTextPreference>(LanLlmPrefs.KEY_BASE_URL)?.text = nextBase
            val nextApiKey = LanLlmPrefs.getScopedApiKey(prefs, nextProvider, nextBase)
            findPreference<EditTextPreference>(LanLlmPrefs.KEY_API_KEY)?.text = nextApiKey
            true
        }
    }

    private fun ensureProviderDefaultsAndScopedApiKey() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val provider = LanLlmPrefs.currentProvider(prefs)
        val basePref = findPreference<EditTextPreference>(LanLlmPrefs.KEY_BASE_URL)
        val currentRawBase = basePref?.text.orEmpty()
        val effectiveBase = currentRawBase.ifBlank { LanLlmPrefs.providerDefaultBaseUrl(provider) }
        if (currentRawBase != effectiveBase) {
            basePref?.text = effectiveBase
        }
        val apiKey = LanLlmPrefs.syncScopedApiKeyToActivePreferences(prefs, provider, effectiveBase)
        findPreference<EditTextPreference>(LanLlmPrefs.KEY_API_KEY)?.text = apiKey
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
