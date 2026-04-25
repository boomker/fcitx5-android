package org.fxboomk.fcitx5.android.input.predict

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InputStream
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
        val outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        val taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
        val enableThinking: Boolean = false,
    )

    data class PredictionResponse(
        val suggestions: List<String>,
        val rawContent: String,
    )

    suspend fun predict(
        request: PredictionRequest,
        onPartialText: ((String) -> Unit)? = null,
    ): PredictionResponse = withContext(Dispatchers.IO) {
        when (request.config.backend) {
            LanLlmPrefs.Backend.ChatCompletions -> predictChat(request, onPartialText)
            LanLlmPrefs.Backend.Completion -> predictCompletion(request, onPartialText)
        }
    }

    private fun openConnection(endpoint: String, apiKey: String, streaming: Boolean): HttpURLConnection {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (streaming) "text/event-stream, application/json" else "application/json")
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
        onPartialText: ((String) -> Unit)? = null,
    ): PredictionResponse {
        Log.d(TAG, "POST $endpoint beforeCursor='${beforeCursor.take(60)}'")
        val streaming = payload.optBoolean("stream", false)
        val connection = openConnection(endpoint, apiKey, streaming)
        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { input ->
            if (responseCode in 200..299 && streaming) {
                consumeStreamingResponse(
                    input = input,
                    endpoint = endpoint,
                    onPartialText = onPartialText,
                )
            } else {
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
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

    private fun predictChat(
        request: PredictionRequest,
        onPartialText: ((String) -> Unit)?,
    ): PredictionResponse {
        val useRecentCommitBias = request.useRecentCommitBias && request.recentCommittedText.isNotBlank()
        val systemPrompt = LanLlmPrompt.systemPrompt(
            maxPredictionCandidates = request.config.maxPredictionCandidates,
            beforeCursor = request.beforeCursor,
            outputMode = request.outputMode,
            taskMode = request.taskMode,
        )
        val streaming = shouldStreamResponse(request)
        val maxTokens = resolveMaxTokens(request)
        val openAiPayload = JSONObject()
            .put("model", request.config.model)
            .put("stream", streaming)
            .put("temperature", if (request.outputMode == LanLlmOutputMode.LongForm) 0.5 else 0.1)
            .put("max_tokens", maxTokens)
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
                                    outputMode = request.outputMode,
                                    taskMode = request.taskMode,
                                )
                            )
                    )
            )
        if (!shouldReturnPlainText(request)) {
            openAiPayload.put("response_format", JSONObject().put("type", "json_object"))
        }
        val anthropicPayload = JSONObject()
            .put("model", request.config.model)
            .put("system", systemPrompt)
            .put("stream", streaming)
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
                            outputMode = request.outputMode,
                            taskMode = request.taskMode,
                        )
                    )
            ))
            .put("max_tokens", maxTokens)
            .put("temperature", if (request.outputMode == LanLlmOutputMode.LongForm) 0.5 else 0.1)

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
                parseSuggestions = if (shouldReturnPlainText(request)) {
                    LanLlmSuggestionParser::parse
                } else {
                    LanLlmSuggestionParser::parseJsonSuggestions
                },
                request = request,
            )
        }

        return executeWithFallback(
            mode = "chat",
            apiKey = request.config.apiKey,
            beforeCursor = request.beforeCursor,
            plans = plans,
            onPartialText = onPartialText,
        )
    }

    private fun predictCompletion(
        request: PredictionRequest,
        onPartialText: ((String) -> Unit)?,
    ): PredictionResponse {
        val plans = buildCompletionPlans(request)
        return executeWithFallback(
            mode = "completion",
            apiKey = request.config.apiKey,
            beforeCursor = request.beforeCursor,
            plans = plans,
            onPartialText = onPartialText,
        )
    }

    private fun executeWithFallback(
        mode: String,
        apiKey: String,
        beforeCursor: String,
        plans: List<RequestPlan>,
        onPartialText: ((String) -> Unit)?,
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
                    onPartialText = onPartialText,
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
            outputMode = request.outputMode,
            taskMode = request.taskMode,
        )
        val assistantPrefill = LanLlmPrompt.completionAssistantPrefill(
            beforeCursor = request.beforeCursor,
            taskMode = request.taskMode,
        )

        val stops = JSONArray().apply {
            put("<|im_start|>")
            put("<|im_end|>")
            put("<")
            if (
                request.outputMode == LanLlmOutputMode.Suggestions &&
                request.taskMode == LanLlmTaskMode.Completion
            ) {
                put("\n")
                put("。")
                put("！")
                put("？")
                put("，")
                put(",")
            }
        }
        val maxTokens = resolveMaxTokens(request)
        val temperature = if (request.outputMode == LanLlmOutputMode.LongForm) 0.7 else 0.9
        val topP = if (request.outputMode == LanLlmOutputMode.LongForm) 0.9 else 0.95

        val openAiPayload = JSONObject()
            .put("model", request.config.model)
            .put("stream", shouldStreamResponse(request))
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("max_tokens", maxTokens)
            .put("stop", stops)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                LanLlmPrompt.completionSystemPrompt(
                                    beforeCursor = request.beforeCursor,
                                    outputMode = request.outputMode,
                                    taskMode = request.taskMode,
                                )
                            )
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
            .put(
                "system",
                LanLlmPrompt.completionSystemPrompt(
                    beforeCursor = request.beforeCursor,
                    outputMode = request.outputMode,
                    taskMode = request.taskMode,
                )
            )
            .put("stream", shouldStreamResponse(request))
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
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put("top_p", topP)
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
                request = request,
            )
        }
    }

    private fun buildRequestPlans(
        config: LanLlmPrefs.Config,
        endpoint: String,
        payload: JSONObject,
        extractContent: (String) -> String,
        parseSuggestions: (String, String) -> List<String>,
        request: PredictionRequest,
    ): List<RequestPlan> = LanLlmRequestPolicy.variants(
        config = config,
        endpoint = endpoint,
        enableThinking = request.enableThinking,
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
            payload = LanLlmRequestPolicy.applyAugmentation(
                config = config,
                payload = payload,
                augmentation = variant.augmentation,
                thinkingBudgetTokens = resolveThinkingBudgetTokens(request),
            ),
            extractContent = extractContent,
            parseSuggestions = parseSuggestions,
        )
    }

    private fun resolveMaxTokens(request: PredictionRequest): Int {
        val base = if (request.outputMode == LanLlmOutputMode.LongForm) {
            maxOf(request.config.maxOutputTokens, 96)
        } else {
            request.config.maxOutputTokens
        }
        return if (request.enableThinking && request.config.compatApi == LanLlmPrefs.CompatApi.Anthropic) {
            maxOf(base, 1280)
        } else {
            base
        }
    }

    private fun shouldStreamResponse(request: PredictionRequest): Boolean {
        return request.outputMode == LanLlmOutputMode.LongForm ||
            request.taskMode == LanLlmTaskMode.QuestionAnswer
    }

    private fun shouldReturnPlainText(request: PredictionRequest): Boolean {
        return request.outputMode == LanLlmOutputMode.LongForm ||
            request.taskMode == LanLlmTaskMode.QuestionAnswer
    }

    private fun resolveThinkingBudgetTokens(request: PredictionRequest): Int {
        val maxTokens = resolveMaxTokens(request)
        return (maxTokens - 256).coerceIn(1024, 32000)
    }

    private fun consumeStreamingResponse(
        input: InputStream,
        endpoint: String,
        onPartialText: ((String) -> Unit)?,
    ): String {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        val raw = StringBuilder()
        val dataBlock = StringBuilder()
        val text = StringBuilder()

        fun emitChunk(chunk: String) {
            if (chunk.isBlank()) return
            text.append(chunk)
            onPartialText?.invoke(text.toString())
        }

        fun flushDataBlock() {
            if (dataBlock.isEmpty()) return
            val block = dataBlock.toString().trim()
            dataBlock.clear()
            if (block.isBlank() || block == "[DONE]") return
            parseStreamingChunk(block, endpoint, ::emitChunk)
        }

        reader.forEachLine { line ->
            raw.append(line).append('\n')
            when {
                line.isBlank() -> flushDataBlock()
                line.startsWith("data:") -> {
                    dataBlock.append(line.removePrefix("data:").trimStart()).append('\n')
                }
            }
        }
        flushDataBlock()

        val streamedText = text.toString().trim()
        return if (streamedText.isNotBlank()) streamedText else raw.toString().trim()
    }

    private fun parseStreamingChunk(
        rawChunk: String,
        endpoint: String,
        onTextDelta: (String) -> Unit,
    ) {
        rawChunk.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { line ->
                if (line == "[DONE]") return@forEach
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                if (LanLlmRequestPolicy.isAnthropicEndpoint(endpoint) || json.has("type")) {
                    parseAnthropicStreamingChunk(json, onTextDelta)
                } else {
                    parseOpenAiStreamingChunk(json, onTextDelta)
                }
            }
    }

    private fun parseAnthropicStreamingChunk(
        json: JSONObject,
        onTextDelta: (String) -> Unit,
    ) {
        when (json.optString("type")) {
            "content_block_start" -> {
                val block = json.optJSONObject("content_block") ?: return
                if (block.optString("type") == "text") {
                    onTextDelta(block.optString("text"))
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return
                if (delta.optString("type") == "text_delta") {
                    onTextDelta(delta.optString("text"))
                }
            }
        }
    }

    private fun parseOpenAiStreamingChunk(
        json: JSONObject,
        onTextDelta: (String) -> Unit,
    ) {
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            val delta = first?.optJSONObject("delta")
            if (delta != null) {
                extractDeltaText(delta)?.let(onTextDelta)
            } else {
                first?.optString("text")?.takeIf(String::isNotBlank)?.let(onTextDelta)
            }
            return
        }
        extractDeltaText(json)?.let(onTextDelta)
    }

    private fun extractDeltaText(json: JSONObject): String? {
        json.optString("content")
            .takeIf(String::isNotBlank)
            ?.let { return it }
        val content = json.opt("content")
        if (content is JSONArray) {
            val text = buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    val part = item.optString("text")
                    if (part.isNotBlank()) append(part)
                }
            }
            if (text.isNotBlank()) return text
        }
        return null
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
