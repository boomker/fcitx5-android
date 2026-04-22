package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LanLlmCatalogClientTest {

    @Test
    fun remoteModelExposesProviderPrefixWithoutStrippingFullId() {
        val prefixed = LanLlmCatalogClient.RemoteModel(
            id = "xai/grok-4-1-fast-non-reasoning",
            displayName = "grok-4-1-fast-non-reasoning",
        )
        val local = LanLlmCatalogClient.RemoteModel(
            id = "qwen3-1.7b-4bit",
            displayName = "qwen3-1.7b-4bit",
        )

        assertEquals("xai/grok-4-1-fast-non-reasoning", prefixed.id)
        assertEquals("grok-4-1-fast-non-reasoning", prefixed.displayName)
        assertEquals("xai", prefixed.providerPrefix)

        assertEquals("qwen3-1.7b-4bit", local.id)
        assertEquals("qwen3-1.7b-4bit", local.displayName)
        assertNull(local.providerPrefix)
    }

    @Test
    fun minimaxBuiltInCatalogMatchesOfficialSupportedModels() {
        val models = LanLlmCatalogClient.builtInModelsForProvider(LanLlmPrefs.Provider.MiniMax)

        assertEquals(
            listOf(
                "MiniMax-M2.7",
                "MiniMax-M2.7-highspeed",
                "MiniMax-M2.5",
                "MiniMax-M2.5-highspeed",
                "MiniMax-M2.1",
                "MiniMax-M2.1-highspeed",
                "MiniMax-M2",
            ),
            models.map { it.id },
        )
        assertTrue(models.all { it.displayName == it.id })
    }

    @Test
    fun connectivityProbeModelFallsBackToProviderDefault() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            provider = LanLlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/v1",
            model = "",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("MiniMax-M2.7", LanLlmCatalogClient.connectivityProbeModel(config))
    }
}
