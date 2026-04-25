package org.fxboomk.fcitx5.android.input.predict

import org.json.JSONObject

internal enum class RequestAugmentation {
    None,
    ThinkFalse,
    ThinkTrue,
    ThinkingDisabled,
    ThinkingEnabled,
    ReasoningEffortNone,
    ReasoningEffortHigh,
}

internal data class RequestVariant(
    val baseProtocol: String,
    val protocol: String,
    val augmentation: RequestAugmentation,
)

internal object LanLlmRequestPolicy {
    fun isAnthropicEndpoint(endpoint: String): Boolean = endpoint.endsWith("/messages")

    fun variants(
        config: LanLlmPrefs.Config,
        endpoint: String,
        enableThinking: Boolean,
        isSuppressed: (baseProtocol: String, augmentation: RequestAugmentation) -> Boolean = { _, _ -> false },
    ): List<RequestVariant> {
        val baseProtocol = if (isAnthropicEndpoint(endpoint)) {
            "anthropic_messages"
        } else {
            "openai_chat_completions"
        }
        val augmentation = preferredAugmentation(config, enableThinking)
        return buildList {
            if (augmentation != RequestAugmentation.None && !isSuppressed(baseProtocol, augmentation)) {
                add(
                    RequestVariant(
                        baseProtocol = baseProtocol,
                        protocol = when (augmentation) {
                            RequestAugmentation.None -> baseProtocol
                            RequestAugmentation.ThinkFalse -> "${baseProtocol}_think_false"
                            RequestAugmentation.ThinkTrue -> "${baseProtocol}_think_true"
                            RequestAugmentation.ThinkingDisabled -> "${baseProtocol}_thinking_disabled"
                            RequestAugmentation.ThinkingEnabled -> "${baseProtocol}_thinking_enabled"
                            RequestAugmentation.ReasoningEffortNone -> "${baseProtocol}_reasoning_none"
                            RequestAugmentation.ReasoningEffortHigh -> "${baseProtocol}_reasoning_high"
                        },
                        augmentation = augmentation,
                    )
                )
            }
            add(
                RequestVariant(
                    baseProtocol = baseProtocol,
                    protocol = baseProtocol,
                    augmentation = RequestAugmentation.None,
                )
            )
        }
    }

    fun applyAugmentation(
        config: LanLlmPrefs.Config,
        payload: JSONObject,
        augmentation: RequestAugmentation,
        thinkingBudgetTokens: Int? = null,
    ): JSONObject {
        val result = JSONObject(payload.toString())
        when (augmentation) {
            RequestAugmentation.None -> Unit
            RequestAugmentation.ThinkFalse -> result.put("think", false)
            RequestAugmentation.ThinkTrue -> result.put("think", true)
            RequestAugmentation.ThinkingDisabled ->
                result.put("thinking", JSONObject().put("type", "disabled"))
            RequestAugmentation.ThinkingEnabled -> {
                if (config.provider == LanLlmPrefs.Provider.DeepSeek &&
                    config.compatApi == LanLlmPrefs.CompatApi.OpenAI
                ) {
                    result.put("thinking", JSONObject().put("type", "enabled"))
                    if (!result.has("reasoning_effort")) {
                        result.put("reasoning_effort", "high")
                    }
                } else {
                    val budget = thinkingBudgetTokens ?: 1024
                    result.put(
                        "thinking",
                        JSONObject()
                            .put("type", "enabled")
                            .put("budget_tokens", budget)
                    )
                }
            }
            RequestAugmentation.ReasoningEffortNone -> result.put("reasoning_effort", "none")
            RequestAugmentation.ReasoningEffortHigh -> result.put("reasoning_effort", "high")
        }
        return result
    }

    fun shouldPersistThinkingDisabledSuppression(
        statusCode: Int,
        responseBody: String,
        augmentation: RequestAugmentation,
    ): Boolean {
        if (augmentation != RequestAugmentation.ThinkingDisabled) return false
        if (statusCode !in listOf(400, 404, 405, 422, 501)) return false
        val body = responseBody.lowercase()
        return "unsupported parameter" in body ||
            "unknown parameter" in body ||
            "invalid value" in body
    }

    private fun preferredAugmentation(
        config: LanLlmPrefs.Config,
        enableThinking: Boolean,
    ): RequestAugmentation =
        if (config.compatApi == LanLlmPrefs.CompatApi.Anthropic) {
            when (config.provider) {
                LanLlmPrefs.Provider.Anthropic,
                LanLlmPrefs.Provider.MiniMax,
                -> if (enableThinking) {
                    RequestAugmentation.ThinkingEnabled
                } else {
                    RequestAugmentation.ThinkingDisabled
                }

                else -> RequestAugmentation.None
            }
        } else {
            when (config.provider) {
                LanLlmPrefs.Provider.Custom -> if (enableThinking) {
                    RequestAugmentation.ThinkTrue
                } else {
                    RequestAugmentation.ThinkFalse
                }
                LanLlmPrefs.Provider.OpenAI,
                LanLlmPrefs.Provider.Gemini,
                -> if (enableThinking) {
                    RequestAugmentation.ReasoningEffortHigh
                } else {
                    RequestAugmentation.ReasoningEffortNone
                }

                LanLlmPrefs.Provider.DeepSeek,
                LanLlmPrefs.Provider.Zhipu,
                -> if (enableThinking) {
                    RequestAugmentation.ThinkingEnabled
                } else {
                    RequestAugmentation.ThinkingDisabled
                }

                else -> RequestAugmentation.None
            }
        }
}
