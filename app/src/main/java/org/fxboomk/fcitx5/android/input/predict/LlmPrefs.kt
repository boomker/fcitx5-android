package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.fxboomk.fcitx5.android.R

object LlmPrefs {
    private const val KEY_PREFIX = "llm_"
    private const val LEGACY_KEY_PREFIX = "lan_llm_"

    const val KEY_RUNTIME = KEY_PREFIX + "runtime"
    const val KEY_LOCAL_MODEL_URL = KEY_PREFIX + "local_model_url"
    const val KEY_BACKEND = KEY_PREFIX + "backend"
    const val KEY_CHAT_API_ENABLED = KEY_PREFIX + "chat_api_enabled"
    const val KEY_ENABLED = KEY_PREFIX + "enabled"
    const val KEY_AUTO_PREDICT_ENABLED = KEY_PREFIX + "auto_predict_enabled"
    const val KEY_PROVIDER = KEY_PREFIX + "provider"
    const val KEY_BASE_URL = KEY_PREFIX + "base_url"
    const val KEY_ENABLED_BASE_URLS = KEY_PREFIX + "enabled_base_urls"
    const val KEY_SHARE_PRIMARY_API_KEY = KEY_PREFIX + "share_primary_api_key"
    const val KEY_MODEL = KEY_PREFIX + "model"
    const val KEY_API_KEY = KEY_PREFIX + "api_key"
    private const val KEY_CUSTOM_DEFAULT_BASE_URL = KEY_PREFIX + "custom_default_base_url"
    private const val KEY_CUSTOM_DEFAULT_API_KEY = KEY_PREFIX + "custom_default_api_key"
    private const val KEY_MODEL_SCOPE_PREFIX = KEY_PREFIX + "model_scope_"
    private const val KEY_API_KEY_SCOPE_PREFIX = KEY_PREFIX + "api_key_scope_"
    private const val KEY_BASE_URLS_SCOPE_PREFIX = KEY_PREFIX + "base_urls_scope_"
    private const val KEY_ENABLED_SCOPE_PREFIX = KEY_PREFIX + "enabled_scope_"
    const val KEY_SAMPLE_COUNT = KEY_PREFIX + "sample_count"
    const val KEY_MAX_OUTPUT_TOKENS = KEY_PREFIX + "max_output_tokens"
    const val KEY_MAX_PREDICTION_CANDIDATES = KEY_PREFIX + "max_prediction_candidates"
    const val KEY_MAX_CONTEXT_CHARS = KEY_PREFIX + "max_context_chars"
    const val KEY_SPACE_COMMIT_PREDICTION = KEY_PREFIX + "space_commit_prediction"
    const val KEY_PREDICTION_DISPLAY_MODE = KEY_PREFIX + "prediction_display_mode"
    const val KEY_PERSONA_PRESET = KEY_PREFIX + "persona_preset"
    const val KEY_CUSTOM_PERSONA = KEY_PREFIX + "custom_persona"
    const val KEY_CUSTOM_PERSONA_NAMES = KEY_PREFIX + "custom_persona_names"
    private const val KEY_REMEMBERED_TASK_MODE = KEY_PREFIX + "remembered_task_mode"
    private const val KEY_REMEMBERED_LONG_FORM_ENABLED = KEY_PREFIX + "remembered_long_form_enabled"
    private const val KEY_REMEMBERED_THINKING_ENABLED = KEY_PREFIX + "remembered_thinking_enabled"
    private const val KEY_PERSONA_DETAIL_PREFIX = KEY_PREFIX + "persona_detail_"

