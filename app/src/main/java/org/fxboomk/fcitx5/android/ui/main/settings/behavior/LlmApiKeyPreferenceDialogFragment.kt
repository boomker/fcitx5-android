/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.GetChars
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LlmCatalogClient
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import splitties.dimensions.dp

class LlmApiKeyPreferenceDialogFragment : DialogFragment() {
    private lateinit var domainContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var sharePrimaryKeySwitch: SwitchCompat
    private val catalogClient = LlmCatalogClient()

    // domain (host) -> key, kept live by each field so it survives rotation.
    private val keyByDomain = linkedMapOf<String, String>()

    private val preferenceKey: String
        get() = requireArguments().getString(ARG_KEY).orEmpty()

    private val preferenceFragment: PreferenceFragmentCompat
        get() = requireParentFragment() as PreferenceFragmentCompat

    private val apiKeyPreference: EditTextPreference
        get() = preferenceFragment.findPreference<EditTextPreference>(preferenceKey)
            ?: error("Preference not found for key=$preferenceKey")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val prefs = apiKeyPreference.preferenceManager.sharedPreferences
        val provider = prefs?.let(LlmPrefs::currentProvider) ?: LlmPrefs.Provider.Custom
        val currentStatus = savedInstanceState?.getString(STATE_STATUS).orEmpty()
        val sharePrimaryKey = if (savedInstanceState?.containsKey(STATE_SHARE_PRIMARY_API_KEY) == true) {
            savedInstanceState.getBoolean(STATE_SHARE_PRIMARY_API_KEY)
        } else {
            prefs?.getBoolean(LlmPrefs.KEY_SHARE_PRIMARY_API_KEY, false) ?: false
        }

