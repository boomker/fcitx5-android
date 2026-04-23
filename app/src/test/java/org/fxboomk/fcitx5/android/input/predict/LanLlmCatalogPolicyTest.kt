/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmCatalogPolicyTest {
    @Test
    fun miniMaxBuiltInCatalogMatchesOfficialSupportedModels() {
        val models = LanLlmCatalogPolicy.builtInModelsForProvider(LanLlmPrefs.Provider.MiniMax)

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
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            provider = LanLlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/anthropic",
            model = "",
            apiKey = "minimax-key",
            debounceMs = 450,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals("MiniMax-M2.7", LanLlmCatalogPolicy.connectivityProbeModel(config))
    }

    @Test
    fun probeAndFallbackHeuristicsRemainNarrow() {
        assertTrue(LanLlmCatalogPolicy.shouldProbeChatEndpoint(404, "unsupported route"))
        assertTrue(LanLlmCatalogPolicy.shouldFallbackToNextEndpoint(400, "unknown parameter think"))
        assertFalse(LanLlmCatalogPolicy.shouldProbeChatEndpoint(401, "unauthorized"))
        assertFalse(LanLlmCatalogPolicy.shouldFallbackToNextEndpoint(500, "server exploded"))
    }
}