    private val exactPreferenceKeys = listOf(
        KEY_RUNTIME,
        KEY_LOCAL_MODEL_URL,
        KEY_BACKEND,
        KEY_CHAT_API_ENABLED,
        KEY_ENABLED,
        KEY_AUTO_PREDICT_ENABLED,
        KEY_PROVIDER,
        KEY_BASE_URL,
        KEY_ENABLED_BASE_URLS,
        KEY_SHARE_PRIMARY_API_KEY,
        KEY_MODEL,
        KEY_API_KEY,
        KEY_CUSTOM_DEFAULT_BASE_URL,
        KEY_CUSTOM_DEFAULT_API_KEY,
        KEY_SAMPLE_COUNT,
        KEY_MAX_OUTPUT_TOKENS,
        KEY_MAX_PREDICTION_CANDIDATES,
        KEY_MAX_CONTEXT_CHARS,
        KEY_SPACE_COMMIT_PREDICTION,
        KEY_PREDICTION_DISPLAY_MODE,
        KEY_PERSONA_PRESET,
        KEY_CUSTOM_PERSONA,
        KEY_CUSTOM_PERSONA_NAMES,
        KEY_REMEMBERED_TASK_MODE,
        KEY_REMEMBERED_LONG_FORM_ENABLED,
        KEY_REMEMBERED_THINKING_ENABLED,
    )

    private val prefixedPreferenceKeys = listOf(
        KEY_MODEL_SCOPE_PREFIX,
        KEY_API_KEY_SCOPE_PREFIX,
        KEY_BASE_URLS_SCOPE_PREFIX,
        KEY_ENABLED_SCOPE_PREFIX,
        KEY_PERSONA_DETAIL_PREFIX,
    )

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
        Gemini("gemini", R.string.llm_provider_gemini, "https://generativelanguage.googleapis.com/v1beta/openai", CompatApi.OpenAI),
        Anthropic("anthropic", R.string.llm_provider_anthropic, "https://api.anthropic.com/v1", CompatApi.Anthropic),
        DeepSeek("deepseek", R.string.llm_provider_deepseek, "https://api.deepseek.com", CompatApi.OpenAI),
        MiniMax(
            "minimax",
            R.string.llm_provider_minimax,
            "https://api.minimaxi.com/anthropic",
            CompatApi.Anthropic,
            "MiniMax-M2.7",
        ),
        Moonshot(
            "moonshot",
            R.string.llm_provider_moonshot,
            "https://api.moonshot.cn/v1",
            CompatApi.OpenAI,
            "kimi-k2.6",
        ),
        Zhipu("zhipu", R.string.llm_provider_zhipu, "https://open.bigmodel.cn/api/paas/v4", CompatApi.OpenAI);

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

    data class RemoteEndpoint(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
    )

    fun parseBaseUrls(raw: String): List<String> = raw
        .splitToSequence('\n', '\r')
        .map(::normalizeBaseUrl)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    fun encodeBaseUrls(baseUrls: Collection<String>): String = baseUrls
        .map(::normalizeBaseUrl)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n")

    fun selectedBaseUrls(prefs: SharedPreferences, baseUrls: List<String>): List<String> {
        val normalized = baseUrls.map(::normalizeBaseUrl).filter(String::isNotBlank).distinct()
        val selected = prefs.getStringSet(KEY_ENABLED_BASE_URLS, null)?.toSet() ?: normalized.take(1).toSet()
        return normalized.filter { it in selected }
    }

