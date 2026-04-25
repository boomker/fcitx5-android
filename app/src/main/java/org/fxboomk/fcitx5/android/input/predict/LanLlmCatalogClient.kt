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
            val models = runCatching { fetchModels(config) }.getOrNull()
            if (models != null) {
                return ConnectivityResult(
                    endpoint = config.modelsEndpoint,
                    modelCount = models.size,
                )
            }

            probeChatEndpoint(config)
            return ConnectivityResult(
                endpoint = config.chatEndpoint,
                modelCount = LanLlmCatalogPolicy.builtInModelsForProvider(config.provider).size,
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
        if (
            failure is CatalogRequestFailure &&
            LanLlmCatalogPolicy.shouldProbeChatEndpoint(failure.statusCode, failure.responseBody)
        ) {
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
                        LanLlmCatalogPolicy.shouldFallbackToNextEndpoint(failure.statusCode, failure.responseBody)
                    if (!canFallback) throw failure
                }
            }
            throw (lastFailure ?: IllegalStateException("模型列表地址为空"))
        }.recoverCatching {
            LanLlmCatalogPolicy.recoverModelListing(
                config = config,
                fetchGeminiNativeModels = { fetchGeminiNativeModels(config.apiKey) },
                probeChatEndpoint = { probeChatEndpoint(config) },
            ) ?: throw it
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

    private fun probeChatEndpoint(config: LanLlmPrefs.Config) {
        if (config.chatEndpoint.isBlank()) error("聊天接口地址为空")
        val probeModel = LanLlmCatalogPolicy.connectivityProbeModel(config)
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
        val variants = LanLlmRequestPolicy.variants(
            config = config,
            endpoint = config.chatEndpoint,
            enableThinking = false,
        )

        var lastFailure: CatalogRequestFailure? = null
        for ((index, variant) in variants.withIndex()) {
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
                output.write(
                    LanLlmRequestPolicy.applyAugmentation(
                        config = config,
                        payload = payload,
                        augmentation = variant.augmentation,
                    )
                        .toString()
                        .toByteArray(StandardCharsets.UTF_8)
                )
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()
            if (responseCode in 200..299) return

            val failure = CatalogRequestFailure(responseCode, responseBody)
            lastFailure = failure
            val canFallback = index < variants.lastIndex &&
                LanLlmCatalogPolicy.shouldFallbackToNextEndpoint(failure.statusCode, failure.responseBody)
            if (!canFallback) {
                throw IllegalStateException(responseBody.ifBlank { "HTTP $responseCode" })
            }
        }

        val failure = lastFailure
        val message = if (failure != null) {
            failure.responseBody.ifBlank { "HTTP ${failure.statusCode}" }
        } else {
            "连通性检测失败"
        }
        throw IllegalStateException(message)
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