        keyByDomain.clear()
        val savedDomains = savedInstanceState?.getStringArrayList(STATE_DOMAINS)
        val savedValues = savedInstanceState?.getStringArrayList(STATE_VALUES)
        if (savedDomains != null && savedValues != null && savedDomains.size == savedValues.size) {
            savedDomains.forEachIndexed { index, domain -> keyByDomain[domain] = savedValues[index] }
        } else if (prefs != null) {
            LlmPrefs.endpointDomains(prefs).forEach { entry ->
                keyByDomain[entry.domain] = initialKeyFor(prefs, provider, entry)
            }
        }

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dp(24)
            setPadding(padding, context.dp(8), padding, 0)
        }
        domainContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sharePrimaryKeySwitch = SwitchCompat(context).apply {
            text = context.getString(R.string.llm_share_primary_api_key)
            isChecked = sharePrimaryKey
            setOnCheckedChangeListener { _, _ -> refreshDomainRows() }
        }
        statusView = TextView(context).apply {
            text = currentStatus
            isVisible = currentStatus.isNotBlank()
        }
        contentView.addView(domainContainer, fullWidth(0))
        contentView.addView(sharePrimaryKeySwitch, fullWidth(context.dp(12)))
        contentView.addView(statusView, fullWidth(context.dp(12)))
        refreshDomainRows()

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.llm_api_key)
            .setView(ScrollView(context).apply { addView(contentView) })
            .setPositiveButton(android.R.string.ok) { _, _ -> persistValue() }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.llm_test_connectivity, null)
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
        val entries = keyByDomain.entries.toList()
        outState.putStringArrayList(STATE_DOMAINS, ArrayList(entries.map { it.key }))
        outState.putStringArrayList(STATE_VALUES, ArrayList(entries.map { it.value }))
        if (::statusView.isInitialized) {
            outState.putString(STATE_STATUS, statusView.text?.toString().orEmpty())
        }
        if (::sharePrimaryKeySwitch.isInitialized) {
            outState.putBoolean(STATE_SHARE_PRIMARY_API_KEY, sharePrimaryKeySwitch.isChecked)
        }
    }

    private fun initialKeyFor(
        prefs: android.content.SharedPreferences,
        provider: LlmPrefs.Provider,
        entry: LlmPrefs.EndpointDomain,
    ): String {
        val raw = LlmPrefs.getScopedApiKeyRaw(prefs, provider, entry.representativeBaseUrl)
        if (raw.isNotBlank()) return raw
        // Only the primary (first enabled) domain may inherit the legacy shared key;
        // other domains stay blank so each can hold its own distinct key.
        return if (entry.domain == LlmPrefs.primaryDomain(prefs)) {
            prefs.getString(LlmPrefs.KEY_API_KEY, "").orEmpty().trim()
        } else {
            ""
        }
    }

    private fun refreshDomainRows() {
        val context = requireContext()
        val prefs = apiKeyPreference.preferenceManager.sharedPreferences
        val provider = prefs?.let(LlmPrefs::currentProvider) ?: LlmPrefs.Provider.Custom
        val domains = prefs?.let(LlmPrefs::endpointDomains).orEmpty()
        val primaryDomain = prefs?.let(LlmPrefs::primaryDomain)
        domainContainer.removeAllViews()
        if (domains.isEmpty()) {
            domainContainer.addView(TextView(context).apply {
                text = context.getString(R.string.llm_endpoint_none_hint)
            })
            return
        }
        domains.forEach { entry ->
            val domain = entry.domain
            if (domain !in keyByDomain) {
                keyByDomain[domain] = prefs?.let { initialKeyFor(it, provider, entry) }.orEmpty()
            }
            val keyEdit = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                transformationMethod = PartialKeyMask
                hint = context.getString(
                    if (domain == primaryDomain) {
                        R.string.llm_api_url_endpoint_key_hint
                    } else if (sharePrimaryKeySwitch.isChecked) {
                        R.string.llm_api_url_backup_key_shared_hint
                    } else {
                        R.string.llm_api_url_backup_key_hint
                    },
                )
                setText(keyByDomain[domain])
                addTextChangedListener(afterTextChanged { keyByDomain[domain] = it })
            }
            domainContainer.addView(domainRow(context, domain, keyEdit))
        }
    }

    private fun persistValue() {
        val prefs = apiKeyPreference.preferenceManager.sharedPreferences ?: return
        val provider = LlmPrefs.currentProvider(prefs)
        val primary = LlmPrefs.primaryDomain(prefs)
        LlmPrefs.writeScopedApiKeys(prefs, provider, keyByDomain, primary)
        prefs.edit()
            .putBoolean(LlmPrefs.KEY_SHARE_PRIMARY_API_KEY, sharePrimaryKeySwitch.isChecked)
            .apply()
        apiKeyPreference.text = primary?.let { keyByDomain[it] }.orEmpty()
    }

    private fun runConnectivityCheck(dialog: AlertDialog) {
        val prefs = apiKeyPreference.preferenceManager.sharedPreferences ?: return
        val primary = LlmPrefs.primaryDomain(prefs)
        val pendingApiKey = primary?.let { keyByDomain[it] }.orEmpty().trim()
        val config = LlmPrefs.read(prefs, LlmPrefs.Overrides(apiKey = pendingApiKey))
        statusView.isVisible = true
        statusView.text = getString(R.string.llm_connectivity_checking)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        lifecycleScope.launch {
            val result = runCatching { catalogClient.checkConnectivity(config) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
            result.onSuccess { success ->
                statusView.text = getString(
                    R.string.llm_connectivity_success,
                    success.modelCount,
                    success.endpoint,
                )
            }.onFailure { error ->
                statusView.text = getString(
                    R.string.llm_connectivity_failed,
                    error.message?.take(160).orEmpty(),
                )
                Toast.makeText(requireContext(), error.message?.take(160).orEmpty(), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun domainRow(
        context: android.content.Context,
        domain: String,
        field: android.view.View,
    ): android.view.View {
        val label = TextView(context).apply {
            text = domain
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = fullWidth(context.dp(8))
            addView(label, fullWidth(0))
            addView(field, fullWidth(0))
        }
    }

    private fun afterTextChanged(block: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
    }

    private fun fullWidth(topMargin: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }
    /** Shows the first and last 4 characters of a key in plain text and masks the
     *  middle, so a stored key is recognizable without exposing the whole secret.
     *  Must wrap the live source (so its length always matches) — returning a static
     *  snapshot desyncs the text layout and crashes on edit/paste (IndexOutOfBounds). */
    private object PartialKeyMask : android.text.method.TransformationMethod {
        override fun getTransformation(source: CharSequence?, view: android.view.View?): CharSequence =
            if (source == null) "" else MaskedKeyText(source)

        override fun onFocusChanged(
            view: android.view.View?,
            sourceText: CharSequence?,
            focused: Boolean,
            direction: Int,
            previouslyFocusedRect: android.graphics.Rect?,
        ) = Unit
    }

    private class MaskedKeyText(private val source: CharSequence) : CharSequence, GetChars {
        override val length: Int get() = source.length

        override fun get(index: Int): Char =
            if (isMasked(index, source.length)) MASK else source[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            val buffer = CharArray(endIndex - startIndex)
            getChars(startIndex, endIndex, buffer, 0)
            return String(buffer)
        }

        override fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) {
            if (source is GetChars) {
                source.getChars(start, end, dest, destoff)
            } else {
                for (i in start until end) dest[destoff + i - start] = source[i]
            }
            val n = source.length
            for (i in start until end) {
                if (isMasked(i, n)) dest[destoff + i - start] = MASK
            }
        }

        override fun toString(): String {
            val buffer = CharArray(source.length)
            getChars(0, source.length, buffer, 0)
            return String(buffer)
        }

        private fun isMasked(index: Int, length: Int): Boolean =
            length > VISIBLE_EDGE * 2 && index >= VISIBLE_EDGE && index < length - VISIBLE_EDGE

        companion object {
            private const val VISIBLE_EDGE = 4
            private const val MASK = '•'
        }
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val STATE_STATUS = "llm_api_key_status"
        private const val STATE_DOMAINS = "llm_api_key_domains"
        private const val STATE_VALUES = "llm_api_key_values"
        private const val STATE_SHARE_PRIMARY_API_KEY = "llm_share_primary_api_key"

        fun newInstance(key: String): LlmApiKeyPreferenceDialogFragment =
            LlmApiKeyPreferenceDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_KEY, key) }
            }
    }
}