    fun writeBaseUrls(prefs: SharedPreferences, baseUrls: Collection<String>, enabledBaseUrls: Collection<String>) {
        migrateLegacyPreferenceKeys(prefs)
        val provider = currentProvider(prefs)
        val oldPrimary = primaryBaseUrl(prefs) ?: providerDefaultBaseUrl(provider, prefs)
        val oldScope = scopedApiKeyKey(prefs, provider, oldPrimary)
        // Bind a legacy single key to its old host before changing the selected primary.
        // Otherwise a later selection could silently send it to a different host.
        val oldKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim().ifBlank {
            if (provider == Provider.Custom) prefs.getString(KEY_CUSTOM_DEFAULT_API_KEY, "").orEmpty().trim() else ""
        }
        val normalized = baseUrls.map(::normalizeBaseUrl).filter(String::isNotBlank).distinct()
        val enabled = enabledBaseUrls.map(::normalizeBaseUrl).filter { it in normalized }.toSet()
            .ifEmpty { normalized.take(1).toSet() }
        val newPrimary = normalized.firstOrNull { it in enabled }
        val newKey = newPrimary?.let { url ->
            if (url == oldPrimary && !prefs.contains(oldScope)) oldKey
            else prefs.getString(scopedApiKeyKey(prefs, provider, url), "").orEmpty().trim()
        }.orEmpty()
        prefs.edit().apply {
            if (oldKey.isNotBlank() && !prefs.contains(oldScope)) putString(oldScope, oldKey)
            putString(KEY_BASE_URL, encodeBaseUrls(normalized))
            putStringSet(KEY_ENABLED_BASE_URLS, enabled)
            putString(KEY_API_KEY, newKey)
        }.apply()
    }

    /**
     * Persists an API key per domain, writing it onto every configured URL that
     * shares the domain (keys are scoped per full URL, so same-domain endpoints
     * stay in sync). The active key ([KEY_API_KEY]) — and, for the custom
     * provider, the stored default — follows [primaryDomain]. A blank key clears
     * that domain's scoped entries.
     */
    fun writeScopedApiKeys(
        prefs: SharedPreferences,
        provider: Provider,
        keyByDomain: Map<String, String>,
        primaryDomain: String?,
    ) {
        migrateLegacyPreferenceKeys(prefs)
        val urlsByDomain = parseBaseUrls(prefs.getString(KEY_BASE_URL, "").orEmpty()).groupBy(::domainOf)
        val editor = prefs.edit()
        keyByDomain.forEach { (domain, rawKey) ->
            val key = rawKey.trim()
            urlsByDomain[domainOf(domain)].orEmpty().forEach { url ->
                val scopeKey = scopedApiKeyKey(prefs, provider, url)
                if (key.isBlank()) editor.remove(scopeKey) else editor.putString(scopeKey, key)
            }
        }
        val primaryKey = primaryDomain?.let { keyByDomain[it] ?: keyByDomain[domainOf(it)] }?.trim().orEmpty()
        editor.putString(KEY_API_KEY, primaryKey)
        if (provider == Provider.Custom) {
            editor.putString(KEY_CUSTOM_DEFAULT_API_KEY, primaryKey)
        }
        editor.apply()
    }

    /** Called only after confirming URL edits; a provider switch keeps its scoped settings. */
    fun removeScopedEndpointSettings(prefs: SharedPreferences, provider: Provider, removedUrls: Collection<String>) {
        if (removedUrls.isEmpty()) return
        prefs.edit().apply {
            removedUrls.forEach { url ->
                remove(scopedApiKeyKey(prefs, provider, url))
                remove(scopedModelKey(prefs, provider, url))
            }
        }.apply()
    }

    data class EndpointDomain(val domain: String, val representativeBaseUrl: String)

    /** The domain (host) that groups endpoints and scopes their key and model. */
    fun domainOf(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        if (normalized.isBlank()) return ""
        return runCatching { URL(normalized).host }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.lowercase()
            ?: normalized
    }

    /** Unique domains across the ENABLED URLs (an unchecked URL hides its domain
     *  unless another enabled URL shares it), each with a representative URL. */
    fun endpointDomains(prefs: SharedPreferences): List<EndpointDomain> {
        val allUrls = parseBaseUrls(prefs.getString(KEY_BASE_URL, "").orEmpty())
        val seen = linkedMapOf<String, String>()
        selectedBaseUrls(prefs, allUrls).forEach { url ->
            val domain = domainOf(url)
            if (domain.isNotBlank() && domain !in seen) seen[domain] = url
        }
        return seen.map { (domain, url) -> EndpointDomain(domain, url) }
    }

