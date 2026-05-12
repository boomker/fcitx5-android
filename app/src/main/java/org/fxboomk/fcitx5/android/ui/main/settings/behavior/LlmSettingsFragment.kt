/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.GenAiLocalLlmRuntime
import org.fxboomk.fcitx5.android.input.predict.LlmClient
import org.fxboomk.fcitx5.android.input.predict.LlmLocalModelManager
import org.fxboomk.fcitx5.android.input.predict.LlmLocalResourceManager
import org.fxboomk.fcitx5.android.input.predict.LocalLlmPredictionRequest
import org.fxboomk.fcitx5.android.input.predict.LlmOutputMode
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import org.fxboomk.fcitx5.android.input.predict.LlmTaskMode
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withLoadingDialog
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fxboomk.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import org.fxboomk.fcitx5.android.utils.toast

class LlmSettingsFragment : PaddingPreferenceFragment() {
    private data class RecommendedLocalModel(
        val displayName: String,
        val description: String,
        val downloadUrl: String,
    )

    private data class ModelGenerationTestResult(
        val stage: String,
        val personaName: String,
        val inputPrefix: String,
        val suggestions: List<String>,
        val modelName: String,
        val modelId: String,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
        val error: String? = null,
    )

    private data class ImportSection(
        @StringRes val titleRes: Int,
        @StringRes val summaryRes: Int,
        @StringRes val actionRes: Int,
        val onClick: () -> Unit,
    )

    companion object {
        private const val PREF_LOCAL_MODEL_IMPORT = "llm_local_model_import_entry"
        private const val PREF_MODEL_TEST = "llm_model_test"

        private val recommendedLocalModels = listOf(
            RecommendedLocalModel(
                displayName = "Qwen3-0.6B-ONNX",
                description = "已验证可在当前本地运行时加载的推荐模型",
                downloadUrl = "onnx-community/Qwen3-0.6B-ONNX",
            )
        )
    }

