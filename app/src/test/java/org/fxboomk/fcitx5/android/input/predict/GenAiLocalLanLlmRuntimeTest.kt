/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenAiLocalLanLlmRuntimeTest {

    @Test
    fun localGenerationStopReasonPrefersControlTokens() {
        val request = baseRequest()

        assertEquals("control_token", localGenerationStopReason(request, "<|im_end|>"))
    }

    @Test
    fun localGenerationStopReasonDetectsEnoughSuggestions() {
        val request = baseRequest(maxPredictionCandidates = 2)

        assertEquals(
            "enough_suggestions",
            localGenerationStopReason(request, """{"suggestions":["好的呀","没问题"]}"""),
        )
    }

    @Test
    fun localGenerationStopReasonDetectsStructuredPayloadCompletion() {
        val request = baseRequest(maxPredictionCandidates = 4)

        assertEquals(
            "structured_payload_complete",
            localGenerationStopReason(request, """{"suggestions":["好的呀"]}"""),
        )
    }

    @Test
    fun formatLocalGenerationTelemetryIncludesDetailedFields() {
        val telemetry = GenAiLocalLanLlmRuntime.LocalGenerationTelemetry(
            provider = "CPU",
            intraOpThreads = 4,
            interOpThreads = 1,
            graphOptimizationLevel = "ORT_ENABLE_ALL",
            modelLoadMs = 12,
            modelCacheHit = true,
            tokenizerLoadMs = 3,
            tokenizerCacheHit = false,
            tokenizeMs = 8,
            inputTokenCount = 42,
            generatedTokenCount = 20,
            firstTokenLatencyMs = 50,
            generationDurationMs = 200,
            stopReason = "enough_suggestions",
        )

        val summary = formatLocalGenerationTelemetry(telemetry)

        assertTrue(summary.contains("provider=CPU"))
        assertTrue(summary.contains("intraOpThreads=4"))
        assertTrue(summary.contains("interOpThreads=1"))
        assertTrue(summary.contains("graphOptimizationLevel=ORT_ENABLE_ALL"))
        assertTrue(summary.contains("modelLoadMs=12"))
        assertTrue(summary.contains("modelCacheHit=true"))
        assertTrue(summary.contains("tokenizerLoadMs=3"))
        assertTrue(summary.contains("tokenizerCacheHit=false"))
        assertTrue(summary.contains("tokenizeMs=8"))
        assertTrue(summary.contains("generatedTokens=20"))
        assertTrue(summary.contains("firstTokenMs=50"))
        assertTrue(summary.contains("generateMs=200"))
        assertTrue(summary.contains("tokensPerSecond=100.00"))
        assertTrue(summary.contains("stopReason=enough_suggestions"))
    }

    @Test
    fun localSessionTuningClampsToFourThreads() {
        val tuning = localSessionTuning(availableProcessors = 8)

        assertEquals(4, tuning.intraOpThreads)
        assertEquals(1, tuning.interOpThreads)
        assertEquals("ORT_ENABLE_ALL", tuning.graphOptimizationLevel)
    }

    @Test
    fun buildSessionConfigOverlayEncodesThreadSettings() {
        val overlay = buildSessionConfigOverlay(
            LocalSessionTuning(
                intraOpThreads = 3,
                interOpThreads = 1,
                graphOptimizationLevel = "ORT_ENABLE_ALL",
            )
        )

        assertTrue(overlay.contains("\"intra_op_num_threads\": 3"))
        assertTrue(overlay.contains("\"inter_op_num_threads\": 1"))
        assertTrue(overlay.contains("\"graph_optimization_level\": \"ORT_ENABLE_ALL\""))
    }

    @Test
    fun localExecutionProviderCandidatesPreferXnnpackThenCpu() {
        val providers = localExecutionProviderCandidates()

        assertEquals(listOf("XNNPACK", "CPU"), providers.map { it.name })
        assertEquals(emptyMap<String, String>(), providers.first().options)
    }

    private fun baseRequest(
        maxPredictionCandidates: Int = 1,
    ): LocalLanLlmPredictionRequest = LocalLanLlmPredictionRequest(
        modelPath = "/tmp/model.onnx",
        companionDirectory = "/tmp",
        beforeCursor = "你好",
        recentCommittedText = "",
        historyText = "",
        maxPredictionCandidates = maxPredictionCandidates,
        maxOutputTokens = 96,
        outputMode = LanLlmOutputMode.Suggestions,
        taskMode = LanLlmTaskMode.Completion,
        enableThinking = false,
    )
}
