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
import org.fxboomk.fcitx5.android.input.predict.GenAiLocalLanLlmRuntime
import org.fxboomk.fcitx5.android.input.predict.LanLlmClient
import org.fxboomk.fcitx5.android.input.predict.LanLlmLocalModelManager
import org.fxboomk.fcitx5.android.input.predict.LanLlmLocalResourceManager
import org.fxboomk.fcitx5.android.input.predict.LocalLanLlmPredictionRequest
import org.fxboomk.fcitx5.android.input.predict.LanLlmOutputMode
import org.fxboomk.fcitx5.android.input.predict.LanLlmPrefs
import org.fxboomk.fcitx5.android.input.predict.LanLlmTaskMode
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.common.withLoadingDialog
import org.fxboomk.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fxboomk.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.toast

class LanLlmSettingsFragment : PaddingPreferenceFragment() {
    private data class RecommendedLocalModel(
        val displayName: String,
        val description: String,
        val downloadUrl: String,
    )

    private data class ModelGenerationTestResult(
        val stage: String,
        val prompt: String,
        val rawText: String,
        val suggestions: List<String>,
        val error: String? = null,
    )

    private data class ImportSection(
        @StringRes val titleRes: Int,
        @StringRes val summaryRes: Int,
        @StringRes val actionRes: Int,
        val onClick: () -> Unit,
    )

    companion object {
        private const val PREF_LOCAL_MODEL_IMPORT = "lan_llm_local_model_import_entry"
        private const val PREF_MODEL_TEST = "lan_llm_model_test"

        private val recommendedLocalModels = listOf(
            RecommendedLocalModel(
                displayName = "Qwen3-0.6B-ONNX 移动端 int4",
                description = "已验证可在当前本地运行时加载的推荐模型",
                downloadUrl = "https://huggingface.co/onnx-community/Qwen3-0.6B-ONNX/resolve/main/onnxruntime/cpu_and_mobile/cpu-int4-kld-block-128/model.onnx",
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
            LanLlmPrefs.KEY_BASE_URL -> {
                if (childFragmentManager.findFragmentByTag(LanLlmApiUrlPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LanLlmApiUrlPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LanLlmApiUrlPreferenceDialogFragment::class.java.name)
                return
            }
            LanLlmPrefs.KEY_API_KEY -> {
                if (childFragmentManager.findFragmentByTag(LanLlmApiKeyPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LanLlmApiKeyPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LanLlmApiKeyPreferenceDialogFragment::class.java.name)
                return
            }
            LanLlmPrefs.KEY_MODEL -> {
                val runtime = preferenceManager.sharedPreferences?.let(LanLlmPrefs::currentRuntime)
                    ?: LanLlmPrefs.Runtime.Remote
                if (runtime == LanLlmPrefs.Runtime.LocalOnDevice) {
                    showLocalModelManagerDialog()
                    return
                }
                if (childFragmentManager.findFragmentByTag(LanLlmModelPreferenceDialogFragment::class.java.name) != null) {
                    return
                }
                LanLlmModelPreferenceDialogFragment.newInstance(preference.key)
                    .show(childFragmentManager, LanLlmModelPreferenceDialogFragment::class.java.name)
                return
            }
        }
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferences?.let(LanLlmPrefs::migrateSeekBarBackedPreferences)
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(MySwitchPreference(context).apply {
                key = LanLlmPrefs.KEY_ENABLED
                setTitle(R.string.lan_llm_enable)
                setSummary(R.string.lan_llm_enable_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(MySwitchPreference(context).apply {
                key = LanLlmPrefs.KEY_AUTO_PREDICT_ENABLED
                setTitle(R.string.lan_llm_auto_predict)
                setSummary(R.string.lan_llm_auto_predict_summary)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(providerPreference())
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_BASE_URL,
                titleRes = R.string.lan_llm_api_url,
                defaultValue = "",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val raw = pref.text.orEmpty().trim()
                    if (raw.isNotBlank()) {
                        raw
                    } else {
                        val sharedPrefs = pref.preferenceManager.sharedPreferences
                        val provider = LanLlmPrefs.Provider.from(
                            sharedPrefs?.getString(LanLlmPrefs.KEY_PROVIDER, null)
                        )
                        context.getString(
                            R.string.lan_llm_api_url_default_summary,
                            LanLlmPrefs.providerDefaultBaseUrl(provider, sharedPrefs),
                        )
                    }
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_API_KEY,
                titleRes = R.string.lan_llm_api_key,
                defaultValue = "",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    if (pref.text.isNullOrBlank()) {
                        context.getString(R.string._not_available_)
                    } else {
                        context.getString(R.string.lan_llm_api_key_set)
                    }
                },
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            ))
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_MODEL,
                titleRes = R.string.lan_llm_model_status,
                defaultValue = "qwen",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val sharedPrefs = pref.preferenceManager.sharedPreferences
                    val runtime = sharedPrefs?.let(LanLlmPrefs::currentRuntime) ?: LanLlmPrefs.Runtime.Remote
                    if (runtime == LanLlmPrefs.Runtime.LocalOnDevice) {
                        LanLlmLocalModelManager.statusSummary(context)
                    } else {
                        val modelName = pref.text.orEmpty().ifBlank {
                            context.getString(R.string.lan_llm_model_summary_hint)
                        }
                        val status = if (LanLlmPrefs.read(context).isUsable) {
                            context.getString(R.string.lan_llm_model_status_remote_ready)
                        } else {
                            context.getString(R.string.lan_llm_model_status_remote_unavailable)
                        }
                        context.getString(
                            R.string.lan_llm_model_status_remote_summary,
                            modelName,
                            status,
                        )
                    }
                },
            ))
            addPreference(sampleCountPreference())
            addPreference(maxContextCharsPreference())
            addPreference(maxPredictionCandidatesPreference())
            addPreference(textPreference(
                key = LanLlmPrefs.KEY_MAX_OUTPUT_TOKENS,
                titleRes = R.string.lan_llm_max_output_tokens,
                defaultValue = "512",
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text?.toIntOrNull()
                    if (value == null) {
                        context.getString(R.string.invalid_value)
                    } else {
                        context.getString(R.string.lan_llm_max_output_tokens_summary, value)
                    }
                },
                inputType = InputType.TYPE_CLASS_NUMBER,
                onChange = { raw ->
                    val value = raw.toIntOrNull()
                    if (value == null || value !in 1..16384) {
                        context.toast(R.string.invalid_value)
                        false
                    } else {
                        true
                    }
                },
            ))
            addLocalModelPreferences(this)
        }
        ensureProviderDefaultsAndScopedApiKey()
    }

