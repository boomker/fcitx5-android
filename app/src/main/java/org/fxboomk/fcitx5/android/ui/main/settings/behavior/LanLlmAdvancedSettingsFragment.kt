/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.appcompat.app.AlertDialog
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LanLlmPrefs
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fxboomk.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fxboomk.fcitx5.android.utils.toast

class LanLlmAdvancedSettingsFragment : PaddingPreferenceFragment() {
    private val personaListKey = "lan_llm_persona_list"

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferences?.let(LanLlmPrefs::migrateSeekBarBackedPreferences)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(MySwitchPreference(context).apply {
                key = LanLlmPrefs.KEY_SPACE_COMMIT_PREDICTION
                setTitle(R.string.lan_llm_space_commit_prediction)
                setSummary(R.string.lan_llm_space_commit_prediction_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(personaPresetPreference())
            addPreference(customPersonaPreference())
            addPreference(sampleCountPreference())
            addPreference(maxContextCharsPreference())
            addPreference(maxOutputTokensPreference())
        }
        refreshPersonaOptions()
        syncPersonaPreferenceState()
    }

    private fun personaPresetPreference() = ListPreference(requireContext()).apply {
        key = personaListKey
        setTitle(R.string.lan_llm_persona_style)
        setDialogTitle(R.string.lan_llm_persona_style)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
            personaOptions().firstOrNull { it.value == pref.value }?.description.orEmpty()
        }
        setOnPreferenceChangeListener { _, newValue ->
            persistSelectedPersona(newValue?.toString().orEmpty())
            syncPersonaPreferenceState()
            true
        }
    }

