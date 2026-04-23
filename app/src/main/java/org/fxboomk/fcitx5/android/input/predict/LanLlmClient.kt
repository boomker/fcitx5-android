package org.fxboomk.fcitx5.android.input.predict

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "LanLlmClient"

internal class LanLlmClient(
    private val thinkingSuppressionStore: LanLlmThinkingSuppressionStore = NoOpLanLlmThinkingSuppressionStore,
) {
    private class HttpRequestFailure(
        val statusCode: Int,
        val responseBody: String,
    ) : IllegalStateException("LAN LLM request failed: HTTP $statusCode ${responseBody.take(240)}")

    private data class RequestPlan(
        val endpoint: String,
        val config: LanLlmPrefs.Config,
        val baseProtocol: String,
        val protocol: String,
        val augmentation: RequestAugmentation,
        val payload: JSONObject,
        val extractContent: (String) -> String,
        val parseSuggestions: (String, String) -> List<String>,
    )

    data class PredictionRequest(
        val config: LanLlmPrefs.Config,
        val beforeCursor: String,
        val recentCommittedText: String = "",
        val historyText: String = "",
        val useRecentCommitBias: Boolean = false,
        val seed: Int? = null,
    )

    data class PredictionResponse(
        val suggestions: List<String>,
        val rawContent: String,
    )

    suspend fun predict(request: PredictionRequest): PredictionResponse = withContext(Dispatchers.IO) {
        when (request.config.backend) {
            LanLlmPrefs.Backend.ChatCompletions -> predictChat(request)
            LanLlmPrefs.Backend.Completion -> predictCompletion(request)
        }
    }

    private fun openConnection(endpoint: String, apiKey: String): HttpURLConnection {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            when {
                LanLlmRequestPolicy.isAnthropicEndpoint(endpoint) -> {
                    if (apiKey.isNotBlank()) {
                        setRequestProperty("x-api-key", apiKey)
                    }
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                apiKey.isNotBlank() -> {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
        }
        return connection
    }

    private fun execute(
        endpoint: String,
        apiKey: String,
        payload: JSONObject,
        beforeCursor: String,
        extractContent: (String) -> String,
        parseSuggestions: (String, String) -> List<String> = LanLlmSuggestionParser::parse,
    ): PredictionResponse {
        Log.d(TAG, "POST $endpoint beforeCursor='${beforeCursor.take(60)}'")
        val connection = openConnection(endpoint, apiKey)
        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }.orEmpty()

        Log.d(TAG, "response code=$responseCode body=${responseBody.take(240)}")
        if (responseCode !in 200..299) {
            throw HttpRequestFailure(responseCode, responseBody)
        }

        val rawContent = extractContent(responseBody)
        val suggestions = parseSuggestions(rawContent, beforeCursor)
        Log.d(TAG, "parsed suggestions=$suggestions rawContent=${rawContent.take(240)}")
        return PredictionResponse(
            suggestions = suggestions,
            rawContent = rawContent,
        )
    }

    private fun predictChat(request: PredictionRequest): PredictionResponse {
        val useRecentCommitBias = request.useRecentCommitBias && request.recentCommittedText.isNotBlank()
        val systemPrompt = LanLlmPrompt.systemPrompt(
            maxPredictionCandidates = request.config.maxPredictionCandidates,
            beforeCursor = request.beforeCursor,
        )
        val openAiPayload = JSONObject()
            .put("model", request.config.model)
            .put("stream", false)
            .put("temperature", 0.1)
            .put("max_tokens", request.config.maxOutputTokens)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                LanLlmPrompt.userPrompt(
                                    beforeCursor = request.beforeCursor,
                                    recentCommittedText = request.recentCommittedText,
                                    historyText = request.historyText,
                                    useRecentCommitBias = useRecentCommitBias,
                                )
                            )
                    )
            )
        val anthropicPayload = JSONObject()
            .put("model", request.config.model)
            .put("system", systemPrompt)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        LanLlmPrompt.userPrompt(
                            beforeCursor = request.beforeCursor,
                            recentCommittedText = request.recentCommittedText,
                            historyText = request.historyText,
                            useRecentCommitBias = useRecentCommitBias,
                        )
                    )
            ))
            .put("max_tokens", request.config.maxOutputTokens)
            .put("temperature", 0.1)

        val plans = request.config.chatCompatEndpoints.flatMap { endpoint ->
            buildRequestPlans(
                config = request.config,
                endpoint = endpoint,
                payload = if (LanLlmRequestPolicy.isAnthropicEndpoint(endpoint)) anthropicPayload else openAiPayload,
                extractContent = if (LanLlmRequestPolicy.isAnthropicEndpoint(endpoint)) {
                    ::extractAnthropicContent
                } else {
                    ::extractMessageContent
                },
                parseSuggestions = LanLlmSuggestionParser::parseJsonSuggestions,
            )
        }

        return executeWithFallback(
            mode = "chat",
            apiKey = request.config.apiKey,
            beforeCursor = request.beforeCursor,
            plans = plans,
        )
    }

    private fun predictCompletion(request: PredictionRequest): PredictionResponse {
        val plans = buildCompletionPlans(request)
        return executeWithFallback(
            mode = "completion",
            apiKey = request.config.apiKey,
            beforeCursor = request.beforeCursor,
            plans = plans,
        )
    }

    private fun executeWithFallback(
        mode: String,
        apiKey: String,
        beforeCursor: String,
        plans: List<RequestPlan>,
    ): PredictionResponse {
        var lastFailure: Throwable? = null

        for ((index, plan) in plans.withIndex()) {
            try {
                Log.d(TAG, "predict$mode protocol=${plan.protocol} endpoint=${plan.endpoint}")
                return execute(
                    endpoint = plan.endpoint,
                    apiKey = apiKey,
                    payload = plan.payload,
                    beforeCursor = beforeCursor,
                    extractContent = plan.extractContent,
                    parseSuggestions = plan.parseSuggestions,
                )
            } catch (failure: HttpRequestFailure) {
                lastFailure = failure
                if (
                    LanLlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
                        statusCode = failure.statusCode,
                        responseBody = failure.responseBody,
                        augmentation = plan.augmentation,
                    )
                ) {
                    thinkingSuppressionStore.suppress(
                        config = plan.config,
                        protocol = plan.baseProtocol,
                    )
                }
                val canFallback = index < plans.lastIndex && if (plan.augmentation != RequestAugmentation.None) {
                    true
                } else {
                    shouldFallbackToNextPlan(failure)
                }
                Log.w(
                    TAG,
                    "$mode protocol=${plan.protocol} failed status=${failure.statusCode} fallback=$canFallback body=${failure.responseBody.take(160)}"
                )
                if (!canFallback) throw failure
            }
        }

        throw (lastFailure ?: IllegalStateException("No $mode protocol candidates available"))
    }

    private fun buildCompletionPlans(request: PredictionRequest): List<RequestPlan> {
        val completionUserPrompt = LanLlmPrompt.completionUserPrompt(
            beforeCursor = request.beforeCursor,
            recentCommittedText = request.recentCommittedText,
            historyText = request.historyText,
            useRecentCommitBias = request.useRecentCommitBias,
        )
        val assistantPrefill = LanLlmPrompt.completionAssistantPrefill(request.beforeCursor)

        val stops = JSONArray()
            .put("<|im_start|>")
            .put("<|im_end|>")
            .put("<")
            .put("\n")
            .put("。")
            .put("！")
            .put("？")
            .put("，")
            .put(",")

        val openAiPayload = JSONObject()
            .put("model", request.config.model)
            .put("stream", false)
            .put("temperature", 0.9)
            .put("top_p", 0.95)
            .put("max_tokens", request.config.maxOutputTokens)
            .put("stop", stops)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", LanLlmPrompt.completionSystemPrompt(request.beforeCursor))
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", completionUserPrompt)
                    )
                    .put(
                        JSONObject()
                            .put("role", "assistant")
                            .put("content", assistantPrefill)
                    )
            )
        request.seed?.let { openAiPayload.put("seed", it) }

        val anthropicPayload = JSONObject()
            .put("model", request.config.model)
            .put("system", LanLlmPrompt.completionSystemPrompt(request.beforeCursor))
            .put("messages", JSONArray()
                .put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", completionUserPrompt)
                )
                .put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", assistantPrefill)
            ))
            .put("max_tokens", request.config.maxOutputTokens)
            .put("temperature", 0.9)
            .put("top_p", 0.95)
            .put("stop_sequences", JSONArray(stops.toString()))

        return request.config.completionCompatEndpoints.flatMap { endpoint ->
            buildRequestPlans(
                config = request.config,
                endpoint = endpoint,
                payload = if (LanLlmRequestPolicy.isAnthropicEndpoint(endpoint)) anthropicPayload else openAiPayload,
                extractContent = if (LanLlmRequestPolicy.isAnthropicEndpoint(endpoint)) {
                    ::extractAnthropicContent
                } else {
                    ::extractCompletionContent
                },
                parseSuggestions = LanLlmSuggestionParser::parse,
            )
        }
    }

    private fun buildRequestPlans(
        config: LanLlmPrefs.Config,
        endpoint: String,
        payload: JSONObject,
        extractContent: (String) -> String,
        parseSuggestions: (String, String) -> List<String>,
    ): List<RequestPlan> = LanLlmRequestPolicy.variants(
        config = config,
        endpoint = endpoint,
        isSuppressed = { baseProtocol, augmentation ->
            augmentation == RequestAugmentation.ThinkingDisabled &&
                thinkingSuppressionStore.isSuppressed(config, baseProtocol)
        },
    ).map { variant ->
        RequestPlan(
            endpoint = endpoint,
            config = config,
            baseProtocol = variant.baseProtocol,
            protocol = variant.protocol,
            augmentation = variant.augmentation,
            payload = LanLlmRequestPolicy.applyAugmentation(payload, variant.augmentation),
            extractContent = extractContent,
            parseSuggestions = parseSuggestions,
        )
    }

    private fun shouldFallbackToNextPlan(failure: HttpRequestFailure): Boolean {
        if (failure.statusCode in listOf(400, 404, 405, 501)) return true
        val body = failure.responseBody.lowercase()
        return "not found" in body ||
            "unknown route" in body ||
            "unsupported" in body ||
            "unsupported parameter" in body ||
            "unknown parameter" in body ||
            "unexpected field" in body ||
            "invalid request" in body ||
            "invalid value" in body ||
            "json: unknown field" in body
    }

    private fun extractMessageContent(responseBody: String): String {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices") ?: return responseBody
        if (choices.length() == 0) return responseBody
        val first = choices.optJSONObject(0) ?: return responseBody
        first.optJSONObject("message")?.let { message ->
            val content = message.opt("content")
            when (content) {
                is String -> return content
                is JSONArray -> {
                    val text = buildString {
                        for (index in 0 until content.length()) {
                            val item = content.optJSONObject(index) ?: continue
                            val part = item.optString("text")
                            if (part.isNotBlank()) append(part)
                        }
                    }
                    if (text.isNotBlank()) return text
                }
            }
        }
        return first.optString("text").ifBlank { responseBody }
    }

    private fun extractAnthropicContent(responseBody: String): String {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return responseBody
        val content = root.optJSONArray("content") ?: return responseBody
        val text = buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") {
                    val value = part.optString("text")
                    if (value.isNotBlank()) append(value)
                }
            }
        }
        return text.ifBlank { responseBody }
    }

    private fun extractCompletionContent(responseBody: String): String {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return responseBody
        return root.optString("content").ifBlank {
            extractMessageContent(responseBody).ifBlank { responseBody }
        }
    }
}
