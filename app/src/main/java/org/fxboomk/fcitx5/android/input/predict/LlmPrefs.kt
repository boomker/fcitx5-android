package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.fxboomk.fcitx5.android.R

object LlmPrefs {
    const val KEY_RUNTIME = "lan_llm_runtime"
    const val KEY_LOCAL_MODEL_URL = "lan_llm_local_model_url"
    const val KEY_BACKEND = "lan_llm_backend"
    const val KEY_CHAT_API_ENABLED = "lan_llm_chat_api_enabled"
    const val KEY_ENABLED = "lan_llm_enabled"
    const val KEY_AUTO_PREDICT_ENABLED = "lan_llm_auto_predict_enabled"
    const val KEY_PROVIDER = "lan_llm_provider"
    const val KEY_BASE_URL = "lan_llm_base_url"
    const val KEY_MODEL = "lan_llm_model"
    const val KEY_API_KEY = "lan_llm_api_key"
    private const val KEY_CUSTOM_DEFAULT_BASE_URL = "lan_llm_custom_default_base_url"
    private const val KEY_CUSTOM_DEFAULT_API_KEY = "lan_llm_custom_default_api_key"
    private const val KEY_MODEL_SCOPE_PREFIX = "lan_llm_model_scope_"
    private const val KEY_API_KEY_SCOPE_PREFIX = "lan_llm_api_key_scope_"
    const val KEY_SAMPLE_COUNT = "lan_llm_sample_count"
    const val KEY_MAX_OUTPUT_TOKENS = "lan_llm_max_output_tokens"
    const val KEY_MAX_PREDICTION_CANDIDATES = "lan_llm_max_prediction_candidates"
    const val KEY_MAX_CONTEXT_CHARS = "lan_llm_max_context_chars"
    const val KEY_SPACE_COMMIT_PREDICTION = "lan_llm_space_commit_prediction"
    const val KEY_PREDICTION_DISPLAY_MODE = "lan_llm_prediction_display_mode"
    const val KEY_PERSONA_PRESET = "lan_llm_persona_preset"
    const val KEY_CUSTOM_PERSONA = "lan_llm_custom_persona"
    const val KEY_CUSTOM_PERSONA_NAMES = "lan_llm_custom_persona_names"
    private const val KEY_REMEMBERED_TASK_MODE = "lan_llm_remembered_task_mode"
    private const val KEY_REMEMBERED_LONG_FORM_ENABLED = "lan_llm_remembered_long_form_enabled"
    private const val KEY_REMEMBERED_THINKING_ENABLED = "lan_llm_remembered_thinking_enabled"
    private const val KEY_PERSONA_DETAIL_PREFIX = "lan_llm_persona_detail_"

    enum class Runtime(
        val value: String,
        val defaultModel: String,
    ) {
        Remote("remote", DEFAULT_MODEL),
        LocalOnDevice("local_on_device", DEFAULT_LOCAL_MODEL);

        companion object {
            fun from(value: String?): Runtime =
                entries.firstOrNull { it.value == value } ?: Remote
        }
    }

    enum class Backend(val value: String) {
        ChatCompletions("chat"),
        Completion("completion");

        companion object {
            fun from(value: String?): Backend = entries.firstOrNull { it.value == value } ?: ChatCompletions
        }
    }

    enum class CompatApi {
        OpenAI,
        Anthropic,
    }

    enum class PredictionDisplayMode(
        val value: String,
        val titleRes: Int,
    ) {
        FloatingWindow("floating_window", R.string.llm_prediction_display_mode_floating_window),
        CandidateBar("candidate_bar", R.string.llm_prediction_display_mode_candidate_bar_overlay),
        CandidateExpanded("candidate_expanded", R.string.llm_prediction_display_mode_candidate_expanded);

        companion object {
            fun from(value: String?): PredictionDisplayMode =
                entries.firstOrNull { it.value == value } ?: FloatingWindow
        }
    }

