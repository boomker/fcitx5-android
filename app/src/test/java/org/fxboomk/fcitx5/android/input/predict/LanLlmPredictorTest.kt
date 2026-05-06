/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    @Test
    fun optimizedLocalContextPayloadDropsHistoryForCompletionSuggestions() {
        val payload = optimizedLocalContextPayload(
            LanLlmPredictor.Request(
                beforeCursor = "你好",
                recentCommittedText = "这是最近一次上屏的很长内容，用来验证本地建议请求会裁剪上下文",
                historyText = "这里是一大段历史上下文，当前本地建议模式不应该继续把它塞进 prompt",
            )
        )

        assertEquals("", payload.historyText)
        assertEquals("上屏的很长内容，用来验证本地建议请求会裁剪上下文", payload.recentCommittedText)
    }

    @Test
    fun optimizedLocalMaxOutputTokensGuaranteesExpandedBudgetForQuestionAnswer() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxOutputTokens = 256,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        val maxTokens = optimizedLocalMaxOutputTokens(
            config = config,
            request = LanLlmPredictor.Request(
                beforeCursor = "帮我回一句话",
                taskMode = LanLlmTaskMode.QuestionAnswer,
            ),
        )

        assertEquals(FULL_TEXT_MODE_MIN_OUTPUT_TOKENS, maxTokens)
    }
}
