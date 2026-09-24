/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

class LlmModelPreferenceDialogFragment : DialogFragment() {
    private lateinit var domainContainer: LinearLayout
    private lateinit var statusView: TextView
    private val catalogClient = LlmCatalogClient()

    // domain (host) -> model name, kept live by each field so it survives rotation.
    private val modelByDomain = linkedMapOf<String, String>()

    private sealed interface ModelChoiceItem {
        data class Group(val label: String) : ModelChoiceItem
        data class Model(val value: LlmCatalogClient.RemoteModel) : ModelChoiceItem
    }

    private val preferenceKey: String
        get() = requireArguments().getString(ARG_KEY).orEmpty()

    private val preferenceFragment: PreferenceFragmentCompat
        get() = requireParentFragment() as PreferenceFragmentCompat

    private val modelPreference: EditTextPreference
        get() = preferenceFragment.findPreference<EditTextPreference>(preferenceKey)
            ?: error("Preference not found for key=$preferenceKey")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val prefs = modelPreference.preferenceManager.sharedPreferences
        val provider = prefs?.let(LlmPrefs::currentProvider) ?: LlmPrefs.Provider.Custom
        val currentStatus = savedInstanceState?.getString(STATE_STATUS).orEmpty()

        modelByDomain.clear()
        val savedDomains = savedInstanceState?.getStringArrayList(STATE_DOMAINS)
        val savedValues = savedInstanceState?.getStringArrayList(STATE_VALUES)
        if (savedDomains != null && savedValues != null && savedDomains.size == savedValues.size) {
            savedDomains.forEachIndexed { index, domain -> modelByDomain[domain] = savedValues[index] }
        } else if (prefs != null) {
            LlmPrefs.endpointDomains(prefs).forEach { entry ->
                modelByDomain[entry.domain] = LlmPrefs.getScopedModel(prefs, provider, entry.representativeBaseUrl)
            }
        }

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = context.dp(24)
            setPadding(padding, context.dp(8), padding, 0)
        }
        domainContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        statusView = TextView(context).apply {
            text = currentStatus
            isVisible = currentStatus.isNotBlank()
        }
        contentView.addView(domainContainer, fullWidth(0))
        contentView.addView(statusView, fullWidth(context.dp(12)))
        refreshDomainRows()

        return AlertDialog.Builder(context)
            .setTitle(R.string.llm_model)
            .setView(ScrollView(context).apply { addView(contentView) })
            .setPositiveButton(android.R.string.ok) { _, _ -> persistValue() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val entries = modelByDomain.entries.toList()
        outState.putStringArrayList(STATE_DOMAINS, ArrayList(entries.map { it.key }))
        outState.putStringArrayList(STATE_VALUES, ArrayList(entries.map { it.value }))
        if (::statusView.isInitialized) {
            outState.putString(STATE_STATUS, statusView.text?.toString().orEmpty())
        }
    }
    private fun refreshDomainRows() {
        val context = requireContext()
        val prefs = modelPreference.preferenceManager.sharedPreferences
        val provider = prefs?.let(LlmPrefs::currentProvider) ?: LlmPrefs.Provider.Custom
        val domains = prefs?.let(LlmPrefs::endpointDomains).orEmpty()
        domainContainer.removeAllViews()
        if (domains.isEmpty()) {
            domainContainer.addView(TextView(context).apply {
                text = context.getString(R.string.llm_endpoint_none_hint)
            })
            return
        }
        domains.forEach { entry ->
            val domain = entry.domain
            if (domain !in modelByDomain) {
                modelByDomain[domain] =
                    prefs?.let { LlmPrefs.getScopedModel(it, provider, entry.representativeBaseUrl) }.orEmpty()
            }
            val modelEdit = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = context.getString(R.string.llm_endpoint_model_hint)
                setText(modelByDomain[domain])
                addTextChangedListener(afterTextChanged { modelByDomain[domain] = it })
            }
            val fetchButton = iconButton(context, R.drawable.ic_baseline_arrow_drop_down_24, R.string.llm_fetch_models) {
                fetchModelsForDomain(entry, modelEdit)
            }
            val fieldRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(modelEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    fetchButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            domainContainer.addView(domainRow(context, domain, fieldRow))
        }
    }
    private fun persistValue() {
        val prefs = modelPreference.preferenceManager.sharedPreferences ?: return
        val provider = LlmPrefs.currentProvider(prefs)
        val primary = LlmPrefs.primaryDomain(prefs)
        LlmPrefs.writeScopedModels(prefs, provider, modelByDomain, primary)
        val primaryModel = primary?.let { modelByDomain[it] }.orEmpty()
        if (primaryModel.isNotBlank()) modelPreference.text = primaryModel
    }

    private fun fetchModelsForDomain(entry: LlmPrefs.EndpointDomain, field: EditText) {
        val prefs = modelPreference.preferenceManager.sharedPreferences ?: return
        val config = LlmPrefs.read(
            prefs,
            LlmPrefs.Overrides(
                baseUrl = entry.representativeBaseUrl,
                model = modelByDomain[entry.domain]?.trim(),
            ),
        )
        statusView.isVisible = true
        statusView.text = getString(R.string.llm_model_fetching)
        lifecycleScope.launch {
            val result = runCatching { catalogClient.fetchModels(config) }
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    statusView.text = getString(R.string.llm_model_fetch_empty)
                    return@onSuccess
                }
                statusView.text = getString(R.string.llm_model_fetch_success, models.size)
                showModelPicker(models, field, entry.domain)
            }.onFailure { error ->
                statusView.text = getString(R.string.llm_model_fetch_failed, error.message?.take(160).orEmpty())
                Toast.makeText(requireContext(), error.message?.take(160).orEmpty(), Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun showModelPicker(
        models: List<LlmCatalogClient.RemoteModel>,
        field: EditText,
        domain: String,
    ) {
        val items = buildChoiceItems(models)
        val adapter = object : ArrayAdapter<ModelChoiceItem>(
            requireContext(),
            android.R.layout.simple_list_item_1,
            items,
        ) {
            override fun isEnabled(position: Int): Boolean = getItem(position) is ModelChoiceItem.Model

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                when (val item = getItem(position)) {
                    is ModelChoiceItem.Group -> {
                        view.text = getString(R.string.llm_model_group_prefix, item.label)
                        view.setTypeface(null, Typeface.BOLD)
                        view.alpha = 0.75f
                    }
                    is ModelChoiceItem.Model -> {
                        view.text = item.value.id
                        view.setTypeface(null, Typeface.NORMAL)
                        view.alpha = 1f
                    }
                    null -> Unit
                }
                return view
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.llm_model_select)
            .setAdapter(adapter) { _, which ->
                val selected = (items.getOrNull(which) as? ModelChoiceItem.Model)?.value?.id ?: return@setAdapter
                field.setText(selected)
                field.setSelection(selected.length)
                modelByDomain[domain] = selected
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    private fun buildChoiceItems(models: List<LlmCatalogClient.RemoteModel>): List<ModelChoiceItem> = buildList {
        val prefs = modelPreference.preferenceManager.sharedPreferences
        val provider = LlmPrefs.currentProvider(prefs ?: return@buildList)
        val unprefixedGroupLabel = resolveUnprefixedGroupLabel(
            providerLabel = getString(provider.titleRes),
            isCustomProvider = provider == LlmPrefs.Provider.Custom,
            emptyLabel = getString(R.string.llm_model_group_empty),
        )
        var lastGroupLabel: String? = null
        models.forEach { model ->
            val groupLabel = model.providerPrefix ?: unprefixedGroupLabel
            if (groupLabel != lastGroupLabel) {
                add(ModelChoiceItem.Group(groupLabel))
                lastGroupLabel = groupLabel
            }
            add(ModelChoiceItem.Model(model))
        }
    }
    private fun domainRow(
        context: android.content.Context,
        domain: String,
        field: View,
    ): View {
        val label = TextView(context).apply {
            text = domain
            setTypeface(typeface, Typeface.BOLD)
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
        private const val STATE_STATUS = "llm_model_status"
        private const val STATE_DOMAINS = "llm_model_domains"
        private const val STATE_VALUES = "llm_model_values"

        internal fun resolveUnprefixedGroupLabel(
            providerLabel: String,
            isCustomProvider: Boolean,
            emptyLabel: String,
        ): String = if (isCustomProvider) emptyLabel else providerLabel

        fun newInstance(key: String): LlmModelPreferenceDialogFragment =
            LlmModelPreferenceDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_KEY, key) }
            }
    }
}
