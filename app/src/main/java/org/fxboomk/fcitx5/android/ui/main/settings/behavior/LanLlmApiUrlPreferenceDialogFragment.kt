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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LanLlmPrefs
import org.fxboomk.fcitx5.android.ui.main.modified.restore
import splitties.dimensions.dp

class LanLlmApiUrlPreferenceDialogFragment : DialogFragment() {
    private lateinit var editText: EditText
    private lateinit var chatApiSwitch: SwitchCompat

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
        val currentText = savedInstanceState?.getString(STATE_TEXT)
            ?: apiUrlPreference.text.orEmpty()
        val currentChatApiEnabled = savedInstanceState?.getBoolean(STATE_CHAT_API_ENABLED)
            ?: (prefs?.getBoolean(LanLlmPrefs.KEY_CHAT_API_ENABLED, false) == true)

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

        chatApiSwitch = SwitchCompat(context).apply {
            text = context.getString(R.string.lan_llm_chat_api_enabled)
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
            chatApiSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = context.dp(12)
            }
        )

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.lan_llm_api_url)
            .setView(contentView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                persistValues()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.default_, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                apiUrlPreference.restore()
                editText.setText(apiUrlPreference.text.orEmpty())
                editText.setSelection(editText.text.length)
                chatApiSwitch.isChecked = false
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
        apiUrlPreference.preferenceManager.sharedPreferences
            ?.edit()
            ?.putBoolean(LanLlmPrefs.KEY_CHAT_API_ENABLED, chatApiSwitch.isChecked)
            ?.apply()
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val STATE_TEXT = "lan_llm_api_url_text"
        private const val STATE_CHAT_API_ENABLED = "lan_llm_chat_api_enabled"

        fun newInstance(key: String): LanLlmApiUrlPreferenceDialogFragment =
            LanLlmApiUrlPreferenceDialogFragment().apply {
                arguments = bundleOf(ARG_KEY to key)
            }
    }
}
