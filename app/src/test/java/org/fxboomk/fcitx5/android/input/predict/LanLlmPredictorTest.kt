/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanLlmPredictorTest {

    @Test
    fun requestTrackerOnlyKeepsLatestRequestActive() {
        val tracker = LanLlmPredictor.RequestTracker()

        val first = tracker.replaceActiveRequest()
        val second = tracker.replaceActiveRequest()

        assertFalse(tracker.isActive(first))
        assertTrue(tracker.isActive(second))
    }

    @Test
    fun requestTrackerInvalidationDropsCurrentRequest() {
        val tracker = LanLlmPredictor.RequestTracker()

        val requestId = tracker.replaceActiveRequest()
        tracker.invalidate()

        assertFalse(tracker.isActive(requestId))
    }

    @Test
    fun optimizedLocalMaxOutputTokensClampsSuggestionRequests() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxOutputTokens = 512,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        val maxTokens = optimizedLocalMaxOutputTokens(
            config = config,
            request = LanLlmPredictor.Request(beforeCursor = "你好"),
        )

        assertTrue(maxTokens < config.maxOutputTokens)
        assertTrue(maxTokens == LOCAL_SUGGESTION_MAX_OUTPUT_TOKENS)
    }

    @Test
    fun shouldStopLocalGenerationWhenEnoughSuggestionsArrive() {
        val request = LocalLanLlmPredictionRequest(
            modelPath = "/tmp/model.onnx",
            companionDirectory = "/tmp",
            beforeCursor = "你好",
            recentCommittedText = "",
            historyText = "",
            maxPredictionCandidates = 2,
            maxOutputTokens = 96,
            outputMode = LanLlmOutputMode.Suggestions,
            taskMode = LanLlmTaskMode.Completion,
            enableThinking = false,
        )

        assertTrue(
            shouldStopLocalGeneration(
                request,
                """{"suggestions":["好的呀","没问题"]}""",
            )
        )
    }
}
