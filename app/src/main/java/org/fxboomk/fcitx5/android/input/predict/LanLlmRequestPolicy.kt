package org.fxboomk.fcitx5.android.input.predict

import org.json.JSONObject

internal enum class RequestAugmentation {
    None,
    ThinkFalse,
    ThinkingDisabled,
    ReasoningEffortNone,
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
        isSuppressed: (baseProtocol: String, augmentation: RequestAugmentation) -> Boolean = { _, _ -> false },
    ): List<RequestVariant> {
        val baseProtocol = if (isAnthropicEndpoint(endpoint)) {
            "anthropic_messages"
        } else {
            "openai_chat_completions"
        }
        val augmentation = preferredAugmentation(config)
        return buildList {
            if (augmentation != RequestAugmentation.None && !isSuppressed(baseProtocol, augmentation)) {
                add(
                    RequestVariant(
                        baseProtocol = baseProtocol,
                        protocol = when (augmentation) {
                            RequestAugmentation.None -> baseProtocol
                            RequestAugmentation.ThinkFalse -> "${baseProtocol}_think_false"
                            RequestAugmentation.ThinkingDisabled -> "${baseProtocol}_thinking_disabled"
                            RequestAugmentation.ReasoningEffortNone -> "${baseProtocol}_reasoning_none"
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

    fun applyAugmentation(payload: JSONObject, augmentation: RequestAugmentation): JSONObject {
        val result = JSONObject(payload.toString())
        when (augmentation) {
            RequestAugmentation.None -> Unit
            RequestAugmentation.ThinkFalse -> result.put("think", false)
            RequestAugmentation.ThinkingDisabled ->
                result.put("thinking", JSONObject().put("type", "disabled"))
            RequestAugmentation.ReasoningEffortNone -> result.put("reasoning_effort", "none")
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

    private fun preferredAugmentation(config: LanLlmPrefs.Config): RequestAugmentation =
        if (config.compatApi == LanLlmPrefs.CompatApi.Anthropic) {
            when (config.provider) {
                LanLlmPrefs.Provider.Anthropic,
                LanLlmPrefs.Provider.MiniMax,
                -> RequestAugmentation.ThinkingDisabled

                else -> RequestAugmentation.None
            }
        } else {
            when (config.provider) {
                LanLlmPrefs.Provider.Custom -> RequestAugmentation.ThinkFalse
                LanLlmPrefs.Provider.OpenAI,
                LanLlmPrefs.Provider.Gemini,
                -> RequestAugmentation.ReasoningEffortNone

                LanLlmPrefs.Provider.DeepSeek,
                LanLlmPrefs.Provider.Zhipu,
                -> if (
                    config.provider == LanLlmPrefs.Provider.DeepSeek &&
                    config.model.equals("deepseek-chat", ignoreCase = true)
                ) {
                    RequestAugmentation.None
                } else {
                    RequestAugmentation.ThinkingDisabled
                }

                else -> RequestAugmentation.None
            }
        }
}
