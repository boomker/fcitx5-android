package org.fxboomk.fcitx5.android.input.predict

import java.net.URI

internal object LlmProviderProfile {
    fun messagesEndpoint(config: LlmPrefs.Config): String =
        joinPath(anthropicRoot(config), "/messages")

    fun modelsCompatEndpoints(config: LlmPrefs.Config): List<String> =
        when (config.compatApi) {
            LlmPrefs.CompatApi.OpenAI -> openAiRoots(config).map { root -> joinPath(root, "/models") }.distinct()
            LlmPrefs.CompatApi.Anthropic -> anthropicRoots(config).map { root -> joinPath(root, "/models") }.distinct()
        }

    fun chatCompatEndpoints(config: LlmPrefs.Config): List<String> =
        when (config.compatApi) {
            LlmPrefs.CompatApi.OpenAI -> openAiRoots(config).map { root -> joinPath(root, "/chat/completions") }.distinct()
            LlmPrefs.CompatApi.Anthropic -> anthropicRoots(config).map { root -> joinPath(root, "/messages") }.distinct()
        }

    private fun openAiRoot(config: LlmPrefs.Config): String =
        trimKnownSuffix(
            config.resolvedBaseUrl,
            listOf("/chat/completions", "/models", "/completion", "/responses", "/api/generate"),
        )

    private fun openAiRoots(config: LlmPrefs.Config): List<String> {
        val root = openAiRoot(config)
        return if (
            config.provider == LlmPrefs.Provider.Custom &&
            config.compatApi == LlmPrefs.CompatApi.OpenAI &&
            hasBareHostPath(root)
        ) {
            listOf(
                joinPrefix(root, "/v1"),
                joinPrefix(root, "/api"),
                joinPrefix(root, "/v1/api"),
                root,
            ).distinct()
        } else {
            listOf(root)
        }
    }

    private fun anthropicRoot(config: LlmPrefs.Config): String =
        trimKnownSuffix(
            config.resolvedBaseUrl,
            listOf("/messages", "/models", "/chat/completions", "/completion", "/responses", "/api/generate"),
        )

    private fun anthropicRoots(config: LlmPrefs.Config): List<String> =
        when (config.provider) {
            LlmPrefs.Provider.MiniMax -> miniMaxAnthropicRoots(config)
            else -> listOf(anthropicRoot(config))
        }

    private fun miniMaxAnthropicRoots(config: LlmPrefs.Config): List<String> {
        val root = anthropicRoot(config)
        if (root.isBlank()) return emptyList()
        val uri = runCatching { URI(root) }.getOrNull() ?: return listOf(root)
        val normalizedRoot = trimKnownSuffix(root, listOf("/v1"))
        val host = uri.host.orEmpty().lowercase()
        val alternateHost = when (host) {
            "api.minimaxi.com" -> "api.minimax.io"
            "api.minimax.io" -> "api.minimaxi.com"
            else -> null
        } ?: return listOf(normalizedRoot)
        val alternateRoot = URI(
            uri.scheme,
            uri.userInfo,
            alternateHost,
            uri.port,
            URI(normalizedRoot).path,
            uri.query,
            uri.fragment,
        ).toString().removeSuffix("/")
        return listOf(normalizedRoot, alternateRoot)
            .map { joinPath(it, "/v1") }
            .distinct()
    }

    private fun trimKnownSuffix(raw: String, suffixes: List<String>): String {
        val normalized = raw.removeSuffix("/")
        val suffix = suffixes.firstOrNull { normalized.endsWith(it) } ?: return normalized
        return normalized.removeSuffix(suffix)
    }

    private fun hasBareHostPath(raw: String): Boolean {
        val path = runCatching { URI(raw).path.orEmpty() }.getOrDefault("")
        return path.isBlank() || path == "/"
    }

    private fun joinPrefix(root: String, prefix: String): String {
        if (root.isBlank()) return ""
        return root.removeSuffix("/") + prefix
    }

    private fun joinPath(root: String, path: String): String {
        if (root.isBlank()) return ""
        return if (root.endsWith(path)) root else root.removeSuffix("/") + path
    }
}
