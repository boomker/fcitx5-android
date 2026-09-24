/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
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
    private lateinit var endpointContainer: LinearLayout

    // Enabled set is kept live by each checkbox, so it survives row rebuilds and rotation.
    private val enabledUrls = linkedSetOf<String>()
    private var renderedUrls: List<String> = emptyList()

    private val rebuildRows = Runnable {
        refreshEndpointRows(LlmPrefs.parseBaseUrls(editText.text?.toString().orEmpty()))
    }

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
            ?: apiUrlPreference.text.orEmpty().ifBlank { LlmPrefs.providerDefaultBaseUrl(provider, prefs) }
        val currentChatApiEnabled = savedInstanceState?.getBoolean(STATE_CHAT_API_ENABLED)
            ?: (prefs?.let(LlmPrefs::isChatApiEnabled) == true)
        val currentEndpoints = LlmPrefs.parseBaseUrls(currentText)

        enabledUrls.clear()
        enabledUrls.addAll(
            savedInstanceState?.getStringArrayList(STATE_SELECTED_ENDPOINTS)
                ?: prefs?.let { LlmPrefs.selectedBaseUrls(it, currentEndpoints) }.orEmpty()
        )

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dp(24)
            setPadding(padding, context.dp(8), padding, 0)
        }
        editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 6
            setHorizontallyScrolling(false)
            hint = context.getString(R.string.llm_api_url_multiline_hint)
            setText(currentText)
            setSelection(text.length)
        }
        hintView = TextView(context).apply {
            text = context.getString(
                R.string.llm_api_url_default_hint,
                provider.defaultBaseUrl ?: LlmPrefs.providerDefaultBaseUrl(provider, prefs),
            )
        }
        endpointContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        chatApiSwitch = SwitchCompat(context).apply {
            text = context.getString(R.string.llm_chat_api_enabled)
            isChecked = currentChatApiEnabled
        }
        contentView.addView(editText, fullWidth(0))
        contentView.addView(hintView, fullWidth(context.dp(12)))
        contentView.addView(endpointContainer, fullWidth(context.dp(8)))
        contentView.addView(chatApiSwitch, fullWidth(context.dp(12)))
        refreshEndpointRows(currentEndpoints, force = true)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.llm_api_url)
            .setView(ScrollView(context).apply { addView(contentView) })
            .setPositiveButton(android.R.string.ok) { _, _ -> persistValues() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            editText.addTextChangedListener(afterTextChanged {
                editText.removeCallbacks(rebuildRows)
                editText.postDelayed(rebuildRows, ROW_REBUILD_DELAY_MS)
            })
        }
        return dialog
    }

    override fun onDestroyView() {
        if (::editText.isInitialized) editText.removeCallbacks(rebuildRows)
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::editText.isInitialized) {
            outState.putString(STATE_TEXT, editText.text?.toString().orEmpty())
            outState.putStringArrayList(STATE_SELECTED_ENDPOINTS, ArrayList(enabledUrls))
        }
        if (::chatApiSwitch.isInitialized) {
            outState.putBoolean(STATE_CHAT_API_ENABLED, chatApiSwitch.isChecked)
        }
    }
    private fun persistValues() {
        val baseUrls = LlmPrefs.parseBaseUrls(editText.text?.toString().orEmpty())
        val newValue = LlmPrefs.encodeBaseUrls(baseUrls)
        if (!apiUrlPreference.callChangeListener(newValue)) return
        val prefs = apiUrlPreference.preferenceManager.sharedPreferences ?: return
        val provider = LlmPrefs.currentProvider(prefs)
        val selected = baseUrls.filter { it in enabledUrls }
        val removed = LlmPrefs.parseBaseUrls(prefs.getString(LlmPrefs.KEY_BASE_URL, "").orEmpty())
            .filterNot { it in baseUrls }
        // Keep the old primary URL available to bind its legacy key before changing it.
        LlmPrefs.writeBaseUrls(prefs, baseUrls, selected)
        LlmPrefs.removeScopedEndpointSettings(prefs, provider, removed)
        apiUrlPreference.text = newValue
        if (provider == LlmPrefs.Provider.Custom) {
            LlmPrefs.persistCustomDefaultBaseUrl(
                prefs,
                baseUrls.firstOrNull() ?: LlmPrefs.providerDefaultBaseUrl(provider, prefs),
            )
        }
        prefs.edit().putBoolean(LlmPrefs.KEY_CHAT_API_ENABLED, chatApiSwitch.isChecked).apply()
        val primary = selected.firstOrNull() ?: baseUrls.firstOrNull()
        if (primary != null) {
            preferenceFragment.findPreference<EditTextPreference>(LlmPrefs.KEY_API_KEY)?.text =
                LlmPrefs.getScopedApiKey(prefs, provider, primary)
            val scopedModel = LlmPrefs.syncScopedModelToActivePreferences(prefs, provider, primary)
            preferenceFragment.findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL)?.text = scopedModel
        }
    }

    private fun removeUrl(url: String) {
        val remaining = LlmPrefs.parseBaseUrls(editText.text?.toString().orEmpty()).filter { it != url }
        enabledUrls.remove(url)
        editText.removeCallbacks(rebuildRows)
        editText.setText(LlmPrefs.encodeBaseUrls(remaining))
        editText.setSelection(editText.text.length)
        editText.removeCallbacks(rebuildRows)
        refreshEndpointRows(remaining, force = true)
    }
    private fun refreshEndpointRows(urls: List<String>, force: Boolean = false) {
        if (!force && urls == renderedUrls) return
        val context = requireContext()
        endpointContainer.removeAllViews()
        urls.forEach { url ->
            val checkBox = CheckBox(context).apply {
                text = url
                isChecked = url in enabledUrls
                setOnCheckedChangeListener { _, checked ->
                    if (checked) enabledUrls.add(url) else enabledUrls.remove(url)
                }
            }
            val deleteButton = iconButton(context, R.drawable.ic_baseline_delete_24, R.string.llm_endpoint_delete) {
                removeUrl(url)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(checkBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    deleteButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            endpointContainer.addView(row, fullWidth(context.dp(4)))
        }
        renderedUrls = urls
    }

    private fun afterTextChanged(block: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
    }

    private fun iconButton(
        context: android.content.Context,
        iconRes: Int,
        descriptionRes: Int,
        onClick: () -> Unit,
    ): ImageButton {
        val background = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
        val tint = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, tint, true)
        return ImageButton(context).apply {
            setImageResource(iconRes)
            setBackgroundResource(background.resourceId)
            imageTintList = if (tint.resourceId != 0) {
                androidx.core.content.ContextCompat.getColorStateList(context, tint.resourceId)
            } else {
                android.content.res.ColorStateList.valueOf(tint.data)
            }
            contentDescription = context.getString(descriptionRes)
            setOnClickListener { onClick() }
        }
    }

    private fun fullWidth(topMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }
    companion object {
        private const val ARG_KEY = "key"
        private const val STATE_TEXT = "llm_api_url_text"
        private const val STATE_CHAT_API_ENABLED = "llm_chat_api_enabled"
        private const val STATE_SELECTED_ENDPOINTS = "llm_selected_endpoints"
        private const val ROW_REBUILD_DELAY_MS = 250L

        fun newInstance(key: String): LlmApiUrlPreferenceDialogFragment =
            LlmApiUrlPreferenceDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_KEY, key) }
            }
    }
}
