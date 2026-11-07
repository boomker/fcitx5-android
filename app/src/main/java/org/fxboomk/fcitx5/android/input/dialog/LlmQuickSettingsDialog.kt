/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.dialog

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.LlmCatalogClient
import org.fxboomk.fcitx5.android.input.predict.LlmCatalogPolicy
import org.fxboomk.fcitx5.android.input.predict.LlmLocalModelManager
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import splitties.dimensions.dp

internal class LlmQuickSettingsDialog(
    context: Context,
    private val scope: CoroutineScope,
    private val fetchModels: suspend (LlmPrefs.Config) -> List<LlmCatalogClient.RemoteModel> =
        LlmCatalogClient()::fetchModels,
) {
    // IME contexts need an explicit app theme for Material dialogs and dropdowns.
    private val themed = ContextThemeWrapper(context, R.style.Theme_FcitxAppTheme)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val modelsContainer = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
    private val catalogJobs = mutableListOf<Job>()
    private var generation = 0
    private lateinit var dialog: AlertDialog

    fun build(): AlertDialog {
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(themed.dp(24), themed.dp(8), themed.dp(24), themed.dp(8))
        }
        content.addView(label(themed.getString(R.string.llm_provider)))
        content.addView(selector(
            LlmPrefs.Provider.entries.map { themed.getString(it.titleRes) },
            LlmPrefs.currentProvider(prefs).ordinal,
        ) { index ->
            val next = LlmPrefs.Provider.entries[index]
            val current = LlmPrefs.currentProvider(prefs)
            if (next != current) {
                val oldPrimary = LlmPrefs.primaryBaseUrl(prefs)
                    ?: LlmPrefs.providerDefaultBaseUrl(current, prefs)
                val legacyModel = prefs.getString(LlmPrefs.KEY_MODEL, "").orEmpty().trim()
                if (legacyModel.isNotBlank() && LlmPrefs.getScopedModel(prefs, current, oldPrimary).isBlank()) {
                    if (LlmPrefs.parseBaseUrls(prefs.getString(LlmPrefs.KEY_BASE_URL, "").orEmpty()).isEmpty()) {
                        LlmPrefs.persistScopedModel(prefs, current, oldPrimary, legacyModel)
                    } else {
                        LlmPrefs.writeScopedModels(
                            prefs, current, mapOf(LlmPrefs.domainOf(oldPrimary) to legacyModel),
                            LlmPrefs.domainOf(oldPrimary),
                        )
                    }
                }
                LlmPrefs.switchProviderBaseUrls(prefs, current, next)
                val primary = LlmPrefs.primaryBaseUrl(prefs)
                    ?: LlmPrefs.providerDefaultBaseUrl(next, prefs)
                LlmPrefs.syncScopedApiKeyToActivePreferences(prefs, next, primary)
                LlmPrefs.syncScopedModelToActivePreferences(prefs, next, primary)
                prefs.edit()
                    .putString(LlmPrefs.KEY_PROVIDER, next.value)
                    .putString(LlmPrefs.KEY_RUNTIME, LlmPrefs.runtimeForProvider(next).value)
                    .apply()
                refreshModels()
            }
        })
        content.addView(modelsContainer)
        content.addView(label(themed.getString(R.string.llm_thinking_effort)))
        content.addView(selector(
            LlmPrefs.ThinkingEffort.entries.map { themed.getString(it.titleRes) },
            LlmPrefs.currentThinkingEffort(prefs).ordinal,
        ) { index ->
            val effort = LlmPrefs.ThinkingEffort.entries[index]
            if (effort != LlmPrefs.currentThinkingEffort(prefs)) {
                LlmPrefs.writeThinkingEffort(prefs, effort)
            }
        })
        dialog = MaterialAlertDialogBuilder(themed)
            .setTitle(R.string.llm_quick_settings)
            .setView(ScrollView(themed).apply { addView(content) })
            .setPositiveButton(R.string.done, null)
            .create()
        dialog.setOnShowListener { refreshModels() }
        // The IME service owns the dismiss listener; detach still cancels our work.
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                cancelCatalogRequests()
            }
        })
        return dialog
    }

    private fun cancelCatalogRequests() {
        generation++
        catalogJobs.forEach { it.cancel() }
        catalogJobs.clear()
    }

    private fun refreshModels() {
        cancelCatalogRequests()
        modelsContainer.removeAllViews()
        val provider = LlmPrefs.currentProvider(prefs)
        if (LlmPrefs.currentRuntime(prefs) == LlmPrefs.Runtime.LocalOnDevice) {
            modelsContainer.addView(label(themed.getString(R.string.llm_model)))
            modelsContainer.addView(TextView(themed).apply {
                text = LlmLocalModelManager.currentModel(themed)?.displayName
                    ?: themed.getString(R.string.llm_local_model_not_configured)
            })
            return
        }
        val domains = LlmPrefs.endpointDomains(prefs).ifEmpty {
            if (LlmPrefs.parseBaseUrls(prefs.getString(LlmPrefs.KEY_BASE_URL, "").orEmpty()).isEmpty()) {
                val defaultUrl = LlmPrefs.read(prefs).baseUrl
                if (defaultUrl.isNotBlank()) {
                    listOf(LlmPrefs.EndpointDomain(LlmPrefs.domainOf(defaultUrl), defaultUrl))
                } else emptyList()
            } else emptyList()
        }
        if (domains.isEmpty()) {
            modelsContainer.addView(label(themed.getString(R.string.llm_model)))
            modelsContainer.addView(TextView(themed).apply {
                text = themed.getString(R.string.llm_endpoint_none_hint)
            })
        }
        domains.forEach { entry -> addModelSelector(provider, entry, domains.size > 1) }
    }

    private fun addModelSelector(
        provider: LlmPrefs.Provider,
        entry: LlmPrefs.EndpointDomain,
        showDomain: Boolean,
    ) {
        val config = LlmPrefs.read(prefs, LlmPrefs.Overrides(baseUrl = entry.representativeBaseUrl))
        var selectedModel = LlmPrefs.getScopedModel(prefs, provider, entry.representativeBaseUrl)
            .ifBlank {
                if (entry.domain == (LlmPrefs.primaryDomain(prefs) ?: LlmPrefs.domainOf(config.baseUrl))) {
                    prefs.getString(LlmPrefs.KEY_MODEL, "").orEmpty().trim()
                } else ""
            }
            .ifBlank { LlmPrefs.providerDefaultModel(provider) }
        val title = themed.getString(R.string.llm_model)
        modelsContainer.addView(label(if (showDomain) "$title · ${entry.domain}" else title))
        val modelSelector = AppCompatSpinner(themed, Spinner.MODE_DROPDOWN).apply {
            minimumHeight = themed.dp(48)
        }
        fun bindModels(models: List<String>) {
            val choices = (listOf(selectedModel) + models).filter(String::isNotBlank).distinct()
            val values = if (selectedModel.isBlank()) listOf("") + choices else choices
            modelSelector.onItemSelectedListener = null
            modelSelector.adapter = adapter(values.map {
                it.ifBlank { themed.getString(R.string.llm_model_summary_none) }
            })
            modelSelector.setSelection(values.indexOf(selectedModel).coerceAtLeast(0))
            modelSelector.isEnabled = choices.isNotEmpty()
            modelSelector.onItemSelectedListener = selectionListener { index ->
                val model = values[index]
                if (model.isNotBlank() && model != selectedModel) {
                    if (LlmPrefs.parseBaseUrls(prefs.getString(LlmPrefs.KEY_BASE_URL, "").orEmpty()).isEmpty()) {
                        LlmPrefs.writeBaseUrls(
                            prefs, listOf(entry.representativeBaseUrl), listOf(entry.representativeBaseUrl),
                        )
                    }
                    LlmPrefs.writeScopedModels(
                        prefs, provider, mapOf(entry.domain to model), LlmPrefs.primaryDomain(prefs),
                    )
                    selectedModel = model
                }
            }
        }
        bindModels(LlmCatalogPolicy.builtInModelsForProvider(provider).map { it.id })
        modelsContainer.addView(modelSelector, fullWidth())
        val status = TextView(themed).apply {
            textSize = 12f
            text = themed.getString(R.string.llm_model_fetching)
        }
        modelsContainer.addView(status, fullWidth())
        val requestGeneration = generation
        catalogJobs += scope.launch {
            val result = try {
                Result.success(fetchModels(config))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (requestGeneration != generation || !dialog.isShowing) return@launch
            result.onSuccess { models ->
                bindModels(models.map { it.id })
                status.isVisible = models.isEmpty()
                status.text = themed.getString(R.string.llm_model_fetch_empty)
            }.onFailure { error ->
                status.text = themed.getString(R.string.llm_model_fetch_failed, error.message?.take(160).orEmpty())
            }
        }
    }

    private fun selector(labels: List<String>, selected: Int, onSelected: (Int) -> Unit): Spinner =
        AppCompatSpinner(themed, Spinner.MODE_DROPDOWN).apply {
            layoutParams = fullWidth()
            minimumHeight = themed.dp(48)
            adapter = adapter(labels)
            setSelection(selected)
            onItemSelectedListener = selectionListener(onSelected)
        }

    private fun adapter(labels: List<String>) =
        ArrayAdapter(themed, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun selectionListener(onSelected: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected(position)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun label(text: String) = TextView(themed).apply {
        this.text = text
        textSize = 14f
        setPadding(0, themed.dp(12), 0, themed.dp(4))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