    /** Domain of the active (first enabled) endpoint, used to sync the primary key/model. */
    /** The active (first enabled) base URL, or the first configured URL. */
    fun primaryBaseUrl(prefs: SharedPreferences): String? {
        val urls = parseBaseUrls(prefs.getString(KEY_BASE_URL, "").orEmpty())
        return selectedBaseUrls(prefs, urls).firstOrNull() ?: urls.firstOrNull()
    }

    fun primaryDomain(prefs: SharedPreferences): String? = primaryBaseUrl(prefs)?.let(::domainOf)

    /**
     * Remembers the current provider's URL list and enabled set, then loads [to]'s
     * remembered list (or, the first time, [to]'s default URL — enabled) into the
     * active [KEY_BASE_URL] / [KEY_ENABLED_BASE_URLS]. Returns the active URL text.
     * Lets each provider keep its own URLs and checkmarks across provider switches.
     */
    fun switchProviderBaseUrls(prefs: SharedPreferences, from: Provider, to: Provider): String {
        migrateLegacyPreferenceKeys(prefs)
        if (from == to) return prefs.getString(KEY_BASE_URL, "").orEmpty()
        prefs.edit().apply {
            putString(scopedBaseUrlsKey(from), prefs.getString(KEY_BASE_URL, "").orEmpty())
            val enabled = prefs.getStringSet(KEY_ENABLED_BASE_URLS, null)
            if (enabled == null) remove(scopedEnabledKey(from)) else putStringSet(scopedEnabledKey(from), enabled)
        }.apply()
        val urls: List<String>
        val enabled: Collection<String>
        if (prefs.contains(scopedBaseUrlsKey(to))) {
            urls = parseBaseUrls(prefs.getString(scopedBaseUrlsKey(to), "").orEmpty())
            enabled = prefs.getStringSet(scopedEnabledKey(to), null)?.toList() ?: urls.take(1)
        } else {
            urls = parseBaseUrls(providerDefaultBaseUrl(to, prefs))
            enabled = urls
        }
        writeBaseUrls(prefs, urls, enabled)
        return encodeBaseUrls(urls)
    }

    private fun scopedBaseUrlsKey(provider: Provider): String = KEY_BASE_URLS_SCOPE_PREFIX + provider.value

    private fun scopedEnabledKey(provider: Provider): String = KEY_ENABLED_SCOPE_PREFIX + provider.value

    /** (domain, apiKey) for each enabled domain that has a key configured — its own
     *  scoped key, or the legacy shared key for the primary domain. Mirrors what the
     *  key editor shows, for the settings summary. */
    fun configuredApiKeys(prefs: SharedPreferences): List<Pair<String, String>> {
        val provider = currentProvider(prefs)
        val primary = primaryDomain(prefs)
        return endpointDomains(prefs).mapNotNull { entry ->
            val key = getScopedApiKeyRaw(prefs, provider, entry.representativeBaseUrl).ifBlank {
                if (entry.domain == primary) prefs.getString(KEY_API_KEY, "").orEmpty().trim() else ""
            }
            if (key.isBlank()) null else entry.domain to key
        }
    }

    /** The model name explicitly configured for each enabled domain (raw scoped, with
     *  NO fallback to the shared model) — so a domain left blank is not counted or
     *  listed as another copy of the primary domain's model. */
    fun enabledModels(prefs: SharedPreferences): List<String> {
        val provider = currentProvider(prefs)
        return endpointDomains(prefs).map { entry ->
            getScopedModel(prefs, provider, entry.representativeBaseUrl)
        }
    }

