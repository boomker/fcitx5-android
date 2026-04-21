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

class LanLlmModelPreferenceDialogFragment : DialogFragment() {
    private lateinit var editText: EditText
    private lateinit var statusView: TextView
    private val catalogClient = LanLlmCatalogClient()

    private val preferenceKey: String
        get() = requireArguments().getString(ARG_KEY).orEmpty()

    private val preferenceFragment: PreferenceFragmentCompat
        get() = requireParentFragment() as PreferenceFragmentCompat

    private val modelPreference: EditTextPreference
        get() = preferenceFragment.findPreference<EditTextPreference>(preferenceKey)
            ?: error("Preference not found for key=$preferenceKey")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val currentText = savedInstanceState?.getString(STATE_TEXT)
            ?: modelPreference.text.orEmpty()
        val currentStatus = savedInstanceState?.getString(STATE_STATUS).orEmpty()

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dp(24)
            setPadding(padding, context.dp(8), padding, 0)
        }

        editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
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
            .setTitle(R.string.lan_llm_model)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok) { _, _ -> persistValue() }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.lan_llm_fetch_models, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                fetchRemoteModels(dialog)
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
        if (modelPreference.callChangeListener(newValue)) {
            modelPreference.text = newValue
        }
    }

    private fun fetchRemoteModels(dialog: AlertDialog) {
        val prefs = modelPreference.preferenceManager.sharedPreferences ?: return
        val config = LanLlmPrefs.read(prefs)
        statusView.isVisible = true
        statusView.text = getString(R.string.lan_llm_model_fetching)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false

        lifecycleScope.launch {
            val result = runCatching {
                catalogClient.fetchModels(config)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    statusView.text = getString(R.string.lan_llm_model_fetch_empty)
                    return@onSuccess
                }
                statusView.text = getString(R.string.lan_llm_model_fetch_success, models.size)
                val labels = models.map {
                    if (it.displayName == it.id) it.id else "${it.displayName} (${it.id})"
                }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.lan_llm_model_select)
                    .setItems(labels) { _, which ->
                        val selected = models[which].id
                        editText.setText(selected)
                        editText.setSelection(selected.length)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.onFailure { error ->
                statusView.text = getString(
                    R.string.lan_llm_model_fetch_failed,
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
        private const val STATE_TEXT = "lan_llm_model_text"
        private const val STATE_STATUS = "lan_llm_model_status"

        fun newInstance(key: String): LanLlmModelPreferenceDialogFragment =
            LanLlmModelPreferenceDialogFragment().apply {
                arguments = bundleOf(ARG_KEY to key)
            }
    }
}