    enum class Provider(
        val value: String,
        val titleRes: Int,
        val defaultBaseUrl: String?,
        val compatApi: CompatApi?,
        val defaultModel: String? = null,
    ) {
        LocalAI("local_ai", R.string.llm_provider_local_ai, null, null, DEFAULT_LOCAL_MODEL),
        Custom("custom", R.string.llm_provider_custom, null, null),
        OpenAI("openai", R.string.llm_provider_openai, "https://api.openai.com/v1", CompatApi.OpenAI),
        Anthropic("anthropic", R.string.llm_provider_anthropic, "https://api.anthropic.com/v1", CompatApi.Anthropic),
        Gemini("gemini", R.string.llm_provider_gemini, "https://generativelanguage.googleapis.com/v1beta/openai", CompatApi.OpenAI),
        DeepSeek("deepseek", R.string.llm_provider_deepseek, "https://api.deepseek.com", CompatApi.OpenAI),
        Zhipu("zhipu", R.string.llm_provider_zhipu, "https://open.bigmodel.cn/api/paas/v4", CompatApi.OpenAI),
        MiniMax(
            "minimax",
            R.string.llm_provider_minimax,
            "https://api.minimaxi.com/anthropic",
            CompatApi.Anthropic,
            "MiniMax-M2.7",
        );

        val isVendorProvidedApiService: Boolean
            get() = this != Custom && this != LocalAI

        companion object {
            fun from(value: String?): Provider = entries.firstOrNull { it.value == value } ?: Custom
        }
    }

    enum class PersonaPreset(
        val value: String,
        val titleRes: Int,
        val descriptionRes: Int,
        val zhPrompt: String,
        val enPrompt: String,
    ) {
        Custom(
            value = "custom",
            titleRes = R.string.llm_persona_custom_short,
            descriptionRes = R.string.llm_persona_custom_summary,
            zhPrompt = "",
            enPrompt = "",
        ),
        SocialStar(
            value = "social_star",
            titleRes = R.string.llm_persona_social_star,
            descriptionRes = R.string.llm_persona_social_star_description,
            zhPrompt = "情商在线，幽默风趣的社交达人。",
            enPrompt = "Be a socially savvy, emotionally intelligent, witty social butterfly.",
        ),
        WorkplaceElite(
            value = "workplace_elite",
            titleRes = R.string.llm_persona_workplace_elite,
            descriptionRes = R.string.llm_persona_workplace_elite_description,
            zhPrompt = "大厂满嘴职场话术的产品经理。",
            enPrompt = "Sound like a product manager from a big tech company using polished corporate workplace phrasing.",
        );

        companion object {
            fun from(value: String?): PersonaPreset =
                entries.firstOrNull { it.value == value } ?: Custom
        }
    }

    data class PersonaOption(
        val value: String,
        val title: String,
        val description: String,
        val preset: PersonaPreset? = null,
    ) {
        val isBuiltIn: Boolean
            get() = preset != null
    }

    data class Overrides(
        val runtime: Runtime? = null,
        val provider: Provider? = null,
        val baseUrl: String? = null,
        val model: String? = null,
        val apiKey: String? = null,
        val backend: Backend? = null,
    )

    internal data class RememberedUiMode(
        val taskMode: LlmTaskMode = LlmTaskMode.Completion,
        val longFormEnabled: Boolean = false,
        val thinkingEnabled: Boolean = false,
    )

    private const val DEFAULT_BASE_URL = "http://192.168.1.1:8000"
    private const val DEFAULT_CUSTOM_PORT = 8000
    private const val DEFAULT_MODEL = "qwen"
    private const val DEFAULT_LOCAL_MODEL = "qwen3-0.6b-onnx-local"
    private const val DEFAULT_DEBOUNCE_MS = 200
    private const val DEFAULT_SAMPLE_COUNT = 4
    private const val DEFAULT_MAX_OUTPUT_TOKENS = 512
    private const val DEFAULT_MAX_PREDICTION_CANDIDATES = 4
    private const val DEFAULT_MAX_CONTEXT_CHARS = 64