    private fun addLocalModelPreferences(screen: PreferenceScreen) {
        screen.addPreference(Preference(requireContext()).apply {
            key = PREF_LOCAL_MODEL_IMPORT
            setTitle(R.string.lan_llm_local_model_import)
            setSummary(R.string.lan_llm_local_model_import_merged_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                showImportModelDialog()
                true
            }
        })
        screen.addPreference(Preference(requireContext()).apply {
            key = PREF_MODEL_TEST
            setTitle(R.string.lan_llm_model_test)
            setSummary(R.string.lan_llm_model_test_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnPreferenceClickListener {
                showModelGenerationTestDialog()
                true
            }
        })
    }

    private fun sampleCountPreference() = DialogSeekBarPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_SAMPLE_COUNT
        setTitle(R.string.lan_llm_sample_count)
        setDialogTitle(R.string.lan_llm_sample_count)
        setDefaultValue(4)
        min = 1
        max = 6
        step = 1
        unit = ""
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
    }

    private fun maxContextCharsPreference() = DialogSeekBarPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_MAX_CONTEXT_CHARS
        setTitle(R.string.lan_llm_max_context_chars)
        setDialogTitle(R.string.lan_llm_max_context_chars)
        setDefaultValue(64)
        min = 8
        max = 512
        step = 1
        unit = ""
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
    }

    private fun maxPredictionCandidatesPreference() = DialogSeekBarPreference(requireContext()).apply {
        key = LanLlmPrefs.KEY_MAX_PREDICTION_CANDIDATES
        setTitle(R.string.lan_llm_max_prediction_candidates)
        setDialogTitle(R.string.lan_llm_max_prediction_candidates)
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
        key = LanLlmPrefs.KEY_PROVIDER
        setTitle(R.string.lan_llm_provider)
        setDialogTitle(R.string.lan_llm_provider)
        entries = LanLlmPrefs.Provider.entries.map { context.getString(it.titleRes) }.toTypedArray()
        entryValues = LanLlmPrefs.Provider.entries.map { it.value }.toTypedArray()
        setDefaultValue(LanLlmPrefs.Provider.Custom.value)
        isIconSpaceReserved = false
        isSingleLineTitle = false
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, newValue ->
            val prefs = preferenceManager.sharedPreferences ?: return@setOnPreferenceChangeListener true
            val nextProvider = LanLlmPrefs.Provider.from(newValue?.toString())
            val nextRuntime = LanLlmPrefs.runtimeForProvider(nextProvider)
            prefs.edit().putString(LanLlmPrefs.KEY_RUNTIME, nextRuntime.value).apply()
            val nextBase = LanLlmPrefs.providerDefaultBaseUrl(nextProvider, prefs)
            findPreference<EditTextPreference>(LanLlmPrefs.KEY_BASE_URL)?.text = nextBase
            val nextApiKey = LanLlmPrefs.getScopedApiKey(prefs, nextProvider, nextBase)
            findPreference<EditTextPreference>(LanLlmPrefs.KEY_API_KEY)?.text = nextApiKey
            val nextModel = LanLlmPrefs.syncScopedModelToActivePreferences(prefs, nextProvider, nextBase)
            findPreference<EditTextPreference>(LanLlmPrefs.KEY_MODEL)?.text = nextModel
            syncSamplingPreferenceState(nextProvider)
            syncRuntimePreferenceState(nextRuntime)
            true
        }
    }

    private fun ensureProviderDefaultsAndScopedApiKey() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val runtime = LanLlmPrefs.currentRuntime(prefs)
        if (runtime == LanLlmPrefs.Runtime.LocalOnDevice) {
            val modelPref = findPreference<EditTextPreference>(LanLlmPrefs.KEY_MODEL)
            if (modelPref?.text.isNullOrBlank()) {
                modelPref?.text = runtime.defaultModel
            }
            syncRuntimePreferenceState(runtime)
            refreshModelStatusPreference()
            return
        }
        val provider = LanLlmPrefs.currentProvider(prefs)
        val basePref = findPreference<EditTextPreference>(LanLlmPrefs.KEY_BASE_URL)
        val currentRawBase = basePref?.text.orEmpty()
        val effectiveBase = currentRawBase.ifBlank { LanLlmPrefs.providerDefaultBaseUrl(provider, prefs) }
        if (currentRawBase != effectiveBase) {
            basePref?.text = effectiveBase
        }
        val apiKey = LanLlmPrefs.syncScopedApiKeyToActivePreferences(prefs, provider, effectiveBase)
        findPreference<EditTextPreference>(LanLlmPrefs.KEY_API_KEY)?.text = apiKey
        val currentModel = findPreference<EditTextPreference>(LanLlmPrefs.KEY_MODEL)?.text.orEmpty()
        val restoredModel = LanLlmPrefs.syncScopedModelToActivePreferences(
            prefs,
            provider,
            effectiveBase,
            legacyFallback = currentModel,
        )
        findPreference<EditTextPreference>(LanLlmPrefs.KEY_MODEL)?.text = restoredModel
        syncSamplingPreferenceState(provider)
        syncRuntimePreferenceState(runtime)
        refreshModelStatusPreference()
    }

    private fun syncSamplingPreferenceState(provider: LanLlmPrefs.Provider) {
        val samplePreference = findPreference<DialogSeekBarPreference>(LanLlmPrefs.KEY_SAMPLE_COUNT) ?: return
        val runtime = preferenceManager.sharedPreferences?.let(LanLlmPrefs::currentRuntime)
            ?: LanLlmPrefs.Runtime.Remote
        val isLockedToSingleSample =
            runtime == LanLlmPrefs.Runtime.LocalOnDevice || provider.isVendorProvidedApiService
        if (isLockedToSingleSample && samplePreference.value != 1) {
            samplePreference.setValue(1)
        }
        samplePreference.isEnabled = !isLockedToSingleSample
    }

    private fun syncRuntimePreferenceState(runtime: LanLlmPrefs.Runtime) {
        val remoteEnabled = runtime == LanLlmPrefs.Runtime.Remote
        findPreference<Preference>(LanLlmPrefs.KEY_PROVIDER)?.isEnabled = true
        listOf(
            LanLlmPrefs.KEY_BASE_URL,
            LanLlmPrefs.KEY_API_KEY,
        ).forEach { key ->
            findPreference<Preference>(key)?.isEnabled = remoteEnabled
        }
        findPreference<Preference>(PREF_LOCAL_MODEL_IMPORT)?.isEnabled = !remoteEnabled
        findPreference<Preference>(PREF_MODEL_TEST)?.isEnabled = true
        if (!remoteEnabled) {
            findPreference<DialogSeekBarPreference>(LanLlmPrefs.KEY_SAMPLE_COUNT)?.setValue(1)
        }
        findPreference<DialogSeekBarPreference>(LanLlmPrefs.KEY_SAMPLE_COUNT)?.isEnabled = remoteEnabled
        refreshModelStatusPreference()
    }

    private fun refreshModelStatusPreference() {
        val ctx = requireContext()
        val runtime = preferenceManager.sharedPreferences?.let(LanLlmPrefs::currentRuntime)
            ?: LanLlmPrefs.Runtime.Remote
        val modelPreference = findPreference<EditTextPreference>(LanLlmPrefs.KEY_MODEL) ?: return
        modelPreference.isEnabled =
            runtime != LanLlmPrefs.Runtime.LocalOnDevice || LanLlmLocalModelManager.currentModel(ctx) != null
        modelPreference.setTitle(R.string.lan_llm_model_status)
        modelPreference.text = modelPreference.text
    }

    private fun showModelGenerationTestDialog() {
        showMultilineTextInputDialog(
            titleRes = R.string.lan_llm_model_test,
            initialText = "今天是五一劳动节",
            onConfirm = ::runModelGenerationTest,
        )
    }

    private fun runModelGenerationTest(beforeCursor: String) {
        val ctx = requireContext()
        val config = LanLlmPrefs.read(ctx)
        lifecycleScope.withLoadingDialog(ctx) {
            withContext(Dispatchers.IO) {
                runCatching {
                    when (config.runtime) {
                        LanLlmPrefs.Runtime.LocalOnDevice -> runLocalModelGenerationTest(ctx, config, beforeCursor)
                        LanLlmPrefs.Runtime.Remote -> runRemoteModelGenerationTest(config, beforeCursor)
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
            .setTitle(R.string.lan_llm_local_model_smoke_result)
            .setMessage(buildModelGenerationResultMessage(result))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun performImportModel(uri: android.net.Uri) {
        runLocalModelManagerOperation(
            action = { ctx -> LanLlmLocalModelManager.importModel(ctx, uri) },
            onSuccess = { ctx, model ->
                ctx.toast(getString(R.string.lan_llm_local_model_import_success, model.displayName))
            },
        )
    }

    private fun showImportModelDialog() {
        val ctx = requireContext()
        val sections = listOf(
            ImportSection(
                titleRes = R.string.lan_llm_local_model_import_recommended_title,
                summaryRes = R.string.lan_llm_local_model_import_recommended_summary,
                actionRes = R.string.lan_llm_local_model_import_recommended_action,
                onClick = ::showRecommendedModelDialog,
            ),
            ImportSection(
                titleRes = R.string.lan_llm_local_model_import_local_title,
                summaryRes = R.string.lan_llm_local_model_import_local_summary,
                actionRes = R.string.lan_llm_local_model_import_local_action,
                onClick = { importModelLauncher.launch("*/*") },
            ),
            ImportSection(
                titleRes = R.string.lan_llm_local_model_import_custom_title,
                summaryRes = R.string.lan_llm_local_model_import_custom_summary,
                actionRes = R.string.lan_llm_local_model_import_custom_action,
                onClick = ::showCustomModelUrlDialog,
            ),
        )
        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.lan_llm_local_model_import)
            .setView(buildImportModelDialogView(ctx, sections) {
                dialog?.dismiss()
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRecommendedModelDialog() {
        val ctx = requireContext()
        val items = recommendedLocalModels.map { "${it.displayName}\n${it.description}" }.toTypedArray()
        var selectedIndex = 0
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lan_llm_local_model_import_recommended_title)
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
            titleRes = R.string.lan_llm_local_model_url,
            initialText = LanLlmLocalModelManager.localModelUrl(ctx),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        ) { url ->
            LanLlmLocalModelManager.setLocalModelUrl(ctx, url)
            downloadAndInstallModel(url, null)
        }
    }

    private fun downloadAndInstallModel(
        url: String,
        displayNameOverride: String?,
    ) {
        val ctx = requireContext()
        if (url.isBlank()) {
            ctx.toast(R.string.lan_llm_local_model_url_required)
            return
        }
        runLocalModelManagerOperation(
            action = { context ->
                LanLlmLocalModelManager.downloadModel(
                    context = context,
                    url = url,
                    displayNameOverride = displayNameOverride,
                )
            },
            onSuccess = { _, model ->
                ctx.toast(getString(R.string.lan_llm_local_model_download_success, model.displayName))
            },
        )
    }

    private fun showLocalModelManagerDialog() {
        val ctx = requireContext()
        val model = LanLlmLocalModelManager.currentModel(ctx) ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lan_llm_model_status)
            .setMessage(LanLlmLocalModelManager.statusSummary(ctx))
            .setPositiveButton(R.string.lan_llm_local_model_manage_upgrade) { _, _ ->
                showImportModelDialog()
            }
            .setNeutralButton(R.string.lan_llm_local_model_manage_reload) { _, _ ->
                reloadCurrentModel()
            }
            .setNegativeButton(R.string.lan_llm_local_model_manage_delete) { _, _ ->
                confirmDeleteCurrentModel(model.displayName)
            }
            .show()
    }

    private fun confirmDeleteCurrentModel(displayName: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.lan_llm_local_model_manage_delete)
            .setMessage(getString(R.string.lan_llm_local_model_delete_confirm, LanLlmLocalModelManager.presentDisplayName(displayName)))
            .setPositiveButton(R.string.lan_llm_local_model_manage_delete) { _, _ ->
                LanLlmLocalModelManager.clearModel(ctx)
                refreshModelStatusPreference()
                ctx.toast(R.string.lan_llm_local_model_cleared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reloadCurrentModel() {
        runLocalModelManagerOperation(
            action = LanLlmLocalModelManager::reloadModel,
            onSuccess = { ctx, _ -> ctx.toast(R.string.lan_llm_local_model_reloaded) },
        )
    }

    private fun runLocalModelGenerationTest(
        ctx: android.content.Context,
        config: LanLlmPrefs.Config,
        beforeCursor: String,
    ): ModelGenerationTestResult {
        val model = LanLlmLocalModelManager.currentModel(ctx)
            ?: error(ctx.getString(R.string.lan_llm_local_model_not_configured))
        if (!GenAiLocalLanLlmRuntime.isAvailable()) {
            error(ctx.getString(R.string.lan_llm_local_model_runtime_unavailable))
        }
        val bundle = LanLlmLocalResourceManager.prepareRuntimeBundle(ctx, model.file)
        val smoke = GenAiLocalLanLlmRuntime.smoke(
            LocalLanLlmPredictionRequest(
                modelPath = bundle.model.absolutePath,
                companionDirectory = bundle.directory.absolutePath,
                beforeCursor = beforeCursor,
                recentCommittedText = "",
                historyText = "",
                maxPredictionCandidates = config.maxPredictionCandidates,
                maxOutputTokens = minOf(config.maxOutputTokens, 128),
                outputMode = LanLlmOutputMode.Suggestions,
                taskMode = LanLlmTaskMode.Completion,
                enableThinking = true,
            )
        )
        return ModelGenerationTestResult(
            stage = smoke.stage,
            prompt = smoke.prompt,
            rawText = smoke.rawText,
            suggestions = smoke.suggestions,
            error = smoke.error,
        )
    }

    private suspend fun runRemoteModelGenerationTest(
        config: LanLlmPrefs.Config,
        beforeCursor: String,
    ): ModelGenerationTestResult {
        if (!config.isUsable) {
            error(getString(R.string.lan_llm_model_test_remote_unavailable))
        }
        val response = LanLlmClient().predict(
            LanLlmClient.PredictionRequest(
                config = config,
                beforeCursor = beforeCursor,
                recentCommittedText = "",
                historyText = "",
                outputMode = LanLlmOutputMode.Suggestions,
                taskMode = LanLlmTaskMode.Completion,
                enableThinking = false,
            )
        )
        return ModelGenerationTestResult(
            stage = if (response.rawContent.isBlank() && response.suggestions.isEmpty()) "complete_empty" else "complete",
            prompt = beforeCursor,
            rawText = response.rawContent,
            suggestions = response.suggestions,
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
        appendLine(getString(R.string.lan_llm_local_model_smoke_stage_label, result.stage))
        if (!result.error.isNullOrBlank()) {
            appendLine()
            appendLine(getString(R.string.lan_llm_local_model_smoke_error_label))
            appendLine(result.error)
            appendLine()
        } else {
            appendLine()
        }
        appendLine(getString(R.string.lan_llm_local_model_smoke_prompt_label))
        appendLine(result.prompt.ifBlank { "(empty)" })
        appendLine()
        appendLine(getString(R.string.lan_llm_local_model_smoke_raw_label))
        appendLine(result.rawText.ifBlank { "(empty)" })
        appendLine()
        appendLine(getString(R.string.lan_llm_local_model_smoke_parsed_label))
        if (result.suggestions.isEmpty()) {
            append("(empty)")
        } else {
            result.suggestions.forEachIndexed { index, suggestion ->
                appendLine("${index + 1}. $suggestion")
            }
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
        action: (android.content.Context) -> LanLlmLocalModelManager.InstalledModel,
        onSuccess: (android.content.Context, LanLlmLocalModelManager.InstalledModel) -> Unit,
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
