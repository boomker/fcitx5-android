package org.fxboomk.fcitx5.android.input.predict

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "LlmClient"

internal class LlmClient(
    private val thinkingSuppressionStore: LlmThinkingSuppressionStore = NoOpLlmThinkingSuppressionStore,
) {
    private val endpointFailoverState = LlmEndpointFailoverState()

    private class HttpRequestFailure(
        val statusCode: Int,
        val responseBody: String,
        val classifiedFailure: LlmPredictionFailure = LlmPredictionFailure.fromHttp(statusCode, responseBody),
    ) : IllegalStateException("LLM request failed: HTTP $statusCode", classifiedFailure)

    private data class RequestPlan(
        val endpoint: String,
        val config: LlmPrefs.Config,
        val baseProtocol: String,
        val protocol: String,
        val augmentation: RequestAugmentation,
        val payload: JSONObject,
        val extractContent: (String) -> String,
        val parseSuggestions: (String, String) -> List<String>,
    )

    data class PredictionRequest(
        val config: LlmPrefs.Config,
        val beforeCursor: String,
        val recentCommittedText: String = "",
        val historyText: String = "",
        val useRecentCommitBias: Boolean = false,
        val seed: Int? = null,
        val outputMode: LlmOutputMode = LlmOutputMode.Suggestions,
        val taskMode: LlmTaskMode = LlmTaskMode.Completion,
        val enableThinking: Boolean = false,
        val translationCorrectionAttempt: Boolean = false,
    )

    data class PredictionResponse(
        val suggestions: List<String>,
        val rawContent: String,
        val modelId: String = "",
        val usage: TokenUsage? = null,
    )

    data class TokenUsage(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
    )

    private data class ResponseMetadata(
        val modelId: String = "",
        val usage: TokenUsage? = null,
        val suggestions: List<String>? = null,
    )

    suspend fun predict(
        request: PredictionRequest,
        onPartialText: ((String) -> Unit)? = null,
    ): PredictionResponse = withContext(Dispatchers.IO) {
        predictWithEndpointFailover(request, onPartialText, endpointFailoverState, ::predictAtEndpoint)
    }

    private fun predictAtEndpoint(
        request: PredictionRequest,
        onPartialText: ((String) -> Unit)?,
    ): PredictionResponse {
        val response = when (request.config.backend) {
            LlmPrefs.Backend.ChatCompletions -> predictChat(request, onPartialText)
            LlmPrefs.Backend.Completion -> predictCompletion(request, onPartialText)
        }
        return if (
            request.taskMode == LlmTaskMode.Translate &&
            !request.translationCorrectionAttempt &&
            response.suggestions.none {
                normalizeTranslationCandidate(request.beforeCursor, it).isNotBlank()
            }
        ) {
            Log.w(TAG, "translation response echoed the source; retrying with correction prompt")
            val retryRequest = request.copy(translationCorrectionAttempt = true)
            when (retryRequest.config.backend) {
                LlmPrefs.Backend.ChatCompletions -> predictChat(retryRequest, onPartialText)
                LlmPrefs.Backend.Completion -> predictCompletion(retryRequest, onPartialText)
            }
        } else {
            response
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
                LlmRequestPolicy.isAnthropicEndpoint(endpoint) -> {
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
        parseSuggestions: (String, String) -> List<String> = LlmSuggestionParser::parse,
        suppressTranslationEcho: Boolean = false,
        onPartialText: ((String) -> Unit)? = null,
    ): PredictionResponse {
        Log.d(TAG, "POST $endpoint beforeCursor='${beforeCursor.take(60)}'")
        val streaming = payload.optBoolean("stream", false)
        val connection = openConnection(endpoint, apiKey, streaming)
        try {
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
                        onPartialText = if (suppressTranslationEcho && onPartialText != null) {
                            { partial ->
                                val filtered = normalizeTranslationPartialCandidate(beforeCursor, partial)
                                if (filtered.isNotBlank()) {
                                    onPartialText(filtered)
                                }
                            }
                        } else {
                            onPartialText
                        },
                    )
                } else {
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        reader.readText()
                    }
                }
            }.orEmpty()

            Log.d(TAG, "response code=$responseCode bodyLength=${responseBody.length}")
            if (responseCode !in 200..299) {
                throw HttpRequestFailure(responseCode, responseBody)
            }
            if (!streaming) {
                LlmPredictionFailure.classifyProviderEnvelope(responseBody)?.let { classified ->
                    throw HttpRequestFailure(responseCode, responseBody, classified)
                }
            }

            val metadata = if (streaming) null else extractResponseMetadata(responseBody)
            val rawContent = if (streaming) {
                responseBody
            } else {
                extractContent(responseBody)
            }
            val suggestions = metadata?.suggestions?.takeIf { it.isNotEmpty() }
                ?: parseSuggestions(rawContent, beforeCursor)
            if (rawContent.isBlank() || suggestions.isEmpty()) {
                throw LlmPredictionFailure.invalidResponse()
            }
            Log.d(TAG, "parsed suggestions=$suggestions rawContent=${rawContent.take(240)}")
            return PredictionResponse(
                suggestions = suggestions,
                rawContent = rawContent,
                modelId = metadata?.modelId.orEmpty(),
                usage = metadata?.usage,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun predictChat(
        request: PredictionRequest,
        onPartialText: ((String) -> Unit)?,
    ): PredictionResponse {
        val useRecentCommitBias = request.useRecentCommitBias && request.recentCommittedText.isNotBlank()
        val systemPrompt = LlmPrompt.systemPrompt(
            maxPredictionCandidates = request.config.maxPredictionCandidates,
            beforeCursor = request.beforeCursor,
            outputMode = request.outputMode,
            taskMode = request.taskMode,
            personaPreset = request.config.personaPreset,
            customPersona = request.config.customPersona,
        ).let { prompt ->
            if (request.translationCorrectionAttempt) {
                "$prompt\n\n${LlmPrompt.translationCorrectionInstruction()}"
            } else {
                prompt
            }
        }
        val streaming = shouldStreamResponse(request)
        val maxTokens = resolveMaxTokens(request)
        val openAiPayload = JSONObject()
            .put("model", request.config.model)
            .put("stream", streaming)
            .put(
                "temperature",
                when {
                    request.taskMode == LlmTaskMode.Translate -> 0.2
                    request.outputMode == LlmOutputMode.LongForm -> 0.5
                    else -> 0.1
                }
            )
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
                                LlmPrompt.userPrompt(
                                    beforeCursor = request.beforeCursor,
                                    recentCommittedText = request.recentCommittedText,
                                    historyText = request.historyText,
                                    useRecentCommitBias = useRecentCommitBias,
                                    outputMode = request.outputMode,
                                    taskMode = request.taskMode,
                                ).let { prompt ->
                                    if (request.translationCorrectionAttempt) {
                                        "$prompt\n\n${LlmPrompt.translationCorrectionInstruction()}"
                                    } else {
                                        prompt
                                    }
                                }
                            )
                    )
            )
        if (!shouldReturnPlainText(request)) {
            openAiPayload.put("response_format", JSONObject().put("type", "json_object"))
        }
        applyMoonshotKimiCompat(openAiPayload, request)
        val anthropicPayload = JSONObject()
            .put("model", request.config.model)
            .put("system", systemPrompt)
            .put("stream", streaming)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        LlmPrompt.userPrompt(
                            beforeCursor = request.beforeCursor,
                            recentCommittedText = request.recentCommittedText,
                            historyText = request.historyText,
                            useRecentCommitBias = useRecentCommitBias,
                            outputMode = request.outputMode,
                            taskMode = request.taskMode,
                        ).let { prompt ->
                            if (request.translationCorrectionAttempt) {
                                "$prompt\n\n${LlmPrompt.translationCorrectionInstruction()}"
                            } else {
                                prompt
                            }
                        }
                    )
            ))
            .put("max_tokens", maxTokens)
            .put(
                "temperature",
                when {
                    request.taskMode == LlmTaskMode.Translate -> 0.2
                    request.outputMode == LlmOutputMode.LongForm -> 0.5
                    else -> 0.1
                }
            )
        applyMoonshotKimiCompat(anthropicPayload, request)

        val plans = request.config.chatCompatEndpoints.flatMap { endpoint ->
            buildRequestPlans(
                config = request.config,
                endpoint = endpoint,
                payload = if (LlmRequestPolicy.isAnthropicEndpoint(endpoint)) anthropicPayload else openAiPayload,
                extractContent = if (LlmRequestPolicy.isAnthropicEndpoint(endpoint)) {
                    ::extractAnthropicContent
                } else {
                    ::extractMessageContent
                },
                parseSuggestions = if (shouldReturnPlainText(request)) {
                    { raw, _ -> LlmSuggestionParser.parseSingleText(raw) }
                } else {
                    LlmSuggestionParser::parseJsonSuggestions
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
            suppressTranslationEcho = request.taskMode == LlmTaskMode.Translate,
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
            suppressTranslationEcho = request.taskMode == LlmTaskMode.Translate,
        )
    }

    private fun executeWithFallback(
        mode: String,
        apiKey: String,
        beforeCursor: String,
        plans: List<RequestPlan>,
        suppressTranslationEcho: Boolean = false,
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
                    suppressTranslationEcho = suppressTranslationEcho,
                    onPartialText = onPartialText,
                )
            } catch (failure: HttpRequestFailure) {
                lastFailure = failure.classifiedFailure
                if (
                    LlmRequestPolicy.shouldPersistThinkingDisabledSuppression(
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
                    "$mode protocol=${plan.protocol} failed status=${failure.statusCode} fallback=$canFallback"
                )
                if (!canFallback) throw failure.classifiedFailure
            } catch (failure: IOException) {
                throw LlmPredictionFailure.fromNetwork(failure)
            }
        }

        throw (lastFailure ?: LlmPredictionFailure.invalidResponse())
    }

    private fun buildCompletionPlans(request: PredictionRequest): List<RequestPlan> {
        val candidateLimit = normalizedPredictionCandidateLimit(request.config.maxPredictionCandidates)
        val completionUserPrompt = LlmPrompt.completionUserPrompt(
            beforeCursor = request.beforeCursor,
            recentCommittedText = request.recentCommittedText,
            historyText = request.historyText,
            useRecentCommitBias = request.useRecentCommitBias,
            outputMode = request.outputMode,
            taskMode = request.taskMode,
        ).let { prompt ->
            if (request.translationCorrectionAttempt) {
                "$prompt\n\n${LlmPrompt.translationCorrectionInstruction()}"
            } else {
                prompt
            }
        }
        val assistantPrefill = LlmPrompt.completionAssistantPrefill(
            beforeCursor = request.beforeCursor,
            taskMode = request.taskMode,
        )

        val stops = JSONArray().apply {
            put("<|im_start|>")
            put("<|im_end|>")
            put("<")
        }
        val maxTokens = resolveMaxTokens(request)
        val temperature = when {
            request.taskMode == LlmTaskMode.Translate -> 0.3
            request.outputMode == LlmOutputMode.LongForm -> 0.7
            else -> 0.9
        }
        val topP = when {
            request.taskMode == LlmTaskMode.Translate -> 0.85
            request.outputMode == LlmOutputMode.LongForm -> 0.9
            else -> 0.95
        }

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
                                LlmPrompt.completionSystemPrompt(
                                    maxPredictionCandidates = candidateLimit,
                                    beforeCursor = request.beforeCursor,
                                    outputMode = request.outputMode,
                                    taskMode = request.taskMode,
                                    personaPreset = request.config.personaPreset,
                                    customPersona = request.config.customPersona,
                                ).let { prompt ->
                                    if (request.translationCorrectionAttempt) {
                                        "$prompt\n\n${LlmPrompt.translationCorrectionInstruction()}"
                                    } else {
                                        prompt
                                    }
                                }
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
        applyMoonshotKimiCompat(openAiPayload, request)
        request.seed?.let { openAiPayload.put("seed", it) }

        val anthropicPayload = JSONObject()
            .put("model", request.config.model)
            .put(
                "system",
                LlmPrompt.completionSystemPrompt(
                    maxPredictionCandidates = candidateLimit,
                    beforeCursor = request.beforeCursor,
                    outputMode = request.outputMode,
                    taskMode = request.taskMode,
                    personaPreset = request.config.personaPreset,
                    customPersona = request.config.customPersona,
                ).let { prompt ->
                    if (request.translationCorrectionAttempt) {
                        "$prompt\n\n${LlmPrompt.translationCorrectionInstruction()}"
                    } else {
                        prompt
                    }
                }
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
        applyMoonshotKimiCompat(anthropicPayload, request)

        return request.config.completionCompatEndpoints.flatMap { endpoint ->
            buildRequestPlans(
                config = request.config,
                endpoint = endpoint,
                payload = if (LlmRequestPolicy.isAnthropicEndpoint(endpoint)) anthropicPayload else openAiPayload,
                extractContent = if (LlmRequestPolicy.isAnthropicEndpoint(endpoint)) {
                    ::extractAnthropicContent
                } else {
                    ::extractCompletionContent
                },
                parseSuggestions = if (shouldReturnPlainText(request)) {
                    { raw, _ -> LlmSuggestionParser.parseSingleText(raw) }
                } else {
                    LlmSuggestionParser::parse
                },
                request = request,
            )
        }
    }

    private fun buildRequestPlans(
        config: LlmPrefs.Config,
        endpoint: String,
        payload: JSONObject,
        extractContent: (String) -> String,
        parseSuggestions: (String, String) -> List<String>,
        request: PredictionRequest,
    ): List<RequestPlan> = LlmRequestPolicy.variants(
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
            payload = LlmRequestPolicy.applyAugmentation(
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
        val base = when {
            request.taskMode == LlmTaskMode.Translate -> maxOf(request.config.maxOutputTokens, 192)
            request.outputMode == LlmOutputMode.LongForm ||
                request.taskMode == LlmTaskMode.QuestionAnswer ->
                maxOf(request.config.maxOutputTokens, FULL_TEXT_MODE_MIN_OUTPUT_TOKENS)
            else -> request.config.maxOutputTokens
        }
        return if (request.enableThinking && request.config.compatApi == LlmPrefs.CompatApi.Anthropic) {
            maxOf(base, 1280)
        } else {
            base
        }
    }

    private fun shouldStreamResponse(request: PredictionRequest): Boolean {
        return request.outputMode == LlmOutputMode.LongForm ||
            request.taskMode == LlmTaskMode.QuestionAnswer ||
            request.taskMode == LlmTaskMode.Translate
    }

    private fun shouldReturnPlainText(request: PredictionRequest): Boolean {
        return request.outputMode == LlmOutputMode.LongForm ||
            request.taskMode == LlmTaskMode.QuestionAnswer ||
            request.taskMode == LlmTaskMode.Translate
    }

    private data class MoonshotSamplingSettings(
        val temperature: Double? = null,
        val topP: Double? = null,
        val thinkingType: String? = null,
        val reasoningEffort: String? = null,
        val stripFixedSamplingFields: Boolean = false,
    )

    private fun applyMoonshotKimiCompat(payload: JSONObject, request: PredictionRequest) {
        val settings = resolveMoonshotSamplingSettings(request) ?: return
        if (settings.stripFixedSamplingFields) {
            payload.remove("temperature")
            payload.remove("top_p")
            payload.remove("n")
            payload.remove("presence_penalty")
            payload.remove("frequency_penalty")
            payload.remove("thinking")
        }
        settings.thinkingType?.let { thinkingType ->
            payload.put("thinking", JSONObject().put("type", thinkingType))
        }
        settings.reasoningEffort?.let { reasoningEffort ->
            payload.put("reasoning_effort", reasoningEffort)
        }
        settings.temperature?.let { payload.put("temperature", it) }
        settings.topP?.let { payload.put("top_p", it) }
        if (!settings.stripFixedSamplingFields) {
            payload.put("n", 1)
            payload.put("presence_penalty", 0.0)
            payload.put("frequency_penalty", 0.0)
        }
    }

    private fun isMoonshotK2Model(config: LlmPrefs.Config): Boolean {
        return config.provider == LlmPrefs.Provider.Moonshot &&
            config.model.startsWith("kimi-k2", ignoreCase = true)
    }

    private fun isMoonshotK3Model(config: LlmPrefs.Config): Boolean {
        return config.provider == LlmPrefs.Provider.Moonshot &&
            config.model.startsWith("kimi-k3", ignoreCase = true)
    }

    private fun resolveMoonshotSamplingSettings(request: PredictionRequest): MoonshotSamplingSettings? {
        return when {
            isMoonshotK2Model(request.config) -> MoonshotSamplingSettings(
                temperature = if (request.enableThinking) 1.0 else 0.6,
                topP = 0.95,
                thinkingType = if (request.enableThinking) "enabled" else "disabled",
            )
            isMoonshotK3Model(request.config) -> MoonshotSamplingSettings(
                reasoningEffort = "max",
                stripFixedSamplingFields = true,
            )
            else -> null
        }
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
                LlmPredictionFailure.classifyProviderEnvelope(line)?.let { classified ->
                    throw HttpRequestFailure(statusCode = 200, responseBody = line, classifiedFailure = classified)
                }
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                if (LlmRequestPolicy.isAnthropicEndpoint(endpoint) || json.has("type")) {
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
                    block.optNonBlankText("text")?.let(onTextDelta)
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta") ?: return
                if (delta.optString("type") == "text_delta") {
                    delta.optNonBlankText("text")?.let(onTextDelta)
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
                first?.optNonBlankText("text")?.let(onTextDelta)
            }
            return
        }
        extractDeltaText(json)?.let(onTextDelta)
    }

    private fun extractDeltaText(json: JSONObject): String? {
        return extractDeltaText(json.toString())
    }

    private fun extractDeltaText(raw: String): String? {
        return extractFirstStringField(raw, "content")
            ?: extractStructuredTextContent(raw)
    }

    private fun JSONObject.optNonBlankText(key: String): String? {
        val value = opt(key)
        return if (value == null || value == JSONObject.NULL) {
            null
        } else {
            value.toString()
        }
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotEmpty()) add(value)
        }
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
        if (!responseBody.trimStart().startsWith("{")) return responseBody
        return extractStructuredTextContent(responseBody)
            ?: extractMessageContentString(responseBody)
            ?: extractFirstStringField(responseBody, "text")
            ?: responseBody
    }

    private fun extractAnthropicContent(responseBody: String): String {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return responseBody
        val content = root.optJSONArray("content") ?: return responseBody
        val text = buildString {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") {
                    val value = part.optNonBlankText("text")
                    if (value != null) append(value)
                }
            }
        }
        return text.ifBlank { responseBody }
    }

    private fun extractCompletionContent(responseBody: String): String {
        if (!responseBody.trimStart().startsWith("{")) return responseBody
        return extractFirstStringField(responseBody, "content")
            ?: extractStructuredTextContent(responseBody)
            ?: extractMessageContentString(responseBody)
            ?: responseBody
    }

    private fun extractResponseMetadata(responseBody: String): ResponseMetadata? {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null
        return ResponseMetadata(
            modelId = root.optString("model").trim(),
            usage = root.optJSONObject("usage")?.let {
                TokenUsage(
                    inputTokens = it.optNullableInt("prompt_tokens"),
                    outputTokens = it.optNullableInt("completion_tokens"),
                    totalTokens = it.optNullableInt("total_tokens"),
                )
            },
            suggestions = extractSuggestionsArray(root),
        )
    }

    private fun extractSuggestionsArray(root: JSONObject): List<String>? {
        root.optJSONArray("suggestions")?.toStringList()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val first = choices.optJSONObject(0)
            val content = first?.optJSONObject("message")?.optString("content")
                ?: first?.optString("text")
            extractSuggestionsArrayFromText(content)
                .takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        val content = root.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                extractSuggestionsArrayFromText(item.optString("text"))
                    .takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun extractSuggestionsArrayFromText(text: String?): List<String> {
        val normalized = text?.trim().orEmpty()
        if (normalized.isBlank()) return emptyList()
        val json = runCatching { JSONObject(normalized) }.getOrNull() ?: return emptyList()
        return json.optJSONArray("suggestions")?.toStringList().orEmpty()
    }

    private fun extractMessageContentString(raw: String): String? {
        val messageIndex = raw.indexOf("\"message\"")
        if (messageIndex < 0) return null
        return extractFirstStringField(raw.substring(messageIndex), "content")
    }

    private fun extractStructuredTextContent(raw: String): String? {
        val parts = STRUCTURED_TEXT_REGEX.findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map(::decodeJsonString)
            .filter { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .toList()
        return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = "")
    }

    private fun extractFirstStringField(raw: String, fieldName: String): String? {
        val fieldRegex = Regex(""""$fieldName"\s*:\s*""")
        var searchIndex = 0
        while (true) {
            val match = fieldRegex.find(raw, searchIndex) ?: return null
            var valueIndex = match.range.last + 1
            while (valueIndex < raw.length && raw[valueIndex].isWhitespace()) valueIndex++
            if (valueIndex >= raw.length) return null
            when (raw[valueIndex]) {
                '"' -> return readJsonString(raw, valueIndex)
                'n' -> {
                    if (raw.startsWith("null", valueIndex)) {
                        searchIndex = valueIndex + 4
                        continue
                    }
                    return null
                }
                else -> {
                    searchIndex = valueIndex + 1
                }
            }
        }
    }

    private fun readJsonString(raw: String, quoteIndex: Int): String? {
        var index = quoteIndex + 1
        val out = StringBuilder()
        while (index < raw.length) {
            when (val ch = raw[index++]) {
                '\\' -> {
                    if (index >= raw.length) return null
                    when (val escaped = raw[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > raw.length) return null
                            val hex = raw.substring(index, index + 4)
                            hex.toIntOrNull(16)?.let { out.append(it.toChar()) } ?: return null
                            index += 4
                        }
                        else -> out.append(escaped)
                    }
                }
                '"' -> {
                    val value = out.toString().trim()
                    return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                }
                else -> out.append(ch)
            }
        }
        return null
    }

    private fun decodeJsonString(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            when (val ch = raw[index++]) {
                '\\' -> {
                    if (index >= raw.length) break
                    when (val escaped = raw[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 <= raw.length) {
                                val hex = raw.substring(index, index + 4)
                                hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                                index += 4
                            }
                        }
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private companion object {
        val STRUCTURED_TEXT_REGEX = Regex(
            """"type"\s*:\s*"text"[\s\S]*?"text"\s*:\s*"((?:\\.|[^"\\])*)""""
        )
    }
}

/** Keeps the last working endpoint, or the next endpoint after an error shown to the user.
 * Settings changes reset the preference; no failure or key state is persisted to disk. */
internal class LlmEndpointFailoverState {
    private var configured: List<LlmPrefs.RemoteEndpoint>? = null
    private var preferredBaseUrl: String? = null
    private val deferredFailures = mutableSetOf<String>()

    @Synchronized
    fun ordered(endpoints: List<LlmPrefs.RemoteEndpoint>): List<LlmPrefs.RemoteEndpoint> {
        if (configured != endpoints) {
            configured = endpoints.toList()
            preferredBaseUrl = null
            deferredFailures.clear()
        }
        val first = endpoints.indexOfFirst { it.baseUrl == preferredBaseUrl }.takeIf { it >= 0 } ?: 0
        return endpoints.drop(first) + endpoints.take(first)
    }

    @Synchronized
    fun succeeded(endpoints: List<LlmPrefs.RemoteEndpoint>, baseUrl: String) {
        if (configured == endpoints && baseUrl !in deferredFailures) preferredBaseUrl = baseUrl
    }

    @Synchronized
    fun deferNext(endpoints: List<LlmPrefs.RemoteEndpoint>, failedBaseUrl: String): Boolean {
        if (configured != endpoints || endpoints.size < 2) return false
        val index = endpoints.indexOfFirst { it.baseUrl == failedBaseUrl }
        if (index < 0) return false
        deferredFailures.add(failedBaseUrl)
        preferredBaseUrl = endpoints[(index + 1) % endpoints.size].baseUrl
        return true
    }
}

/** Network/service failures try the next endpoint now. Credential, quota and rate-limit
 * failures surface to the user first, then start from the next endpoint on the next request. */
internal suspend fun predictWithEndpointFailover(
    request: LlmClient.PredictionRequest,
    onPartialText: ((String) -> Unit)?,
    state: LlmEndpointFailoverState = LlmEndpointFailoverState(),
    predictAtEndpoint: (LlmClient.PredictionRequest, ((String) -> Unit)?) -> LlmClient.PredictionResponse,
): LlmClient.PredictionResponse {
    val context = currentCoroutineContext()
    val endpoints = request.config.remoteEndpoints
    val ordered = endpoints.takeIf { it.isNotEmpty() }?.let(state::ordered).orEmpty()
    val configs = ordered.map { endpoint ->
        request.config.copy(
            baseUrl = endpoint.baseUrl,
            model = endpoint.model,
            apiKey = endpoint.apiKey,
            remoteEndpoints = emptyList(),
        )
    }.ifEmpty { listOf(request.config) }
    var lastFailure: LlmPredictionFailure? = null
    for ((index, config) in configs.withIndex()) {
        context.ensureActive()
        var partialEmitted = false
        val partialCallback = onPartialText?.let { callback ->
            { partial: String ->
                context.ensureActive()
                partialEmitted = true
                callback(partial)
            }
        }
        try {
            val response = predictAtEndpoint(request.copy(config = config), partialCallback)
            if (ordered.isNotEmpty()) state.succeeded(endpoints, config.baseUrl)
            return response
        } catch (failure: LlmPredictionFailure) {
            // These failures are displayed through the existing prediction error UI.
            // A separate credential or balance on the next endpoint may still work.
            if (failure.kind in setOf(
                    LlmPredictionFailure.Kind.AUTHENTICATION,
                    LlmPredictionFailure.Kind.BILLING_OR_QUOTA,
                    LlmPredictionFailure.Kind.RATE_LIMIT,
                )) {
                context.ensureActive()
                failure.willSwitchEndpointOnNextRequest =
                    ordered.isNotEmpty() && state.deferNext(endpoints, config.baseUrl)
                throw failure
            }
            lastFailure = failure
        }
        context.ensureActive()
        if (index < configs.lastIndex && partialEmitted) {
            // Streaming callbacks are snapshots: don't mix the two responses.
            onPartialText?.invoke("")
        }
    }
    throw (lastFailure ?: LlmPredictionFailure.invalidResponse())
}
