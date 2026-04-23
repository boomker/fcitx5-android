package org.fxboomk.fcitx5.android.input.predict

internal object LanLlmCatalogPolicy {
    fun builtInModelsForProvider(provider: LanLlmPrefs.Provider): List<LanLlmCatalogClient.RemoteModel> = when (provider) {
        LanLlmPrefs.Provider.MiniMax -> listOf(
            LanLlmCatalogClient.RemoteModel("MiniMax-M2.7"),
            LanLlmCatalogClient.RemoteModel("MiniMax-M2.7-highspeed"),
            LanLlmCatalogClient.RemoteModel("MiniMax-M2.5"),
            LanLlmCatalogClient.RemoteModel("MiniMax-M2.5-highspeed"),
            LanLlmCatalogClient.RemoteModel("MiniMax-M2.1"),
            LanLlmCatalogClient.RemoteModel("MiniMax-M2.1-highspeed"),
            LanLlmCatalogClient.RemoteModel("MiniMax-M2"),
        )

        else -> emptyList()
    }

    fun connectivityProbeModel(config: LanLlmPrefs.Config): String =
        config.model.ifBlank { LanLlmPrefs.providerDefaultModel(config.provider) }

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
        config: LanLlmPrefs.Config,
        fetchGeminiNativeModels: () -> List<LanLlmCatalogClient.RemoteModel>,
        probeChatEndpoint: () -> Unit,
    ): List<LanLlmCatalogClient.RemoteModel>? = when (config.provider) {
        LanLlmPrefs.Provider.Gemini -> fetchGeminiNativeModels()
        LanLlmPrefs.Provider.MiniMax -> {
            probeChatEndpoint()
            builtInModelsForProvider(config.provider)
        }

        else -> null
    }
}