    /** Persists a model per domain, mirroring [writeScopedApiKeys] (written onto
     *  every same-domain URL). The active model ([KEY_MODEL]) follows
     *  [primaryDomain] when that domain has a non-blank model. */
    fun writeScopedModels(
        prefs: SharedPreferences,
        provider: Provider,
        modelByDomain: Map<String, String>,
        primaryDomain: String?,
    ) {
        migrateLegacyPreferenceKeys(prefs)
        val urlsByDomain = parseBaseUrls(prefs.getString(KEY_BASE_URL, "").orEmpty()).groupBy(::domainOf)
        val editor = prefs.edit()
        modelByDomain.forEach { (domain, rawModel) ->
            val model = rawModel.trim()
            urlsByDomain[domainOf(domain)].orEmpty().forEach { url ->
                val scopeKey = scopedModelKey(prefs, provider, url)
                if (model.isBlank()) editor.remove(scopeKey) else editor.putString(scopeKey, model)
            }
        }
        val primaryModel = primaryDomain?.let { modelByDomain[it] ?: modelByDomain[domainOf(it)] }?.trim().orEmpty()
        if (primaryModel.isNotBlank()) editor.putString(KEY_MODEL, primaryModel)
        editor.apply()
    }

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
        val remoteEndpoints: List<RemoteEndpoint> = emptyList(),
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
        migrateLegacyPreferenceKeys(prefs)
        val debounceMs = DEFAULT_DEBOUNCE_MS.toLong()
        val provider = overrides.provider ?: currentProvider(prefs)
        val runtime = overrides.runtime ?: runtimeForProvider(
            provider = provider,
            storedRuntime = prefs.getString(KEY_RUNTIME, Runtime.Remote.value),
        )
        val chatApiEnabled = isChatApiEnabled(prefs)
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
        val rawBaseUrl = overrides.baseUrl ?: prefs.getString(
            KEY_BASE_URL,
            providerDefaultBaseUrl(provider, prefs),
        ).orEmpty()
        val baseUrls = parseBaseUrls(rawBaseUrl)
        val storedPrimary = baseUrls.firstOrNull() ?: providerDefaultBaseUrl(provider, prefs)
        val enabledBaseUrls = prefs.getStringSet(KEY_ENABLED_BASE_URLS, null)
        val selectedBaseUrls = when {
            overrides.baseUrl != null -> listOf(normalizeBaseUrl(overrides.baseUrl))
            enabledBaseUrls == null -> baseUrls.take(1)
            else -> baseUrls.filter { normalizeBaseUrl(it) in enabledBaseUrls }
        }.distinct()
        val baseUrl = selectedBaseUrls.firstOrNull() ?: normalizeBaseUrl(storedPrimary)
        val personaValue = currentPersonaValue(prefs)
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
            personaPreset = PersonaPreset.from(personaValue),
            personaName = currentPersonaName(prefs),
            customPersona = readPersonaDetail(
                prefs = prefs,
                personaValue = personaValue,
            ),
            remoteEndpoints = selectedBaseUrls.map { endpointBaseUrl ->
                val normalized = normalizeBaseUrl(endpointBaseUrl)
                val isActivePrimary = normalized == normalizeBaseUrl(baseUrl)
                RemoteEndpoint(
                    baseUrl = normalized,
                    model = if (isActivePrimary) {
                        (overrides.model ?: getScopedModel(prefs, provider, normalized)
                            .ifBlank { prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty() })
                            .trim()
                            .ifBlank { providerDefaultModel(provider).ifBlank { runtime.defaultModel } }
                    } else {
                        getScopedModel(prefs, provider, normalized).ifBlank {
                            prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().trim()
                                .ifBlank { providerDefaultModel(provider).ifBlank { runtime.defaultModel } }
                        }
                    },
                    apiKey = if (isActivePrimary) {
                        (overrides.apiKey ?: getScopedApiKey(prefs, provider, normalized)).trim()
                    } else {
                        getScopedApiKey(prefs, provider, normalized).trim()
                    },
                )
            },
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
        val value = currentPersonaValue(prefs)
        return PersonaPreset.entries.firstOrNull { it.value == value }?.let {
            ""
        } ?: value
    }

    fun currentPersonaDisplayName(
        context: Context,
        prefs: SharedPreferences,
    ): String {
        val value = currentPersonaValue(prefs)
        val preset = PersonaPreset.entries.firstOrNull { it.value == value }
        return if (preset != null) {
            context.getString(preset.titleRes)
        } else {
            value.ifBlank { context.getString(PersonaPreset.Custom.titleRes) }
        }
    }

    fun readCustomPersonaNames(prefs: SharedPreferences): List<String> {
        migrateLegacyPreferenceKeys(prefs)
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
        migrateLegacyPreferenceKeys(prefs)
        prefs.edit().putStringSet(
            KEY_CUSTOM_PERSONA_NAMES,
            names.map(String::trim).filter { it.isNotBlank() }.toSet(),
        ).apply()
    }

    fun readPersonaDetail(
        prefs: SharedPreferences,
        personaValue: String,
    ): String {
        migrateLegacyPreferenceKeys(prefs)
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
        migrateLegacyPreferenceKeys(prefs)
        prefs.edit().putString(personaDetailKey(personaValue), detail.trim()).apply()
    }

    private fun personaDetailKey(personaValue: String): String {
        val safeValue = URLEncoder.encode(personaValue, StandardCharsets.UTF_8.toString())
        return "$KEY_PERSONA_DETAIL_PREFIX$safeValue"
    }

    fun migrateSeekBarBackedPreferences(prefs: SharedPreferences) {
        migrateLegacyPreferenceKeys(prefs)
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
        prefs?.let(::migrateLegacyPreferenceKeys)
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

    fun isEnabled(prefs: SharedPreferences): Boolean {
        migrateLegacyPreferenceKeys(prefs)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun isChatApiEnabled(prefs: SharedPreferences): Boolean {
        migrateLegacyPreferenceKeys(prefs)
        return if (prefs.contains(KEY_CHAT_API_ENABLED)) {
            prefs.getBoolean(KEY_CHAT_API_ENABLED, false)
        } else {
            prefs.getString(KEY_BACKEND, Backend.Completion.value) == Backend.ChatCompletions.value
        }
    }

    fun currentPersonaValue(prefs: SharedPreferences): String {
        migrateLegacyPreferenceKeys(prefs)
        return prefs.getString(KEY_PERSONA_PRESET, PersonaPreset.Custom.value).orEmpty()
    }

    fun getScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        migrateLegacyPreferenceKeys(prefs)
        val scoped = getScopedApiKeyRaw(prefs, provider, baseUrl)
        if (scoped.isNotBlank()) return scoped

        // The legacy shared value belongs to the current provider and primary host only.
        // Never silently send it to a different host; sharing must be explicitly enabled.
        if (provider != currentProvider(prefs)) return ""
        val primaryUrl = primaryBaseUrl(prefs) ?: providerDefaultBaseUrl(provider, prefs)
        val sameDomain = domainOf(baseUrl) == domainOf(primaryUrl)
        if (!sameDomain && !prefs.getBoolean(KEY_SHARE_PRIMARY_API_KEY, false)) return ""
        val primaryScoped = getScopedApiKeyRaw(prefs, provider, primaryUrl)
        if (primaryScoped.isNotBlank()) return primaryScoped
        // Once a URL list has been saved, its legacy key was bound to the old host.
        if (prefs.contains(KEY_ENABLED_BASE_URLS)) return ""
        return if (provider == Provider.Custom) {
            prefs.getString(KEY_CUSTOM_DEFAULT_API_KEY, "").orEmpty().trim()
        } else {
            ""
        }.ifBlank { prefs.getString(KEY_API_KEY, "").orEmpty().trim() }
    }

    /** The API key stored specifically for [baseUrl]'s scope, WITHOUT falling back to
     *  the shared/default key — so a per-domain editor shows blank when a domain has no
     *  key of its own, instead of echoing (and then overwriting with) another domain's key. */
    fun getScopedApiKeyRaw(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        migrateLegacyPreferenceKeys(prefs)
        return prefs.getString(scopedApiKeyKey(prefs, provider, baseUrl), "").orEmpty().trim()
    }

    fun getScopedModel(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        migrateLegacyPreferenceKeys(prefs)
        return prefs.getString(scopedModelKey(prefs, provider, baseUrl), "").orEmpty().trim()
    }

    fun persistScopedApiKey(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
        apiKey: String,
    ) {
        migrateLegacyPreferenceKeys(prefs)
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
        migrateLegacyPreferenceKeys(prefs)
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
        migrateLegacyPreferenceKeys(prefs)
        prefs.edit()
            .putString(KEY_CUSTOM_DEFAULT_BASE_URL, normalizeBaseUrl(baseUrl))
            .apply()
    }

    fun syncScopedApiKeyToActivePreferences(
        prefs: SharedPreferences,
        provider: Provider,
        baseUrl: String,
    ): String {
        migrateLegacyPreferenceKeys(prefs)
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
        migrateLegacyPreferenceKeys(prefs)
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

    fun currentProvider(prefs: SharedPreferences): Provider {
        migrateLegacyPreferenceKeys(prefs)
        return Provider.from(prefs.getString(KEY_PROVIDER, Provider.Custom.value))
    }

    fun currentRuntime(prefs: SharedPreferences): Runtime {
        migrateLegacyPreferenceKeys(prefs)
        return runtimeForProvider(
            provider = currentProvider(prefs),
            storedRuntime = prefs.getString(KEY_RUNTIME, Runtime.Remote.value),
        )
    }

    fun currentPredictionDisplayMode(prefs: SharedPreferences): PredictionDisplayMode {
        migrateLegacyPreferenceKeys(prefs)
        return PredictionDisplayMode.from(
            prefs.getString(KEY_PREDICTION_DISPLAY_MODE, PredictionDisplayMode.FloatingWindow.value)
        )
    }

    internal fun readRememberedUiMode(
        prefs: SharedPreferences,
        defaultThinkingEnabled: Boolean = false,
    ): RememberedUiMode {
        migrateLegacyPreferenceKeys(prefs)
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
        migrateLegacyPreferenceKeys(prefs)
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

    fun currentBaseUrl(prefs: SharedPreferences, provider: Provider = currentProvider(prefs)): String {
        migrateLegacyPreferenceKeys(prefs)
        return normalizeBaseUrl(
            prefs.getString(KEY_BASE_URL, providerDefaultBaseUrl(provider, prefs)).orEmpty()
                .ifBlank { providerDefaultBaseUrl(provider, prefs) }
        )
    }

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

    fun migrateLegacyPreferenceKeys(prefs: SharedPreferences) {
        val snapshot = prefs.all
        if (snapshot.isEmpty()) return
        val editor = prefs.edit()
        var changed = false

        exactPreferenceKeys.forEach { key ->
            val legacyKey = legacyKeyFor(key) ?: return@forEach
            if (!snapshot.containsKey(key) && snapshot.containsKey(legacyKey)) {
                putPreferenceValue(editor, key, snapshot[legacyKey])
                changed = true
            }
        }

        prefixedPreferenceKeys.forEach { prefix ->
            val legacyPrefix = legacyKeyFor(prefix) ?: return@forEach
            snapshot.forEach { (legacyKey, value) ->
                if (legacyKey.startsWith(legacyPrefix)) {
                    val migratedKey = prefix + legacyKey.removePrefix(legacyPrefix)
                    if (!snapshot.containsKey(migratedKey)) {
                        putPreferenceValue(editor, migratedKey, value)
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            editor.apply()
        }
    }

    private fun legacyKeyFor(currentKey: String): String? =
        currentKey.takeIf { it.startsWith(KEY_PREFIX) }
            ?.removePrefix(KEY_PREFIX)
            ?.let { LEGACY_KEY_PREFIX + it }

    private fun putPreferenceValue(
        editor: SharedPreferences.Editor,
        key: String,
        value: Any?,
    ) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

}
