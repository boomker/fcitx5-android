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

class LanLlmClient {
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
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
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
            throw IllegalStateException("LAN LLM request failed: HTTP $responseCode ${responseBody.take(240)}")
        }

        val rawContent = extractContent(responseBody)
        val suggestions = LanLlmSuggestionParser.parse(rawContent, beforeCursor)
        Log.d(TAG, "parsed suggestions=$suggestions rawContent=${rawContent.take(240)}")
        return PredictionResponse(
            suggestions = suggestions,
            rawContent = rawContent,
        )
    }

    private fun predictChat(request: PredictionRequest): PredictionResponse {
        val payload = JSONObject()
            .put("model", request.config.model)
            .put("stream", false)
            .put("temperature", 0.1)
            .put("max_tokens", 18)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", LanLlmPrompt.systemPrompt())
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", LanLlmPrompt.userPrompt(request.beforeCursor))
                    )
            )
        return execute(
            endpoint = request.config.chatEndpoint,
            apiKey = request.config.apiKey,
            payload = payload,
            beforeCursor = request.beforeCursor,
            extractContent = ::extractMessageContent,
        )
    }

    private fun predictCompletion(request: PredictionRequest): PredictionResponse {
        val payload = JSONObject()
            .put("prompt", LanLlmPrompt.completionPrompt(
                beforeCursor = request.beforeCursor,
                recentCommittedText = request.recentCommittedText,
                historyText = request.historyText,
                useRecentCommitBias = request.useRecentCommitBias,
            ))
            .put("n_predict", 12)
            .put("temperature", 1.1)
            .put("top_k", 50)
            .put("top_p", 0.95)
            .put("min_p", 0.02)
            .put("repeat_penalty", 1.05)
            .put("cache_prompt", true)
            .put(
                "stop",
                JSONArray()
                    .put("<|im_end|>")
                    .put("<")
                    .put("\n")
                    .put("。")
                    .put("！")
                    .put("？")
                    .put("，")
                    .put(",")
            )
        request.seed?.let { payload.put("seed", it) }
        return execute(
            endpoint = request.config.completionEndpoint,
            apiKey = request.config.apiKey,
            payload = payload,
            beforeCursor = request.beforeCursor,
            extractContent = ::extractCompletionContent,
        )
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

    private fun extractCompletionContent(responseBody: String): String {
        val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return responseBody
        return root.optString("content").ifBlank {
            root.optString("response").ifBlank { responseBody }
        }
    }
}
