/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import splitties.dimensions.dp

class LlmApiUrlPreferenceDialogFragment : DialogFragment() {
    private lateinit var editText: EditText
    private lateinit var chatApiSwitch: SwitchCompat
    private lateinit var hintView: TextView

    private val preferenceKey: String
        get() = requireArguments().getString(ARG_KEY).orEmpty()

    private val preferenceFragment: PreferenceFragmentCompat
        get() = requireParentFragment() as PreferenceFragmentCompat

    private val apiUrlPreference: EditTextPreference
        get() = preferenceFragment.findPreference<EditTextPreference>(preferenceKey)
            ?: error("Preference not found for key=$preferenceKey")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val prefs = apiUrlPreference.preferenceManager.sharedPreferences
        val provider = prefs?.let(LlmPrefs::currentProvider) ?: LlmPrefs.Provider.Custom
        val currentText = savedInstanceState?.getString(STATE_TEXT)
            ?: apiUrlPreference.text.orEmpty().ifBlank {
                LlmPrefs.providerDefaultBaseUrl(provider, prefs)
            }
        val currentChatApiEnabled = savedInstanceState?.getBoolean(STATE_CHAT_API_ENABLED)
            ?: (prefs?.let(LlmPrefs::isChatApiEnabled) == true)

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dp(24)
            setPadding(padding, context.dp(8), padding, 0)
        }

        editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(currentText)
            setSelection(text.length)
        }

        hintView = TextView(context).apply {
            text = provider.defaultBaseUrl?.let {
                context.getString(R.string.llm_api_url_default_hint, it)
            } ?: context.getString(
                R.string.llm_api_url_default_hint,
                LlmPrefs.providerDefaultBaseUrl(provider, prefs),
            )
        }

        chatApiSwitch = SwitchCompat(context).apply {
            text = context.getString(R.string.llm_chat_api_enabled)
            isChecked = currentChatApiEnabled
        }

        contentView.addView(
            editText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        contentView.addView(
            hintView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(12)
            }
        )
        contentView.addView(
            chatApiSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(12)
            }
        )

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.llm_api_url)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                persistValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.default_, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editText.setText(LlmPrefs.providerDefaultBaseUrl(provider, prefs))
                editText.setSelection(editText.text.length)
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::editText.isInitialized) {
            outState.putString(STATE_TEXT, editText.text?.toString().orEmpty())
        }
        if (::chatApiSwitch.isInitialized) {
            outState.putBoolean(STATE_CHAT_API_ENABLED, chatApiSwitch.isChecked)
        }
    }

    private fun persistValues() {
        val newValue = editText.text?.toString().orEmpty()
        if (apiUrlPreference.callChangeListener(newValue)) {
            apiUrlPreference.text = newValue
        }
        apiUrlPreference.preferenceManager.sharedPreferences?.let { prefs ->
            val provider = LlmPrefs.currentProvider(prefs)
            if (provider == LlmPrefs.Provider.Custom) {
                LlmPrefs.persistCustomDefaultBaseUrl(prefs, newValue)
            }
            prefs.edit()
                .putBoolean(LlmPrefs.KEY_CHAT_API_ENABLED, chatApiSwitch.isChecked)
                .apply()
            val scopedApiKey = LlmPrefs.syncScopedApiKeyToActivePreferences(prefs, provider, newValue)
            preferenceFragment.findPreference<EditTextPreference>(LlmPrefs.KEY_API_KEY)?.text = scopedApiKey
            val scopedModel = LlmPrefs.syncScopedModelToActivePreferences(prefs, provider, newValue)
            preferenceFragment.findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL)?.text = scopedModel
        }
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val STATE_TEXT = "llm_api_url_text"
        private const val STATE_CHAT_API_ENABLED = "llm_chat_api_enabled"

        fun newInstance(key: String): LlmApiUrlPreferenceDialogFragment =
            LlmApiUrlPreferenceDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_KEY, key) }
            }
    }
}
