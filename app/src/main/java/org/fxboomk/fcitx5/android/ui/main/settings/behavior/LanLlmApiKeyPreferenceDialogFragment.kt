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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LanLlmCatalogClient
import org.fxboomk.fcitx5.android.input.predict.LanLlmPrefs
import splitties.dimensions.dp

class LanLlmApiKeyPreferenceDialogFragment : DialogFragment() {
    private lateinit var editText: EditText
    private lateinit var statusView: TextView
    private val catalogClient = LanLlmCatalogClient()

    private val preferenceKey: String
        get() = requireArguments().getString(ARG_KEY).orEmpty()

    private val preferenceFragment: PreferenceFragmentCompat
        get() = requireParentFragment() as PreferenceFragmentCompat

    private val apiKeyPreference: EditTextPreference
        get() = preferenceFragment.findPreference<EditTextPreference>(preferenceKey)
            ?: error("Preference not found for key=$preferenceKey")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val currentText = savedInstanceState?.getString(STATE_TEXT)
            ?: apiKeyPreference.text.orEmpty()
        val currentStatus = savedInstanceState?.getString(STATE_STATUS).orEmpty()

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dp(24)
            setPadding(padding, context.dp(8), padding, 0)
        }

        editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(currentText)
            setSelection(text.length)
        }

        statusView = TextView(context).apply {
            text = currentStatus
            isVisible = currentStatus.isNotBlank()
        }

        contentView.addView(
            editText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        contentView.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(12)
            }
        )

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.lan_llm_api_key)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok) { _, _ -> persistValue() }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.lan_llm_test_connectivity, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                runConnectivityCheck(dialog)
            }
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::editText.isInitialized) {
            outState.putString(STATE_TEXT, editText.text?.toString().orEmpty())
        }
        if (::statusView.isInitialized) {
            outState.putString(STATE_STATUS, statusView.text?.toString().orEmpty())
        }
    }

    private fun persistValue() {
        val newValue = editText.text?.toString().orEmpty()
        if (apiKeyPreference.callChangeListener(newValue)) {
            apiKeyPreference.text = newValue
            apiKeyPreference.preferenceManager.sharedPreferences?.let { prefs ->
                val provider = LanLlmPrefs.currentProvider(prefs)
                val baseUrl = LanLlmPrefs.currentBaseUrl(prefs, provider)
                LanLlmPrefs.persistScopedApiKey(prefs, provider, baseUrl, newValue)
            }
        }
    }

    private fun runConnectivityCheck(dialog: AlertDialog) {
        val prefs = apiKeyPreference.preferenceManager.sharedPreferences ?: return
        val pendingApiKey = editText.text?.toString().orEmpty().trim()
        val config = LanLlmPrefs.read(
            prefs,
            LanLlmPrefs.Overrides(apiKey = pendingApiKey),
        )
        statusView.isVisible = true
        statusView.text = getString(R.string.lan_llm_connectivity_checking)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false

        lifecycleScope.launch {
            val result = runCatching {
                catalogClient.checkConnectivity(config)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
            result.onSuccess { success ->
                statusView.text = getString(
                    R.string.lan_llm_connectivity_success,
                    success.modelCount,
                    success.endpoint,
                )
            }.onFailure { error ->
                statusView.text = getString(
                    R.string.lan_llm_connectivity_failed,
                    error.message?.take(160).orEmpty(),
                )
                Toast.makeText(
                    requireContext(),
                    error.message?.take(160).orEmpty(),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val STATE_TEXT = "lan_llm_api_key_text"
        private const val STATE_STATUS = "lan_llm_api_key_status"

        fun newInstance(key: String): LanLlmApiKeyPreferenceDialogFragment =
            LanLlmApiKeyPreferenceDialogFragment().apply {
                arguments = bundleOf(ARG_KEY to key)
            }
    }
}