    private fun customPersonaPreference() = EditTextPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_CUSTOM_PERSONA
        setTitle(R.string.lan_llm_custom_persona)
        setDialogTitle(R.string.lan_llm_custom_persona)
        setDefaultValue("")
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            pref.text?.trim().takeUnless { it.isNullOrEmpty() }
                ?: context.getString(R.string.lan_llm_custom_persona_summary)
        }
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            editText.minLines = 3
        }
        setOnPreferenceChangeListener { _, newValue ->
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceChangeListener true
            val selectedValue = prefs.getString(
                LanLlmPrefs.KEY_PERSONA_PRESET,
                LanLlmPrefs.PersonaPreset.Custom.value,
            ).orEmpty()
            LanLlmPrefs.writePersonaDetail(prefs, selectedValue, newValue?.toString().orEmpty())
            true
        }
    }

    private fun sampleCountPreference() = DialogSeekBarPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_SAMPLE_COUNT
        setTitle(R.string.lan_llm_sample_count)
        setDialogTitle(R.string.lan_llm_sample_count)
        setDefaultValue(4)
        min = 1
        max = 6
        step = 1
        unit = ""
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
    }

    private fun maxContextCharsPreference() = DialogSeekBarPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_MAX_CONTEXT_CHARS
        setTitle(R.string.lan_llm_max_context_chars)
        setDialogTitle(R.string.lan_llm_max_context_chars)
        setDefaultValue(64)
        min = 8
        max = 512
        step = 1
        unit = ""
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
    }

    private fun maxOutputTokensPreference() = EditTextPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_MAX_OUTPUT_TOKENS
        setTitle(R.string.lan_llm_max_output_tokens)
        setDialogTitle(R.string.lan_llm_max_output_tokens)
        setDefaultValue("512")
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val value = pref.text?.toIntOrNull()
            if (value == null) {
                context.getString(R.string.invalid_value)
            } else {
                context.getString(R.string.lan_llm_max_output_tokens_summary, value)
            }
        }
        setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER
            editText.setSingleLine(true)
        }
        setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.toIntOrNull()
            if (value == null || value !in 1..16384) {
                context.toast(R.string.invalid_value)
                false
            } else {
                true
            }
        }
    }

    private fun syncPersonaPreferenceState(preset: LanLlmPrefs.PersonaPreset? = null) {
        val ctx = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val selectedValue = prefs.getString(LanLlmPrefs.KEY_PERSONA_PRESET, LanLlmPrefs.PersonaPreset.Custom.value).orEmpty()
        val resolvedPreset = preset ?: LanLlmPrefs.PersonaPreset.entries.firstOrNull { it.value == selectedValue }
        val preference = findPreference<EditTextPreference>(LanLlmPrefs.KEY_CUSTOM_PERSONA) ?: return
        val title = when (resolvedPreset) {
            LanLlmPrefs.PersonaPreset.Custom -> ctx.getString(R.string.lan_llm_custom_persona)
            LanLlmPrefs.PersonaPreset.SocialStar -> ctx.getString(R.string.lan_llm_social_star_persona)
            LanLlmPrefs.PersonaPreset.WorkplaceElite -> ctx.getString(R.string.lan_llm_workplace_elite_persona)
            null -> "${selectedValue}${ctx.getString(R.string.lan_llm_persona_suffix)}"
        }
        preference.title = title
        preference.dialogTitle = title
        preference.isEnabled = true
        val detail = LanLlmPrefs.readPersonaDetail(prefs, selectedValue)
        if (preference.text != detail) {
            preference.text = detail
        }
        preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            pref.text?.trim().takeUnless { it.isNullOrEmpty() }
                ?: ctx.getString(R.string.lan_llm_custom_persona_summary)
        }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference.key == personaListKey) {
            showPersonaChooserDialog()
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    private fun personaOptions(): List<LanLlmPrefs.PersonaOption> {
        val prefs = preferenceManager.sharedPreferences ?: return LanLlmPrefs.builtInPersonaOptions(requireContext())
        val builtIns = LanLlmPrefs.builtInPersonaOptions(requireContext())
        val customs = LanLlmPrefs.readCustomPersonaNames(prefs).map { name ->
            LanLlmPrefs.PersonaOption(
                value = name,
                title = name,
                description = getString(R.string.lan_llm_persona_custom_name_description, name),
                preset = null,
            )
        }
        return builtIns + customs
    }

    private fun refreshPersonaOptions() {
        val preference = findPreference<ListPreference>(personaListKey) ?: return
        val options = personaOptions()
        preference.entries = options.map { it.title }.toTypedArray()
        preference.entryValues = options.map { it.value }.toTypedArray()
        val prefs = preferenceManager.sharedPreferences ?: return
        val selected = prefs.getString(LanLlmPrefs.KEY_PERSONA_PRESET, LanLlmPrefs.PersonaPreset.Custom.value)
        preference.setDefaultValue(LanLlmPrefs.PersonaPreset.Custom.value)
        preference.value = selected
    }

    private fun persistSelectedPersona(value: String) {
        val prefs = preferenceManager.sharedPreferences ?: return
        prefs.edit().putString(LanLlmPrefs.KEY_PERSONA_PRESET, value).apply()
    }

    private fun showPersonaChooserDialog() {
        val ctx = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val options = personaOptions()
        val selectedValue = prefs.getString(LanLlmPrefs.KEY_PERSONA_PRESET, LanLlmPrefs.PersonaPreset.Custom.value).orEmpty()
        val checkedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
        var pendingIndex = checkedIndex
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lan_llm_persona_style)
            .setSingleChoiceItems(options.map { it.title }.toTypedArray(), checkedIndex) { _, which ->
                pendingIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = options.getOrNull(pendingIndex) ?: return@setPositiveButton
                persistSelectedPersona(selected.value)
                refreshPersonaOptions()
                syncPersonaPreferenceState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.add) { _, _ ->
                showAddPersonaDialog()
            }
            .show()
    }

    private fun showAddPersonaDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = ctx.getString(R.string.lan_llm_persona_name_hint)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lan_llm_persona_add)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) return@setPositiveButton
                val prefs = preferenceManager.sharedPreferences ?: return@setPositiveButton
                val names = (LanLlmPrefs.readCustomPersonaNames(prefs) + name).distinct()
                LanLlmPrefs.writeCustomPersonaNames(prefs, names)
                persistSelectedPersona(name)
                refreshPersonaOptions()
                syncPersonaPreferenceState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
