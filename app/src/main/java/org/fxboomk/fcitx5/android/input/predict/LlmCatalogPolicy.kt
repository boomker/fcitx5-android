package org.fxboomk.fcitx5.android.input.predict

internal object LlmCatalogPolicy {
    fun builtInModelsForProvider(provider: LlmPrefs.Provider): List<LlmCatalogClient.RemoteModel> = when (provider) {
        LlmPrefs.Provider.MiniMax -> listOf(
            LlmCatalogClient.RemoteModel("MiniMax-M2.7"),
            LlmCatalogClient.RemoteModel("MiniMax-M2.7-highspeed"),
            LlmCatalogClient.RemoteModel("MiniMax-M2.5"),
            LlmCatalogClient.RemoteModel("MiniMax-M2.5-highspeed"),
            LlmCatalogClient.RemoteModel("MiniMax-M2.1"),
            LlmCatalogClient.RemoteModel("MiniMax-M2.1-highspeed"),
            LlmCatalogClient.RemoteModel("MiniMax-M2"),
        )

        else -> emptyList()
    }

    fun connectivityProbeModel(config: LlmPrefs.Config): String =
        config.model.ifBlank { LlmPrefs.providerDefaultModel(config.provider) }

    fun shouldProbeChatEndpoint(statusCode: Int, responseBody: String): Boolean {
        if (statusCode !in listOf(404, 405, 501)) return false
        val body = responseBody.lowercase()
        return body.isBlank() ||
            "not found" in body ||
            "404" in body ||
            "unsupported" in body ||
            "no route" in body ||
            "cannot get" in body
    }

    fun shouldFallbackToNextEndpoint(statusCode: Int, responseBody: String): Boolean {
        if (statusCode !in listOf(400, 404, 405, 501)) return false
        val body = responseBody.lowercase()
        return body.isBlank() ||
            "not found" in body ||
            "404" in body ||
            "unsupported" in body ||
            "unsupported parameter" in body ||
            "no route" in body ||
            "cannot get" in body ||
            "unknown route" in body ||
            "unknown parameter" in body ||
            "unexpected field" in body ||
            "invalid request" in body ||
            "invalid value" in body
    }

    fun recoverModelListing(
        config: LlmPrefs.Config,
        fetchGeminiNativeModels: () -> List<LlmCatalogClient.RemoteModel>,
        probeChatEndpoint: () -> Unit,
    ): List<LlmCatalogClient.RemoteModel>? = when (config.provider) {
        LlmPrefs.Provider.Gemini -> fetchGeminiNativeModels()
        LlmPrefs.Provider.MiniMax -> {
            probeChatEndpoint()
            builtInModelsForProvider(config.provider)
        }

        else -> null
    }
}