    data class Config(
        val enabled: Boolean,
        val autoPredictEnabled: Boolean = false,
        val runtime: Runtime = Runtime.Remote,
        val backend: Backend,
        val provider: Provider = Provider.Custom,
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val debounceMs: Long,
        val sampleCount: Int,
        val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
        val maxPredictionCandidates: Int = DEFAULT_MAX_PREDICTION_CANDIDATES,
        val maxContextChars: Int,
        val preferLastCommit: Boolean,
        val spaceCommitPrediction: Boolean = false,
        val predictionDisplayMode: PredictionDisplayMode = PredictionDisplayMode.FloatingWindow,
        val personaPreset: PersonaPreset = PersonaPreset.Custom,
        val personaName: String = "",
        val customPersona: String = "",
    ) {
        val isLocalOnDevice: Boolean
            get() = runtime == Runtime.LocalOnDevice

        val isUsable: Boolean
            get() = enabled && model.isNotBlank() && (isLocalOnDevice || resolvedBaseUrl.isNotBlank())

        private val inferredCompatApi: CompatApi
            get() = if (
                baseUrl.endsWith("/messages") ||
                baseUrl.contains("anthropic", ignoreCase = true) ||
                model.startsWith("claude", ignoreCase = true) ||
                apiKey.startsWith("sk-ant-", ignoreCase = true)
            ) {
                CompatApi.Anthropic
            } else {
                CompatApi.OpenAI
            }

        val compatApi: CompatApi
            get() = provider.compatApi ?: inferredCompatApi

        val resolvedBaseUrl: String
            get() = normalizeBaseUrl(baseUrl.ifBlank { provider.defaultBaseUrl.orEmpty() })

        val chatEndpoint: String
            get() = chatCompatEndpoints.firstOrNull().orEmpty()

        val messagesEndpoint: String
            get() = LlmProviderProfile.messagesEndpoint(this)

        val modelsEndpoint: String
            get() = modelsCompatEndpoints.firstOrNull().orEmpty()

        val modelsCompatEndpoints: List<String>
            get() = LlmProviderProfile.modelsCompatEndpoints(this)

        val chatCompatEndpoints: List<String>
            get() = LlmProviderProfile.chatCompatEndpoints(this)

        val completionCompatEndpoints: List<String>
            get() = chatCompatEndpoints

        val fetchWindowChars: Int
            get() = (maxContextChars * 3).coerceIn(96, 512)
    }

    fun read(context: Context, overrides: Overrides = Overrides()): Config =
        read(PreferenceManager.getDefaultSharedPreferences(context), overrides)

