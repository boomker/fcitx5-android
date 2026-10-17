/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.SubtypeManager
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinScheme
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeCodec
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import org.fxboomk.fcitx5.android.utils.CollapsiblePreferenceCategory
import org.fxboomk.fcitx5.android.utils.toast
import splitties.dimensions.dp

class CustomShuangpinMappingFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private var scheme: CustomShuangpinScheme = CustomShuangpinSchemeCodec.defaultScheme()
    private var dirty = false
    private var buildingPreferences = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        scheme = CustomShuangpinSchemeStore.load()
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        setPreferenceScreen(screen)

        buildingPreferences = true
        addSchemePreferences(context, screen)
        buildingPreferences = false
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.custom_shuangpin))
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (parentFragmentManager.findFragmentByTag(javaClass.name) != null) return
        val dialog = when (preference) {
            is EditTextPreference ->
                EditTextPreferenceDialogFragmentCompat.newInstance(preference.key)
            is ListPreference ->
                ListPreferenceDialogFragmentCompat.newInstance(preference.key)
            else -> {
                super.onDisplayPreferenceDialog(preference)
                return
            }
        }
        dialog.setTargetFragment(this, 0)
        dialog.show(parentFragmentManager, javaClass.name)
    }

    override fun onStop() {
        super.onStop()
        if (dirty) {
            dirty = false
            viewModel.fcitx.runIfReady {
                reloadConfig()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    SubtypeManager.syncWith(enabledIme())
                }
            }
        }
    }

    private fun addSchemePreferences(context: Context, screen: PreferenceScreen) {
        screen.addPreference(
            Preference(context).apply {
                isSelectable = false
                title = context.getString(R.string.custom_shuangpin_mapping_hint)
            }
        )

        screen.addPreference(
            EditTextPreference(context).apply {
                key = KEY_NAME
                title = context.getString(R.string.custom_shuangpin_scheme_name)
                isPersistent = false
                text = scheme.name
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    if (buildingPreferences) return@setOnPreferenceChangeListener true
                    val name = newValue.toString().trim()
                    if (!CustomShuangpinSchemeCodec.isValidName(name)) {
                        context.toast(R.string.custom_shuangpin_name_invalid)
                        return@setOnPreferenceChangeListener false
                    }
                    persist(scheme.copy(name = name))
                    true
                }
            }
        )

        screen.addPreference(
            EditTextPreference(context).apply {
                key = KEY_ZERO_INITIAL
                title = context.getString(R.string.custom_shuangpin_zero_initial)
                isPersistent = false
                text = scheme.zeroInitial
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    if (buildingPreferences) return@setOnPreferenceChangeListener true
                    val zeroInitial = newValue.toString().trim().lowercase()
                    if (!CustomShuangpinSchemeCodec.isValidZeroInitial(zeroInitial)) {
                        context.toast(R.string.custom_shuangpin_zero_initial_invalid)
                        return@setOnPreferenceChangeListener false
                    }
                    persist(scheme.copy(zeroInitial = zeroInitial))
                    true
                }
            }
        )

        val initialsCategory = CollapsiblePreferenceCategory(
            context,
            stateKey = STATE_INITIALS
        ).apply {
            key = KEY_INITIALS_GROUP
            setCollapsibleTitle(context.getString(R.string.custom_shuangpin_initials))
        }
        // PreferenceGroup needs a PreferenceManager before it can accept children.
        screen.addPreference(initialsCategory)
        CustomShuangpinSchemeCodec.INITIALS.forEach { initial ->
            initialsCategory.addPreference(
                mappingPreference(
                    context,
                    key = KEY_INITIAL_PREFIX + initial,
                    title = initial,
                    currentKey = scheme.initials[initial].orEmpty()
                ) { key ->
                    val initials = scheme.initials.toMutableMap()
                    if (key.isEmpty()) initials.remove(initial) else initials[initial] = key
                    persist(scheme.copy(initials = initials))
                }
            )
        }

        val finalsCategory = CollapsiblePreferenceCategory(
            context,
            stateKey = STATE_FINALS
        ).apply {
            key = KEY_FINALS_GROUP
            setCollapsibleTitle(context.getString(R.string.custom_shuangpin_finals))
        }
        screen.addPreference(finalsCategory)
        CustomShuangpinSchemeCodec.FINALS.forEach { final ->
            finalsCategory.addPreference(
                mappingPreference(
                    context,
                    key = KEY_FINAL_PREFIX + final,
                    title = final,
                    currentKey = scheme.finals[final].orEmpty()
                ) { key ->
                    val finals = scheme.finals.toMutableMap()
                    if (key.isEmpty()) finals.remove(final) else finals[final] = key
                    persist(scheme.copy(finals = finals))
                }
            )
        }

        val syllablesCategory = CollapsiblePreferenceCategory(
            context,
            stateKey = STATE_SYLLABLES
        ).apply {
            key = KEY_SYLLABLES_GROUP
            setCollapsibleTitle(context.getString(R.string.custom_shuangpin_syllables))
        }
        screen.addPreference(syllablesCategory)
        syllablesCategory.addPreference(
            Preference(context).apply {
                key = KEY_SYLLABLES
                title = context.getString(R.string.custom_shuangpin_edit_syllables)
                isPersistent = false
                summary = syllableSummary(context)
                setOnPreferenceClickListener {
                    showSyllableEditor()
                    true
                }
            }
        )
    }

    private fun syllableSummary(context: Context): String {
        val count = scheme.syllables.values.sumOf { it.size }
        return if (count == 0) {
            context.getString(R.string.custom_shuangpin_key_none)
        } else {
            context.getString(R.string.custom_shuangpin_syllable_count, count)
        }
    }

    private fun showSyllableEditor() {
        val context = requireContext()
        val editText = EditText(context).apply {
            setText(
                scheme.syllables.entries.joinToString("\n") { (pinyin, keys) ->
                    "$pinyin=${keys.joinToString(",")}"
                }
            )
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            maxLines = 12
            setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.custom_shuangpin_syllables)
            .setMessage(R.string.custom_shuangpin_syllables_hint)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val parsed = parseSyllableOverrides(editText.text.toString())
                if (parsed == null) {
                    context.toast(R.string.custom_shuangpin_syllables_invalid)
                } else {
                    persist(scheme.copy(syllables = parsed))
                    findPreference<Preference>(KEY_SYLLABLES)?.summary =
                        syllableSummary(context)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseSyllableOverrides(text: String): Map<String, List<String>>? {
        val result = mutableMapOf<String, MutableList<String>>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val separator = line.indexOf('=')
            if (separator <= 0 || separator == line.lastIndex) return null
            val pinyin = line.substring(0, separator).trim().lowercase()
            val keys = line.substring(separator + 1)
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (!CustomShuangpinSchemeCodec.isValidSyllable(pinyin) ||
                keys.isEmpty() ||
                keys.any { !CustomShuangpinSchemeCodec.isValidSyllableKeys(it) }
            ) return null
            result.getOrPut(pinyin) { mutableListOf() }.addAll(keys)
        }
        return result.mapValues { it.value.distinct() }
    }

    private fun mappingPreference(
        context: Context,
        key: String,
        title: String,
        currentKey: String,
        onChanged: (String) -> Unit
    ): ListPreference = ListPreference(context).apply {
        this.key = key
        this.title = title
        isPersistent = false
        entries = (
            listOf<CharSequence>(context.getString(R.string.custom_shuangpin_key_none)) +
                CustomShuangpinSchemeCodec.KEYS
            ).toTypedArray()
        entryValues = (
            listOf<CharSequence>(CustomShuangpinSchemeCodec.NONE) +
                CustomShuangpinSchemeCodec.KEYS
            ).toTypedArray()
        value = currentKey.ifEmpty { CustomShuangpinSchemeCodec.NONE }
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, newValue ->
            if (buildingPreferences) return@setOnPreferenceChangeListener true
            val selected = newValue.toString()
            onChanged(if (selected == CustomShuangpinSchemeCodec.NONE) "" else selected)
            true
        }
    }

    private fun persist(updated: CustomShuangpinScheme) {
        scheme = updated
        dirty = true
        CustomShuangpinSchemeStore.save(updated).onFailure {
            requireContext().toast(it)
        }
    }

    private companion object {
        const val KEY_NAME = "custom_shuangpin_name"
        const val KEY_ZERO_INITIAL = "custom_shuangpin_zero_initial"
        const val KEY_INITIAL_PREFIX = "custom_shuangpin_initial_"
        const val KEY_FINAL_PREFIX = "custom_shuangpin_final_"
        const val KEY_INITIALS_GROUP = "custom_shuangpin_initials_group"
        const val KEY_FINALS_GROUP = "custom_shuangpin_finals_group"
        const val KEY_SYLLABLES_GROUP = "custom_shuangpin_syllables_group"
        const val KEY_SYLLABLES = "custom_shuangpin_syllables"
        const val STATE_INITIALS = "custom_shuangpin_state_initials"
        const val STATE_FINALS = "custom_shuangpin_state_finals"
        const val STATE_SYLLABLES = "custom_shuangpin_state_syllables"
    }
}
