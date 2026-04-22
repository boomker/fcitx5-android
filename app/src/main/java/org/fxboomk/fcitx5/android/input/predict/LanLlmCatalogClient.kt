package org.fxboomk.fcitx5.android.input.predict

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LanLlmCatalogClient {
    companion object {
        internal fun builtInModelsForProvider(provider: LanLlmPrefs.Provider): List<RemoteModel> = when (provider) {
            LanLlmPrefs.Provider.MiniMax -> listOf(
                RemoteModel("MiniMax-M2.7"),
                RemoteModel("MiniMax-M2.7-highspeed"),
                RemoteModel("MiniMax-M2.5"),
                RemoteModel("MiniMax-M2.5-highspeed"),
                RemoteModel("MiniMax-M2.1"),
                RemoteModel("MiniMax-M2.1-highspeed"),
                RemoteModel("MiniMax-M2"),
            )
            else -> emptyList()
        }

        internal fun connectivityProbeModel(config: LanLlmPrefs.Config): String =
            config.model.ifBlank { LanLlmPrefs.providerDefaultModel(config.provider) }
    }

    private class CatalogRequestFailure(
        val statusCode: Int,
        val responseBody: String,
    ) : IllegalStateException(responseBody.ifBlank { "HTTP $statusCode" })

    data class RemoteModel(
        val id: String,
        val displayName: String = id,
    ) {
        val providerPrefix: String?
            get() = id.substringBefore('/').takeIf { '/' in id && it.isNotBlank() }
    }

    data class ConnectivityResult(
        val endpoint: String,
        val modelCount: Int,
    )

    suspend fun checkConnectivity(config: LanLlmPrefs.Config): ConnectivityResult {
        if (config.provider == LanLlmPrefs.Provider.MiniMax) {
            probeChatEndpoint(config)
            return ConnectivityResult(
                endpoint = config.chatEndpoint,
                modelCount = builtInModelsForProvider(config.provider).size,
            )
        }

        val modelListing = runCatching { fetchModels(config) }
        modelListing.getOrNull()?.let { models ->
            return ConnectivityResult(
                endpoint = config.modelsEndpoint,
                modelCount = models.size,
            )
        }

        val failure = modelListing.exceptionOrNull()
        if (failure is CatalogRequestFailure && shouldProbeChatEndpoint(failure)) {
            probeChatEndpoint(config)
            return ConnectivityResult(
                endpoint = config.chatEndpoint,
                modelCount = 0,
            )
        }

        throw (failure ?: IllegalStateException("连通性检测失败"))
    }

    suspend fun fetchModels(config: LanLlmPrefs.Config): List<RemoteModel> = withContext(Dispatchers.IO) {
        var lastFailure: Throwable? = null
        val models = runCatching {
            for ((index, endpoint) in config.modelsCompatEndpoints.withIndex()) {
                try {
                    return@runCatching fetchModelsFromEndpoint(endpoint, config)
                } catch (failure: CatalogRequestFailure) {
                    lastFailure = failure
                    val canFallback = index < config.modelsCompatEndpoints.lastIndex &&
                        shouldFallbackToNextEndpoint(failure)
                    if (!canFallback) throw failure
                }
            }
            throw (lastFailure ?: IllegalStateException("模型列表地址为空"))
        }.recoverCatching {
            if (config.provider == LanLlmPrefs.Provider.Gemini) {
                fetchGeminiNativeModels(config.apiKey)
            } else if (config.provider == LanLlmPrefs.Provider.MiniMax) {
                probeChatEndpoint(config)
                builtInModelsForProvider(config.provider)
            } else {
                throw it
            }
        }
            .getOrThrow()

        models
            .distinctBy { it.id }
            .sortedWith(
                compareBy<RemoteModel> { it.providerPrefix.orEmpty().lowercase() }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.id.lowercase() }
            )
    }

    private fun fetchModelsFromEndpoint(
        endpoint: String,
        config: LanLlmPrefs.Config,
    ): List<RemoteModel> {
        if (endpoint.isBlank()) error("模型列表地址为空")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            when (config.compatApi) {
                LanLlmPrefs.CompatApi.OpenAI -> {
                    if (config.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                }
                LanLlmPrefs.CompatApi.Anthropic -> {
                    if (config.apiKey.isNotBlank()) {
                        setRequestProperty("x-api-key", config.apiKey)
                    }
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
        }
        return readModelResponse(connection)
    }

    private fun fetchGeminiNativeModels(apiKey: String): List<RemoteModel> {
        if (apiKey.isBlank()) error("Gemini API Key 为空")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models?key=" +
            URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        return readModelResponse(connection)
    }

    private fun readModelResponse(connection: HttpURLConnection): List<RemoteModel> {
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }.orEmpty()
        if (responseCode !in 200..299) {
            throw CatalogRequestFailure(responseCode, responseBody)
        }

        return parseModels(responseBody)
    }

    private fun shouldProbeChatEndpoint(failure: CatalogRequestFailure): Boolean {
        if (failure.statusCode !in listOf(404, 405, 501)) return false
        val body = failure.responseBody.lowercase()
        return body.isBlank() ||
            "not found" in body ||
            "404" in body ||
            "unsupported" in body ||
            "no route" in body ||
            "cannot get" in body
    }

    private fun shouldFallbackToNextEndpoint(failure: CatalogRequestFailure): Boolean {
        if (failure.statusCode !in listOf(400, 404, 405, 501)) return false
        val body = failure.responseBody.lowercase()
        return body.isBlank() ||
            "not found" in body ||
            "404" in body ||
            "unsupported" in body ||
            "no route" in body ||
            "cannot get" in body ||
            "unknown route" in body ||
            "invalid request" in body
    }

    private fun probeChatEndpoint(config: LanLlmPrefs.Config) {
        if (config.chatEndpoint.isBlank()) error("聊天接口地址为空")
        val probeModel = connectivityProbeModel(config)
        if (probeModel.isBlank()) error("当前未填写模型名称，无法执行连通性检测")

        val payload = JSONObject()
            .put("model", probeModel)
            .put("stream", false)
            .put("max_tokens", 1)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "ping")
                    )
            )

        val connection = (URL(config.chatEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            when (config.compatApi) {
                LanLlmPrefs.CompatApi.OpenAI -> {
                    if (config.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                }
                LanLlmPrefs.CompatApi.Anthropic -> {
                    if (config.apiKey.isNotBlank()) {
                        setRequestProperty("x-api-key", config.apiKey)
                    }
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
        }
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
        if (responseCode in 200..299) return

        throw IllegalStateException(responseBody.ifBlank { "HTTP $responseCode" })
    }

    private fun parseModels(responseBody: String): List<RemoteModel> {
        val root = runCatching { JSONObject(responseBody) }.getOrNull()
            ?: throw IllegalStateException("无法解析模型列表响应")

        root.optJSONArray("data")?.let { data ->
            return parseModelArray(data)
        }
        root.optJSONArray("models")?.let { models ->
            return parseModelArray(models)
        }

        throw IllegalStateException("响应中未包含可用模型列表")
    }

    private fun parseModelArray(array: JSONArray): List<RemoteModel> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val rawId = item.optString("id").ifBlank {
                item.optString("name")
            }
            val id = rawId.trim()
            if (id.isBlank()) continue
            val displayName = item.optString("display_name")
                .ifBlank { item.optString("displayName") }
                .ifBlank { id.substringAfterLast('/').trim() }
                .ifBlank { id }
            add(RemoteModel(id = id, displayName = displayName))
        }
    }
}
