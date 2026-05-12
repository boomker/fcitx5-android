/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCatalogPolicyTest {
    @Test
    fun miniMaxBuiltInCatalogMatchesOfficialSupportedModels() {
        val models = LlmCatalogPolicy.builtInModelsForProvider(LlmPrefs.Provider.MiniMax)

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
    }

    @Test
    fun connectivityProbeModelFallsBackToProviderDefault() {
        val config = LlmPrefs.Config(
            enabled = true,
            backend = LlmPrefs.Backend.ChatCompletions,
            provider = LlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/anthropic",
            model = "",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("MiniMax-M2.7", LlmCatalogPolicy.connectivityProbeModel(config))
    }

    @Test
    fun probeAndFallbackHeuristicsRemainNarrow() {
        assertTrue(LlmCatalogPolicy.shouldProbeChatEndpoint(404, "unsupported route"))
        assertTrue(LlmCatalogPolicy.shouldFallbackToNextEndpoint(400, "unknown parameter think"))
        assertFalse(LlmCatalogPolicy.shouldProbeChatEndpoint(401, "unauthorized"))
        assertFalse(LlmCatalogPolicy.shouldFallbackToNextEndpoint(500, "server exploded"))
    }
}
