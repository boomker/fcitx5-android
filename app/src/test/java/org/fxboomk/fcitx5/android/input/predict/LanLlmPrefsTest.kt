/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class LanLlmPrefsTest {

    @Test
    fun completionCompatEndpointsPrefersLlamaAndFallsBackToOllamaForBaseHost() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:11434",
            model = "scirime-ime",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf(
                "http://127.0.0.1:11434/completion",
                "http://127.0.0.1:11434/api/generate",
            ),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsKeepsExplicitCompletionEndpointOnly() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:8080/completion",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf("http://127.0.0.1:8080/completion"),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun completionCompatEndpointsKeepsExplicitOllamaGenerateEndpointOnly() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:11434/api/generate",
            model = "scirime-ime",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            listOf("http://127.0.0.1:11434/api/generate"),
            config.completionCompatEndpoints,
        )
    }

    @Test
    fun chatEndpointNormalizesFromExplicitCompletionEndpoint() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "http://127.0.0.1:8080/completion",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            config.chatEndpoint,
        )
    }

    @Test
    fun completionEndpointNormalizesFromExplicitChatEndpoint() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            backend = LanLlmPrefs.Backend.Completion,
            baseUrl = "http://127.0.0.1:11434/v1/chat/completions",
            model = "local",
            apiKey = "",
            debounceMs = 450,
            sampleCount = 4,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        assertEquals(
            "http://127.0.0.1:11434/completion",
            config.completionEndpoint,
        )
        assertEquals(
            "http://127.0.0.1:11434/api/generate",
            config.ollamaGenerateEndpoint,
        )
    }
}