    private lateinit var importModelLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importModelLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                performImportModel(uri)
            }
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference.key) {
            LlmPrefs.KEY_BASE_URL -> {
                if (childFragmentManager.findFragmentByTag(LlmApiUrlPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LlmApiUrlPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LlmApiUrlPreferenceDialogFragment::class.java.name)
                return
            }
            LlmPrefs.KEY_API_KEY -> {
                if (childFragmentManager.findFragmentByTag(LlmApiKeyPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LlmApiKeyPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LlmApiKeyPreferenceDialogFragment::class.java.name)
                return
            }
            LlmPrefs.KEY_MODEL -> {
                val runtime = preferenceManager.sharedPreferences?.let(LlmPrefs::currentRuntime)
                    ?: LlmPrefs.Runtime.Remote
                if (runtime == LlmPrefs.Runtime.LocalOnDevice) {
                    showLocalModelManagerDialog()
                    return
                }
                if (childFragmentManager.findFragmentByTag(LlmModelPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LlmModelPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LlmModelPreferenceDialogFragment::class.java.name)
                return
            }
        }
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferences?.let(LlmPrefs::migrateSeekBarBackedPreferences)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(MySwitchPreference(context).apply {
                key = LlmPrefs.KEY_ENABLED
                setTitle(R.string.llm_enable)
                setSummary(R.string.llm_enable_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(MySwitchPreference(context).apply {
                key = LlmPrefs.KEY_AUTO_PREDICT_ENABLED
                setTitle(R.string.llm_auto_predict)
                setSummary(R.string.llm_auto_predict_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(providerPreference())
            addPreference(textPreference(
                key = LlmPrefs.KEY_BASE_URL,
                titleRes = R.string.llm_api_url,
                defaultValue = "",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val raw = pref.text.orEmpty().trim()
                    if (raw.isNotBlank()) {
                        raw
                    } else {
                        val sharedPrefs = pref.preferenceManager.sharedPreferences
                        val provider = LlmPrefs.Provider.from(
                            sharedPrefs?.getString(LlmPrefs.KEY_PROVIDER, null)
                        )
                        context.getString(
                            R.string.llm_api_url_default_summary,
                            LlmPrefs.providerDefaultBaseUrl(provider, sharedPrefs),
                        )
                    }
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ))
            addPreference(textPreference(
                key = LlmPrefs.KEY_API_KEY,
                titleRes = R.string.llm_api_key,
                defaultValue = "",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    if (pref.text.isNullOrBlank()) {
                        context.getString(R.string._not_available_)
                    } else {
                        context.getString(R.string.llm_api_key_set)
                    }
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ))
            addPreference(textPreference(
                key = LlmPrefs.KEY_MODEL,
                titleRes = R.string.llm_model_status,
                defaultValue = "qwen",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val sharedPrefs = pref.preferenceManager.sharedPreferences
                    val runtime = sharedPrefs?.let(LlmPrefs::currentRuntime) ?: LlmPrefs.Runtime.Remote
                    if (runtime == LlmPrefs.Runtime.LocalOnDevice) {
                        LlmLocalModelManager.statusSummary(context)
                    } else {
                        val modelName = pref.text.orEmpty().ifBlank {
                            context.getString(R.string.llm_model_summary_hint)
                        }
                        val status = if (LlmPrefs.read(context).isUsable) {
                            context.getString(R.string.llm_model_status_remote_ready)
                        } else {
                            context.getString(R.string.llm_model_status_remote_unavailable)
                        }
                        context.getString(
                            R.string.llm_model_status_remote_summary,
                            modelName,
                            status,
                        )
                    }
                },
            ))
            addPreference(maxPredictionCandidatesPreference())
            addPreference(Preference(context).apply {
                key = "llm_advanced_entry"
                setTitle(R.string.llm_advanced)
                setSummary(R.string.llm_advanced_summary)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.LlmAdvanced)
                    true
                }
            })
            addLocalModelPreferences(this)
        }
        ensureProviderDefaultsAndScopedApiKey()
    }

    private fun addLocalModelPreferences(screen: PreferenceScreen) {
        screen.addPreference(Preference(requireContext()).apply {
            key = PREF_LOCAL_MODEL_IMPORT
            setTitle(R.string.llm_local_model_import)
            setSummary(R.string.llm_local_model_import_merged_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                showImportModelDialog()
                true
            }
        })
        screen.addPreference(Preference(requireContext()).apply {
            key = PREF_MODEL_TEST
            setTitle(R.string.llm_model_test)
            setSummary(R.string.llm_model_test_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                showModelGenerationTestDialog()
                true
            }
        })
    }
    private fun maxPredictionCandidatesPreference() = DialogSeekBarPreference(requireContext()).apply {
        key = LlmPrefs.KEY_MAX_PREDICTION_CANDIDATES
        setTitle(R.string.llm_max_prediction_candidates)
        setDialogTitle(R.string.llm_max_prediction_candidates)
        setDefaultValue(4)
        min = 1
        max = 8
        step = 1
        unit = ""
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
    }

    private fun providerPreference() = ListPreference(requireContext()).apply {
        key = LlmPrefs.KEY_PROVIDER
        setTitle(R.string.llm_provider)
        setDialogTitle(R.string.llm_provider)
        entries = LlmPrefs.Provider.entries.map { context.getString(it.titleRes) }.toTypedArray()
        entryValues = LlmPrefs.Provider.entries.map { it.value }.toTypedArray()
        setDefaultValue(LlmPrefs.Provider.Custom.value)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, newValue ->
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceChangeListener true
            val nextProvider = LlmPrefs.Provider.from(newValue?.toString())
            val nextRuntime = LlmPrefs.runtimeForProvider(nextProvider)
            prefs.edit().putString(LlmPrefs.KEY_RUNTIME, nextRuntime.value).apply()
            val nextBase = LlmPrefs.providerDefaultBaseUrl(nextProvider, prefs)
            findPreference<EditTextPreference>(LlmPrefs.KEY_BASE_URL)?.text = nextBase
            val nextApiKey = LlmPrefs.getScopedApiKey(prefs, nextProvider, nextBase)
            findPreference<EditTextPreference>(LlmPrefs.KEY_API_KEY)?.text = nextApiKey
            val nextModel = LlmPrefs.syncScopedModelToActivePreferences(prefs, nextProvider, nextBase)
            findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL)?.text = nextModel
            syncSamplingPreferenceState(nextProvider)
            syncRuntimePreferenceState(nextRuntime)
            true
        }
    }

    private fun ensureProviderDefaultsAndScopedApiKey() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val runtime = LlmPrefs.currentRuntime(prefs)
        if (runtime == LlmPrefs.Runtime.LocalOnDevice) {
            val modelPref = findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL)
            if (modelPref?.text.isNullOrBlank()) {
                modelPref?.text = runtime.defaultModel
            }
            syncRuntimePreferenceState(runtime)
            refreshModelStatusPreference(runtime)
            return
        }
        val provider = LlmPrefs.currentProvider(prefs)
        val basePref = findPreference<EditTextPreference>(LlmPrefs.KEY_BASE_URL)
        val currentRawBase = basePref?.text.orEmpty()
        val effectiveBase = currentRawBase.ifBlank { LlmPrefs.providerDefaultBaseUrl(provider, prefs) }
        if (currentRawBase != effectiveBase) {
            basePref?.text = effectiveBase
        }
        val apiKey = LlmPrefs.syncScopedApiKeyToActivePreferences(prefs, provider, effectiveBase)
        findPreference<EditTextPreference>(LlmPrefs.KEY_API_KEY)?.text = apiKey
        val currentModel = findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL)?.text.orEmpty()
        val restoredModel = LlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            provider,
            effectiveBase,
            legacyFallback = currentModel,
        )
        findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL)?.text = restoredModel
        syncSamplingPreferenceState(provider)
        syncRuntimePreferenceState(runtime)
        refreshModelStatusPreference(runtime)
    }

    private fun syncSamplingPreferenceState(provider: LlmPrefs.Provider) {
        val samplePreference = findPreference<DialogSeekBarPreference>(LlmPrefs.KEY_SAMPLE_COUNT) ?: return
        samplePreference.isEnabled = true
    }

    private fun syncRuntimePreferenceState(runtime: LlmPrefs.Runtime) {
        val remoteEnabled = runtime == LlmPrefs.Runtime.Remote
        findPreference<Preference>(LlmPrefs.KEY_PROVIDER)?.isEnabled = true
        listOf(
            LlmPrefs.KEY_BASE_URL,
            LlmPrefs.KEY_API_KEY,
        ).forEach { key ->
            findPreference<Preference>(key)?.isEnabled = remoteEnabled
        }
        findPreference<Preference>(PREF_LOCAL_MODEL_IMPORT)?.isEnabled = !remoteEnabled
        findPreference<Preference>(PREF_MODEL_TEST)?.isEnabled = true
        refreshModelStatusPreference(runtime)
    }

    private fun refreshModelStatusPreference(runtimeOverride: LlmPrefs.Runtime? = null) {
        val ctx = requireContext()
        val runtime = runtimeOverride ?: preferenceManager.sharedPreferences?.let(LlmPrefs::currentRuntime)
            ?: LlmPrefs.Runtime.Remote
        val modelPreference = findPreference<EditTextPreference>(LlmPrefs.KEY_MODEL) ?: return
        modelPreference.isEnabled =
            runtime != LlmPrefs.Runtime.LocalOnDevice || LlmLocalModelManager.currentModel(ctx) != null
        modelPreference.setTitle(R.string.llm_model_status)
        modelPreference.text = modelPreference.text
    }

    private fun showModelGenerationTestDialog() {
        showMultilineTextInputDialog(
            titleRes = R.string.llm_model_test,
            initialText = "今天天气真好",
            onConfirm = ::runModelGenerationTest,
        )
    }

    private fun runModelGenerationTest(beforeCursor: String) {
        val ctx = requireContext()
        val prefs = preferenceManager.sharedPreferences ?: return
        val config = LlmPrefs.read(ctx)
        val personaName = LlmPrefs.currentPersonaDisplayName(ctx, prefs)
        lifecycleScope.withLoadingDialog(ctx) {
            withContext(Dispatchers.IO) {
                runCatching {
                    when (config.runtime) {
                        LlmPrefs.Runtime.LocalOnDevice -> runLocalModelGenerationTest(ctx, config, beforeCursor, personaName)
                        LlmPrefs.Runtime.Remote -> runRemoteModelGenerationTest(config, beforeCursor, personaName)
                    }
                }.onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        showModelGenerationTestResultDialog(result)
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        ctx.toast(it)
                    }
                }
            }
        }
    }

    private fun showModelGenerationTestResultDialog(result: ModelGenerationTestResult) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.llm_local_model_smoke_result)
            .setMessage(buildModelGenerationResultMessage(result))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun performImportModel(uri: android.net.Uri) {
        runLocalModelManagerOperation(
            action = { ctx -> LlmLocalModelManager.importModel(ctx, uri) },
            onSuccess = { ctx, model ->
                ctx.toast(getString(R.string.llm_local_model_import_success, model.displayName))
            },
        )
    }

    private fun showImportModelDialog() {
        val ctx = requireContext()
        val sections = listOf(
            ImportSection(
                titleRes = R.string.llm_local_model_import_recommended_title,
                summaryRes = R.string.llm_local_model_import_recommended_summary,
                actionRes = R.string.llm_local_model_import_recommended_action,
                onClick = ::showRecommendedModelDialog,
            ),
            ImportSection(
                titleRes = R.string.llm_local_model_import_local_title,
                summaryRes = R.string.llm_local_model_import_local_summary,
                actionRes = R.string.llm_local_model_import_local_action,
                onClick = { importModelLauncher.launch("*/*") },
            ),
            ImportSection(
                titleRes = R.string.llm_local_model_import_custom_title,
                summaryRes = R.string.llm_local_model_import_custom_summary,
                actionRes = R.string.llm_local_model_import_custom_action,
                onClick = ::showCustomModelUrlDialog,
            ),
        )
        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.llm_local_model_import)
            .setView(buildImportModelDialogView(ctx, sections) {
                dialog?.dismiss()
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRecommendedModelDialog() {
        val ctx = requireContext()
        val items = recommendedLocalModels.map { it.displayName }.toTypedArray()
        var selectedIndex = 0
        AlertDialog.Builder(ctx)
            .setTitle(R.string.llm_local_model_import_recommended_title)
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val model = recommendedLocalModels[selectedIndex]
                downloadAndInstallModel(model.downloadUrl, model.displayName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomModelUrlDialog() {
        val ctx = requireContext()
        showSingleLineTextInputDialog(
            titleRes = R.string.llm_local_model_url,
            initialText = LlmLocalModelManager.localModelUrl(ctx),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        ) { url ->
            LlmLocalModelManager.setLocalModelUrl(ctx, url)
            downloadAndInstallModel(url, null)
        }
    }

    private fun downloadAndInstallModel(
        url: String,
        displayNameOverride: String?,
    ) {
        val ctx = requireContext()
        if (url.isBlank()) {
            ctx.toast(R.string.llm_local_model_url_required)
            return
        }
        runLocalModelManagerOperation(
            action = { context ->
                LlmLocalModelManager.downloadModel(
                    context = context,
                    url = url,
                    displayNameOverride = displayNameOverride,
                )
            },
            onSuccess = { _, model ->
                ctx.toast(getString(R.string.llm_local_model_download_success, model.displayName))
            },
        )
    }

    private fun showLocalModelManagerDialog() {
        val ctx = requireContext()
        val model = LlmLocalModelManager.currentModel(ctx) ?: return
        val canUpgrade = !model.upgradeSourceRef.isNullOrBlank()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.llm_model_status)
            .setMessage(LlmLocalModelManager.statusSummary(ctx))
            .setPositiveButton(
                if (canUpgrade) {
                    R.string.llm_local_model_manage_upgrade
                } else {
                    R.string.llm_local_model_manage_reimport
                }
            ) { _, _ ->
                if (canUpgrade) {
                    upgradeCurrentModel()
                } else {
                    showImportModelDialog()
                }
            }
            .setNeutralButton(R.string.llm_local_model_manage_reload) { _, _ ->
                reloadCurrentModel()
            }
            .setNegativeButton(R.string.llm_local_model_manage_delete) { _, _ ->
                confirmDeleteCurrentModel(model.displayName)
            }
            .show()
    }

    private fun confirmDeleteCurrentModel(displayName: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.llm_local_model_manage_delete)
            .setMessage(getString(R.string.llm_local_model_delete_confirm, LlmLocalModelManager.presentDisplayName(displayName)))
            .setPositiveButton(R.string.llm_local_model_manage_delete) { _, _ ->
                LlmLocalModelManager.clearModel(ctx)
                refreshModelStatusPreference()
                ctx.toast(R.string.llm_local_model_cleared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reloadCurrentModel() {
        runLocalModelManagerOperation(
            action = LlmLocalModelManager::reloadModel,
            onSuccess = { ctx, _ -> ctx.toast(R.string.llm_local_model_reloaded) },
        )
    }

    private fun upgradeCurrentModel() {
        runLocalModelManagerOperation(
            action = LlmLocalModelManager::upgradeModel,
            onSuccess = { ctx, model ->
                ctx.toast(getString(R.string.llm_local_model_upgrade_success, model.displayName))
            },
        )
    }

    private fun runLocalModelGenerationTest(
        ctx: android.content.Context,
        config: LlmPrefs.Config,
        beforeCursor: String,
        personaName: String,
    ): ModelGenerationTestResult {
        val model = LlmLocalModelManager.currentModel(ctx)
            ?: error(ctx.getString(R.string.llm_local_model_not_configured))
        if (!GenAiLocalLlmRuntime.isAvailable()) {
            error(ctx.getString(R.string.llm_local_model_runtime_unavailable))
        }
        val bundle = LlmLocalResourceManager.prepareRuntimeBundle(ctx, model.file)
        val smoke = GenAiLocalLlmRuntime.smoke(
            LocalLlmPredictionRequest(
                modelPath = bundle.model.absolutePath,
                companionDirectory = bundle.directory.absolutePath,
                beforeCursor = beforeCursor,
                recentCommittedText = "",
                historyText = "",
                maxPredictionCandidates = config.maxPredictionCandidates,
                maxOutputTokens = minOf(config.maxOutputTokens, 128),
                outputMode = LlmOutputMode.Suggestions,
                taskMode = LlmTaskMode.Completion,
                enableThinking = true,
                personaPreset = config.personaPreset,
                customPersona = config.customPersona,
            )
        )
        return ModelGenerationTestResult(
            stage = smoke.stage,
            personaName = personaName,
            inputPrefix = beforeCursor,
            suggestions = smoke.suggestions,
            modelName = model.displayName,
            modelId = model.file.name,
            inputTokens = smoke.inputTokens,
            outputTokens = smoke.outputTokens,
            totalTokens = smoke.totalTokens,
            error = smoke.error,
        )
    }

    private suspend fun runRemoteModelGenerationTest(
        config: LlmPrefs.Config,
        beforeCursor: String,
        personaName: String,
    ): ModelGenerationTestResult {
        if (!config.isUsable) {
            error(getString(R.string.llm_model_test_remote_unavailable))
        }
        val response = LlmClient().predict(
            LlmClient.PredictionRequest(
                config = config,
                beforeCursor = beforeCursor,
                recentCommittedText = "",
                historyText = "",
                outputMode = LlmOutputMode.Suggestions,
                taskMode = LlmTaskMode.Completion,
                enableThinking = false,
            )
        )
        return ModelGenerationTestResult(
            stage = if (response.rawContent.isBlank() && response.suggestions.isEmpty()) "complete_empty" else "complete",
            personaName = personaName,
            inputPrefix = beforeCursor,
            suggestions = response.suggestions,
            modelName = response.modelId.ifBlank { config.model },
            modelId = response.modelId.ifBlank { config.model },
            inputTokens = response.usage?.inputTokens,
            outputTokens = response.usage?.outputTokens,
            totalTokens = response.usage?.totalTokens,
        )
    }

    private fun showMultilineTextInputDialog(
        @StringRes titleRes: Int,
        initialText: String,
        onConfirm: (String) -> Unit,
    ) {
        val ctx = requireContext()
        val editText = createTextInput(
            ctx = ctx,
            initialText = initialText,
            inputType = InputType.TYPE_CLASS_TEXT,
            singleLine = false,
            minLines = 3,
        )
        AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(editText.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSingleLineTextInputDialog(
        @StringRes titleRes: Int,
        initialText: String,
        inputType: Int,
        onConfirm: (String) -> Unit,
    ) {
        val ctx = requireContext()
        val editText = createTextInput(
            ctx = ctx,
            initialText = initialText,
            inputType = inputType,
            singleLine = true,
        )
        AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirm(editText.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createTextInput(
        ctx: android.content.Context,
        initialText: String,
        inputType: Int,
        singleLine: Boolean,
        minLines: Int = 1,
    ): EditText = EditText(ctx).apply {
        this.inputType = inputType
        setSingleLine(singleLine)
        this.minLines = minLines
        setText(initialText)
        setSelection(text.length)
    }

    private fun buildModelGenerationResultMessage(result: ModelGenerationTestResult): String = buildString {
        appendLine(getString(R.string.llm_local_model_smoke_persona_label, result.personaName))
        appendLine()
        appendLine(getString(R.string.llm_local_model_smoke_prompt_label, result.inputPrefix.ifBlank {
            getString(R.string.llm_result_empty_value)
        }))
        appendLine()
        appendLine(getString(R.string.llm_local_model_smoke_stage_label, localizeStage(result.stage)))
        appendLine()
        appendLine(getString(R.string.llm_local_model_smoke_suggestions_label))
        if (result.suggestions.isEmpty()) {
            appendLine(getString(R.string.llm_result_empty_value))
        } else {
            result.suggestions.forEachIndexed { index, suggestion ->
                appendLine("${index + 1}. $suggestion")
            }
        }
        appendLine()
        appendLine(getString(R.string.llm_local_model_smoke_model_label, buildModelIdentity(result)))
        appendLine()
        appendLine(
            getString(
                R.string.llm_local_model_smoke_usage_label,
                formatUsageValue(result.inputTokens),
                formatUsageValue(result.outputTokens),
                formatUsageValue(result.totalTokens),
            )
        )
        if (!result.error.isNullOrBlank()) {
            appendLine()
            appendLine(getString(R.string.llm_local_model_smoke_error_label, result.error))
        }
    }

    private fun buildModelIdentity(result: ModelGenerationTestResult): String {
        val name = result.modelName.trim()
        val id = result.modelId.trim()
        return when {
            name.isBlank() && id.isBlank() -> getString(R.string.llm_result_empty_value)
            name.isNotBlank() && id.isNotBlank() && name != id -> "$name ($id)"
            else -> name.ifBlank { id }
        }
    }

    private fun formatUsageValue(value: Int?): String =
        value?.toString() ?: getString(R.string.llm_result_empty_value)

    private fun localizeStage(stage: String): String {
        val normalized = stage.trim().lowercase()
        return when {
            normalized == "complete" -> getString(R.string.llm_result_stage_complete)
            normalized == "complete_empty" -> getString(R.string.llm_result_stage_complete_empty)
            "timeout" in normalized -> getString(R.string.llm_result_stage_timeout)
            "permission" in normalized || "forbidden" in normalized || "denied" in normalized ->
                getString(R.string.llm_result_stage_permission_denied)
            normalized == "unavailable" || "runtime_unavailable" in normalized ->
                getString(R.string.llm_result_stage_runtime_unavailable)
            normalized == "bundle" || "bundle_missing" in normalized ->
                getString(R.string.llm_result_stage_bundle_missing)
            "auth" in normalized || "unauthorized" in normalized ->
                getString(R.string.llm_result_stage_auth_failed)
            else -> getString(R.string.llm_result_stage_request_failed)
        }
    }

    private fun buildImportModelDialogView(
        ctx: android.content.Context,
        sections: List<ImportSection>,
        dismissDialog: () -> Unit,
    ): ScrollView {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val spacing = (12 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        sections.forEach { section ->
            addImportSection(
                container = container,
                spacing = spacing,
                section = section,
                dismissDialog = dismissDialog,
            )
        }
        return ScrollView(ctx).apply { addView(container) }
    }

    private fun addImportSection(
        container: LinearLayout,
        spacing: Int,
        section: ImportSection,
        dismissDialog: () -> Unit,
    ) {
        container.addView(TextView(requireContext()).apply { setText(section.titleRes) })
        container.addView(TextView(requireContext()).apply {
            setText(section.summaryRes)
            setPadding(0, spacing / 3, 0, spacing / 2)
        })
        container.addView(Button(requireContext()).apply {
            setText(section.actionRes)
            setOnClickListener {
                dismissDialog()
                section.onClick()
            }
        })
        container.addView(TextView(requireContext()).apply { text = "" })
    }

    private fun runLocalModelManagerOperation(
        action: (android.content.Context) -> LlmLocalModelManager.InstalledModel,
        onSuccess: (android.content.Context, LlmLocalModelManager.InstalledModel) -> Unit,
    ) {
        val ctx = requireContext()
        lifecycleScope.withLoadingDialog(ctx) {
            withContext(Dispatchers.IO) {
                runCatching { action(ctx) }
                    .onSuccess { model ->
                        withContext(Dispatchers.Main) {
                            refreshModelStatusPreference()
                            onSuccess(ctx, model)
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) { ctx.toast(it) }
                    }
            }
        }
    }

    private fun textPreference(
        key: String,
        titleRes: Int,
        defaultValue: String,
        summaryProvider: Preference.SummaryProvider<EditTextPreference>? =
            EditTextPreference.SimpleSummaryProvider.getInstance(),
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onChange: ((String) -> Boolean)? = null,
    ) = EditTextPreference(requireContext()).apply {
        this.key = key
        setTitle(titleRes)
        setDialogTitle(titleRes)
        setDefaultValue(defaultValue)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        this.summaryProvider = summaryProvider
        setOnBindEditTextListener { editText ->
            editText.inputType = inputType
            editText.setSingleLine(true)
        }
        if (onChange != null) {
            setOnPreferenceChangeListener { _, newValue ->
                onChange(newValue?.toString().orEmpty())
            }
        }
    }
}