    fun read(prefs: SharedPreferences, overrides: Overrides = Overrides()): Config {
        val debounceMs = DEFAULT_DEBOUNCE_MS.toLong()
        val provider = overrides.provider ?: Provider.from(prefs.getString(KEY_PROVIDER, Provider.Custom.value))
        val runtime = overrides.runtime ?: runtimeForProvider(
            provider = provider,
            storedRuntime = prefs.getString(KEY_RUNTIME, Runtime.Remote.value),
        )
        val chatApiEnabled = if (prefs.contains(KEY_CHAT_API_ENABLED)) {
            prefs.getBoolean(KEY_CHAT_API_ENABLED, false)
        } else {
            prefs.getString(KEY_BACKEND, Backend.Completion.value) == Backend.ChatCompletions.value
        }
        val sampleCount = readBoundedIntPreference(prefs, KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT, 1..6)
        val maxOutputTokens = readBoundedIntPreference(
            prefs,
            KEY_MAX_OUTPUT_TOKENS,
            DEFAULT_MAX_OUTPUT_TOKENS,
            1..16384,
        )
        val maxPredictionCandidates = readBoundedIntPreference(
            prefs,
            KEY_MAX_PREDICTION_CANDIDATES,
            DEFAULT_MAX_PREDICTION_CANDIDATES,
            1..8,
        )
        val maxContextChars = readBoundedIntPreference(
            prefs,
            KEY_MAX_CONTEXT_CHARS,
            DEFAULT_MAX_CONTEXT_CHARS,
            8..512,
        )
        val baseUrl = overrides.baseUrl ?: prefs.getString(
            KEY_BASE_URL,
            providerDefaultBaseUrl(provider, prefs),
        ).orEmpty()
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            autoPredictEnabled = prefs.getBoolean(KEY_AUTO_PREDICT_ENABLED, false),
            runtime = runtime,
            backend = overrides.backend ?: if (chatApiEnabled) Backend.ChatCompletions else Backend.Completion,
            provider = provider,
            baseUrl = normalizeBaseUrl(baseUrl),
            model = (overrides.model ?: getScopedModel(prefs, provider, baseUrl).ifBlank {
                prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            }).trim().ifBlank {
                providerDefaultModel(provider).ifBlank { runtime.defaultModel }
            },
            apiKey = (overrides.apiKey ?: getScopedApiKey(prefs, provider, baseUrl)).trim(),
            debounceMs = debounceMs,
            sampleCount = sampleCount,
            maxOutputTokens = maxOutputTokens,
            maxPredictionCandidates = maxPredictionCandidates,
            maxContextChars = maxContextChars,
            preferLastCommit = true,
            spaceCommitPrediction = prefs.getBoolean(KEY_SPACE_COMMIT_PREDICTION, false),
            predictionDisplayMode = PredictionDisplayMode.from(
                prefs.getString(KEY_PREDICTION_DISPLAY_MODE, PredictionDisplayMode.FloatingWindow.value)
            ),
            personaPreset = PersonaPreset.from(prefs.getString(KEY_PERSONA_PRESET, PersonaPreset.Custom.value)),
            personaName = currentPersonaName(prefs),
            customPersona = readPersonaDetail(
                prefs = prefs,
                personaValue = prefs.getString(KEY_PERSONA_PRESET, PersonaPreset.Custom.value).orEmpty(),
            ),
        )
    }

    fun builtInPersonaOptions(context: Context): List<PersonaOption> = PersonaPreset.entries.map { preset ->
        PersonaOption(
            value = preset.value,
            title = context.getString(preset.titleRes),
            description = context.getString(preset.descriptionRes),
            preset = preset,
        )
    }

    fun currentPersonaName(prefs: SharedPreferences): String {
        val value = prefs.getString(KEY_PERSONA_PRESET, PersonaPreset.Custom.value).orEmpty()
        return PersonaPreset.entries.firstOrNull { it.value == value }?.let {
            ""
        } ?: value
    }

    fun currentPersonaDisplayName(
        context: Context,
        prefs: SharedPreferences,
    ): String {
        val value = prefs.getString(KEY_PERSONA_PRESET, PersonaPreset.Custom.value).orEmpty()
        val preset = PersonaPreset.entries.firstOrNull { it.value == value }
        return if (preset != null) {
            context.getString(preset.titleRes)
        } else {
            value.ifBlank { context.getString(PersonaPreset.Custom.titleRes) }
        }
    }

    fun readCustomPersonaNames(prefs: SharedPreferences): List<String> {
        return prefs.getStringSet(KEY_CUSTOM_PERSONA_NAMES, emptySet())
            ?.map(String::trim)
            ?.filter { it.isNotBlank() }
            ?.sorted()
            .orEmpty()
    }

    fun writeCustomPersonaNames(
        prefs: SharedPreferences,
        names: List<String>,
    ) {
        prefs.edit().putStringSet(
            KEY_CUSTOM_PERSONA_NAMES,
            names.map(String::trim).filter { it.isNotBlank() }.toSet(),
        ).apply()
    }

    fun readPersonaDetail(
        prefs: SharedPreferences,
        personaValue: String,
    ): String {
        val scoped = prefs.getString(personaDetailKey(personaValue), null)
            ?.trim()
            .orEmpty()
        if (scoped.isNotBlank()) return scoped
        return prefs.getString(KEY_CUSTOM_PERSONA, "")
            ?.trim()
            .orEmpty()
            .takeIf { personaValue == PersonaPreset.Custom.value }
            .orEmpty()
    }

    fun writePersonaDetail(
        prefs: SharedPreferences,
        personaValue: String,
        detail: String,
    ) {
        prefs.edit().putString(personaDetailKey(personaValue), detail.trim()).apply()
    }

    private fun personaDetailKey(personaValue: String): String {
        val safeValue = URLEncoder.encode(personaValue, StandardCharsets.UTF_8.toString())
        return "$KEY_PERSONA_DETAIL_PREFIX$safeValue"
    }

    fun migrateSeekBarBackedPreferences(prefs: SharedPreferences) {
        migrateIntPreference(prefs, KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT, 1..6)
        migrateIntPreference(
            prefs,
            KEY_MAX_PREDICTION_CANDIDATES,
            DEFAULT_MAX_PREDICTION_CANDIDATES,
            1..8,
        )
        migrateIntPreference(
            prefs,
            KEY_MAX_CONTEXT_CHARS,
            DEFAULT_MAX_CONTEXT_CHARS,
            8..512,
        )
    }

    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    fun customDefaultBaseUrl(prefs: SharedPreferences? = null): String {
        val persisted = prefs?.getString(KEY_CUSTOM_DEFAULT_BASE_URL, null)
            ?.orEmpty()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return normalizeBaseUrl(persisted ?: generatedCustomDefaultBaseUrl())
    }

    fun providerDefaultBaseUrl(
        provider: Provider,
        prefs: SharedPreferences? = null,
    ): String = provider.defaultBaseUrl ?: customDefaultBaseUrl(prefs)

    fun providerDefaultModel(provider: Provider): String =
        provider.defaultModel.orEmpty()

    fun getScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        return prefs.getString(scopedApiKeyKey(prefs, provider, baseUrl), "").orEmpty().ifBlank {
            if (provider == Provider.Custom) {
                prefs.getString(KEY_CUSTOM_DEFAULT_API_KEY, "").orEmpty().trim()
            } else {
                ""
            }
        }.ifBlank {
            prefs.getString(KEY_API_KEY, "").orEmpty().trim()
        }
    }

    fun getScopedModel(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        return prefs.getString(scopedModelKey(prefs, provider, baseUrl), "").orEmpty().trim()
    }

    fun persistScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        apiKey: String,
    ) {
        val normalizedApiKey = apiKey.trim()
        val editor = prefs.edit()
        if (normalizedApiKey.isBlank()) {
            editor.remove(scopedApiKeyKey(prefs, provider, baseUrl))
        } else {
            editor.putString(scopedApiKeyKey(prefs, provider, baseUrl), normalizedApiKey)
        }
        if (provider == Provider.Custom) {
            editor.putString(KEY_CUSTOM_DEFAULT_API_KEY, normalizedApiKey)
        }
        editor.putString(KEY_API_KEY, normalizedApiKey).apply()
    }

    fun persistScopedModel(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        model: String,
    ) {
        val normalizedModel = model.trim()
        val editor = prefs.edit()
        if (normalizedModel.isBlank()) {
            editor.remove(scopedModelKey(prefs, provider, baseUrl))
        } else {
            editor.putString(scopedModelKey(prefs, provider, baseUrl), normalizedModel)
        }
        editor.putString(KEY_MODEL, normalizedModel).apply()
    }

    fun persistCustomDefaultBaseUrl(
        prefs: SharedPreferences,
        baseUrl: String,
    ) {
        prefs.edit()
            .putString(KEY_CUSTOM_DEFAULT_BASE_URL, normalizeBaseUrl(baseUrl))
            .apply()
    }

    fun syncScopedApiKeyToActivePreferences(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        val apiKey = getScopedApiKey(prefs, provider, baseUrl)
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
        return apiKey
    }

    fun syncScopedModelToActivePreferences(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        legacyFallback: String = "",
    ): String {
        val restoredModel = getScopedModel(prefs, provider, baseUrl)
            .ifBlank { legacyFallback.trim() }
            .ifBlank { providerDefaultModel(provider) }
        val editor = prefs.edit()
        if (restoredModel.isBlank()) {
            editor.remove(scopedModelKey(prefs, provider, baseUrl))
        } else {
            editor.putString(scopedModelKey(prefs, provider, baseUrl), restoredModel)
        }
        editor.putString(KEY_MODEL, restoredModel).apply()
        return restoredModel
    }

    fun currentProvider(prefs: SharedPreferences): Provider =
        Provider.from(prefs.getString(KEY_PROVIDER, Provider.Custom.value))

    fun currentRuntime(prefs: SharedPreferences): Runtime =
        runtimeForProvider(
            provider = currentProvider(prefs),
            storedRuntime = prefs.getString(KEY_RUNTIME, Runtime.Remote.value),
        )

    fun currentPredictionDisplayMode(prefs: SharedPreferences): PredictionDisplayMode =
        PredictionDisplayMode.from(
            prefs.getString(KEY_PREDICTION_DISPLAY_MODE, PredictionDisplayMode.FloatingWindow.value)
        )

    internal fun readRememberedUiMode(
        prefs: SharedPreferences,
        defaultThinkingEnabled: Boolean = false,
    ): RememberedUiMode {
        val taskMode = when (prefs.getString(KEY_REMEMBERED_TASK_MODE, LlmTaskMode.Completion.name)) {
            LlmTaskMode.QuestionAnswer.name -> LlmTaskMode.QuestionAnswer
            LlmTaskMode.Translate.name -> LlmTaskMode.Translate
            else -> LlmTaskMode.Completion
        }
        val longFormEnabled = prefs.getBoolean(KEY_REMEMBERED_LONG_FORM_ENABLED, false) &&
            taskMode != LlmTaskMode.Translate
        val thinkingEnabled = if (prefs.contains(KEY_REMEMBERED_THINKING_ENABLED)) {
            prefs.getBoolean(KEY_REMEMBERED_THINKING_ENABLED, defaultThinkingEnabled)
        } else {
            defaultThinkingEnabled
        }
        return RememberedUiMode(
            taskMode = taskMode,
            longFormEnabled = longFormEnabled,
            thinkingEnabled = thinkingEnabled,
        )
    }

    internal fun persistRememberedUiMode(
        prefs: SharedPreferences,
        taskMode: LlmTaskMode,
        longFormEnabled: Boolean,
        thinkingEnabled: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_REMEMBERED_TASK_MODE, taskMode.name)
            .putBoolean(
                KEY_REMEMBERED_LONG_FORM_ENABLED,
                longFormEnabled && taskMode != LlmTaskMode.Translate,
            )
            .putBoolean(KEY_REMEMBERED_THINKING_ENABLED, thinkingEnabled)
            .apply()
    }

    fun runtimeForProvider(
        provider: Provider,
        storedRuntime: String? = null,
    ): Runtime = when (provider) {
        Provider.LocalAI -> Runtime.LocalOnDevice
        else -> Runtime.from(storedRuntime).takeIf { it != Runtime.LocalOnDevice } ?: Runtime.Remote
    }

    fun currentBaseUrl(prefs: SharedPreferences, provider: Provider = currentProvider(prefs)): String =
        normalizeBaseUrl(
            prefs.getString(KEY_BASE_URL, providerDefaultBaseUrl(provider, prefs)).orEmpty()
                .ifBlank { providerDefaultBaseUrl(provider, prefs) }
        )

    private fun apiKeyScope(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String = "${provider.value}|${
        normalizeBaseUrl(baseUrl.ifBlank { providerDefaultBaseUrl(provider, prefs) })
    }"

    private fun scopedModelKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String =
        KEY_MODEL_SCOPE_PREFIX + URLEncoder.encode(
            apiKeyScope(prefs, provider, baseUrl),
            StandardCharsets.UTF_8.name(),
        )

    private fun scopedApiKeyKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String =
        KEY_API_KEY_SCOPE_PREFIX + URLEncoder.encode(
            apiKeyScope(prefs, provider, baseUrl),
            StandardCharsets.UTF_8.name(),
        )

    private fun generatedCustomDefaultBaseUrl(): String {
        val host = detectLanIpv4Address() ?: "192.168.1.1"
        return "http://$host:$DEFAULT_CUSTOM_PORT"
    }

    private fun detectLanIpv4Address(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { !it.isLoopback && it.isUp }
                .sortedBy { preferredInterfaceScore(it.name) }
            interfaces.asSequence()
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun preferredInterfaceScore(name: String?): Int = when {
        name.isNullOrBlank() -> Int.MAX_VALUE
        name.startsWith("wlan", ignoreCase = true) -> 0
        name.startsWith("wifi", ignoreCase = true) -> 1
        name.startsWith("eth", ignoreCase = true) -> 2
        else -> 10
    }

    private fun migrateIntPreference(
        prefs: SharedPreferences,
        key: String,
        defaultValue: Int,
        validRange: IntRange,
    ) {
        if (prefs.all[key] is Int) return
        val value = readBoundedIntPreference(prefs, key, defaultValue, validRange)
        prefs.edit().putInt(key, value).apply()
    }

    private fun readBoundedIntPreference(
        prefs: SharedPreferences,
        key: String,
        defaultValue: Int,
        validRange: IntRange,
    ): Int {
        val rawValue = prefs.all[key]
        val parsed = when (rawValue) {
            is Int -> rawValue
            is Long -> rawValue.toInt()
            is Float -> rawValue.toInt()
            is Double -> rawValue.toInt()
            is String -> rawValue.toIntOrNull()
            else -> null
        } ?: defaultValue
        return parsed.coerceIn(validRange.first, validRange.last)
    }

}
