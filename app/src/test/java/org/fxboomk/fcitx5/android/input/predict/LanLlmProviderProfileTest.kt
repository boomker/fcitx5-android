/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class LanLlmProviderProfileTest {
    @Test
    fun customOpenAiBareHostExpandsKnownCompatibleEndpoints() {
        val config = config(
            provider = LanLlmPrefs.Provider.Custom,
            baseUrl = "http://127.0.0.1:11434",
        )

        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/chat/completions",
                "http://127.0.0.1:11434/api/chat/completions",
                "http://127.0.0.1:11434/v1/api/chat/completions",
                "http://127.0.0.1:11434/chat/completions",
            ),
            LanLlmProviderProfile.chatCompatEndpoints(config),
        )
        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/models",
                "http://127.0.0.1:11434/api/models",
                "http://127.0.0.1:11434/v1/api/models",
                "http://127.0.0.1:11434/models",
            ),
            LanLlmProviderProfile.modelsCompatEndpoints(config),
        )
    }

    @Test
    fun miniMaxProfileNormalizesAnthropicRootsWithoutDuplicatingV1() {
        val config = config(
            provider = LanLlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/anthropic/v1",
            model = "MiniMax-M2.7",
        )

        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/messages",
                "https://api.minimax.io/anthropic/v1/messages",
            ),
            LanLlmProviderProfile.chatCompatEndpoints(config),
        )
        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/models",
                "https://api.minimax.io/anthropic/v1/models",
            ),
            LanLlmProviderProfile.modelsCompatEndpoints(config),
        )
    }

    private fun config(
        provider: LanLlmPrefs.Provider,
        baseUrl: String,
        model: String = "local-model",
    ): LanLlmPrefs.Config = LanLlmPrefs.Config(
        enabled = true,
        backend = LanLlmPrefs.Backend.ChatCompletions,
        provider = provider,
        baseUrl = baseUrl,
        model = model,
        apiKey = "",
        debounceMs = 450,
        sampleCount = 1,
        maxContextChars = 64,
        preferLastCommit = true,
    )
}
