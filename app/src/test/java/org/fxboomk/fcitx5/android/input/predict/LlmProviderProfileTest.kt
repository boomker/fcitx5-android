/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmProviderProfileTest {
    @Test
    fun customOpenAiBareHostExpandsKnownCompatibleEndpoints() {
        val config = config(
            provider = LlmPrefs.Provider.Custom,
            baseUrl = "http://127.0.0.1:11434",
        )

        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/chat/completions",
                "http://127.0.0.1:11434/api/chat/completions",
                "http://127.0.0.1:11434/v1/api/chat/completions",
                "http://127.0.0.1:11434/chat/completions",
            ),
            LlmProviderProfile.chatCompatEndpoints(config),
        )
        assertEquals(
            listOf(
                "http://127.0.0.1:11434/v1/models",
                "http://127.0.0.1:11434/api/models",
                "http://127.0.0.1:11434/v1/api/models",
                "http://127.0.0.1:11434/models",
            ),
            LlmProviderProfile.modelsCompatEndpoints(config),
        )
    }

    @Test
    fun miniMaxProfileNormalizesAnthropicRootsWithoutDuplicatingV1() {
        val config = config(
            provider = LlmPrefs.Provider.MiniMax,
            baseUrl = "https://api.minimaxi.com/anthropic/v1",
            model = "MiniMax-M2.7",
        )

        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/messages",
                "https://api.minimax.io/anthropic/v1/messages",
            ),
            LlmProviderProfile.chatCompatEndpoints(config),
        )
        assertEquals(
            listOf(
                "https://api.minimaxi.com/anthropic/v1/models",
                "https://api.minimax.io/anthropic/v1/models",
            ),
            LlmProviderProfile.modelsCompatEndpoints(config),
        )
    }

    private fun config(
        provider: LlmPrefs.Provider,
        baseUrl: String,
        model: String = "local-model",
    ): LlmPrefs.Config = LlmPrefs.Config(
        enabled = true,
        backend = LlmPrefs.Backend.ChatCompletions,
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
