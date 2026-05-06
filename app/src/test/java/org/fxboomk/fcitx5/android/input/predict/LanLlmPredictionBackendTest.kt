/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class LanLlmPredictionBackendTest {

    @Test
    fun selectPredictionBackendChoosesLocalWhenRuntimeIsLocal() {
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )
        val remote = FakeBackend("remote")
        val local = FakeBackend("local")

        val selected = selectPredictionBackend(config, remote, local)

        assertSame(local, selected)
    }

    @Test
    fun localBackendReturnsRuntimeSuggestions() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(
            predictions = listOf("候选一", "候选二", "候选三")
        )
        val backend = LocalLanLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLanLlmModelStore {
                override fun currentModel(context: android.content.Context): LanLlmLocalModelManager.InstalledModel? =
                    LanLlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LanLlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LanLlmLocalModelManager.CompatibilityInfo(
                            state = LanLlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLanLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LanLlmLocalResourceManager.ResourceBundle =
                    LanLlmLocalResourceManager.ResourceBundle(
                        directory = java.io.File("/tmp/runtime-bundle"),
                        model = modelFile,
                        tokenizer = java.io.File("/tmp/runtime-bundle/tokenizer.json"),
                        tokenizerConfig = java.io.File("/tmp/runtime-bundle/tokenizer_config.json"),
                        modelConfig = java.io.File("/tmp/runtime-bundle/config.json"),
                        genAiConfig = java.io.File("/tmp/runtime-bundle/genai_config.json"),
                    )
            },
            appContext = context,
        )
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
            maxPredictionCandidates = 2,
        )

        val response = runBlocking {
            backend.predict(
                config = config,
                request = LanLlmPredictor.Request(beforeCursor = "你好"),
            )
        }

        assertEquals(listOf("候选一", "候选二"), response.suggestions)
        assertEquals(LOCAL_SUGGESTION_MAX_OUTPUT_TOKENS, runtime.lastPredictRequest?.maxOutputTokens)
        assertEquals(LanLlmOutputMode.Suggestions, runtime.lastPredictRequest?.outputMode)
        assertEquals(LanLlmTaskMode.Completion, runtime.lastPredictRequest?.taskMode)
        assertFalse(runtime.lastPredictRequest?.enableThinking ?: true)
    }

    @Test
    fun localBackendNormalizesPredictionCandidateLimitBeforeRuntimeAndFinalTake() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(
            predictions = (1..10).map { "候选$it" }
        )
        val backend = LocalLanLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLanLlmModelStore {
                override fun currentModel(context: android.content.Context): LanLlmLocalModelManager.InstalledModel? =
                    LanLlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LanLlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LanLlmLocalModelManager.CompatibilityInfo(
                            state = LanLlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLanLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LanLlmLocalResourceManager.ResourceBundle =
                    LanLlmLocalResourceManager.ResourceBundle(
                        directory = java.io.File("/tmp/runtime-bundle"),
                        model = modelFile,
                        tokenizer = java.io.File("/tmp/runtime-bundle/tokenizer.json"),
                        tokenizerConfig = java.io.File("/tmp/runtime-bundle/tokenizer_config.json"),
                        modelConfig = java.io.File("/tmp/runtime-bundle/config.json"),
                        genAiConfig = java.io.File("/tmp/runtime-bundle/genai_config.json"),
                    )
            },
            appContext = context,
        )
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
            maxPredictionCandidates = 99,
        )

        val response = runBlocking {
            backend.predict(
                config = config,
                request = LanLlmPredictor.Request(beforeCursor = "你好"),
            )
        }

        assertEquals(MAX_PREDICTION_CANDIDATE_LIMIT, runtime.lastPredictRequest?.maxPredictionCandidates)
        assertEquals(MAX_PREDICTION_CANDIDATE_LIMIT, response.suggestions.size)
    }

    @Test
    fun localBackendRunsPredictionOffCallerThread() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(
            predictions = listOf("候选一")
        )
        val backend = LocalLanLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLanLlmModelStore {
                override fun currentModel(context: android.content.Context): LanLlmLocalModelManager.InstalledModel? =
                    LanLlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LanLlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LanLlmLocalModelManager.CompatibilityInfo(
                            state = LanLlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLanLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LanLlmLocalResourceManager.ResourceBundle =
                    LanLlmLocalResourceManager.ResourceBundle(
                        directory = java.io.File("/tmp/runtime-bundle"),
                        model = modelFile,
                        tokenizer = java.io.File("/tmp/runtime-bundle/tokenizer.json"),
                        tokenizerConfig = java.io.File("/tmp/runtime-bundle/tokenizer_config.json"),
                        modelConfig = java.io.File("/tmp/runtime-bundle/config.json"),
                        genAiConfig = java.io.File("/tmp/runtime-bundle/genai_config.json"),
                    )
            },
            appContext = context,
        )
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lan-llm-caller")
        }
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            runBlocking(dispatcher) {
                backend.predict(
                    config = config,
                    request = LanLlmPredictor.Request(beforeCursor = "你好"),
                )
            }
        } finally {
            dispatcher.close()
            executor.shutdown()
        }

        assertNotEquals("lan-llm-caller", runtime.predictThreadName)
    }

    @Test
    fun localBackendPrewarmUsesShortNonThinkingRequest() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(predictions = emptyList())
        val backend = LocalLanLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLanLlmModelStore {
                override fun currentModel(context: android.content.Context): LanLlmLocalModelManager.InstalledModel? =
                    LanLlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LanLlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LanLlmLocalModelManager.CompatibilityInfo(
                            state = LanLlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLanLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LanLlmLocalResourceManager.ResourceBundle =
                    LanLlmLocalResourceManager.ResourceBundle(
                        directory = java.io.File("/tmp/runtime-bundle"),
                        model = modelFile,
                        tokenizer = java.io.File("/tmp/runtime-bundle/tokenizer.json"),
                        tokenizerConfig = java.io.File("/tmp/runtime-bundle/tokenizer_config.json"),
                        modelConfig = java.io.File("/tmp/runtime-bundle/config.json"),
                        genAiConfig = java.io.File("/tmp/runtime-bundle/genai_config.json"),
                    )
            },
            appContext = context,
        )
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
        )

        backend.prewarm(config)

        assertEquals("你好", runtime.lastPrewarmRequest?.beforeCursor)
        assertEquals(8, runtime.lastPrewarmRequest?.maxOutputTokens)
        assertEquals(LanLlmOutputMode.Suggestions, runtime.lastPrewarmRequest?.outputMode)
        assertEquals(LanLlmTaskMode.Completion, runtime.lastPrewarmRequest?.taskMode)
        assertEquals(false, runtime.lastPrewarmRequest?.enableThinking)
    }

    @Test
    fun localBackendTrimsCompletionSuggestionContextForOnDevicePrompt() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(
            predictions = listOf("候选一")
        )
        val backend = LocalLanLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLanLlmModelStore {
                override fun currentModel(context: android.content.Context): LanLlmLocalModelManager.InstalledModel? =
                    LanLlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LanLlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LanLlmLocalModelManager.CompatibilityInfo(
                            state = LanLlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLanLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LanLlmLocalResourceManager.ResourceBundle =
                    LanLlmLocalResourceManager.ResourceBundle(
                        directory = java.io.File("/tmp/runtime-bundle"),
                        model = modelFile,
                        tokenizer = java.io.File("/tmp/runtime-bundle/tokenizer.json"),
                        tokenizerConfig = java.io.File("/tmp/runtime-bundle/tokenizer_config.json"),
                        modelConfig = java.io.File("/tmp/runtime-bundle/config.json"),
                        genAiConfig = java.io.File("/tmp/runtime-bundle/genai_config.json"),
                    )
            },
            appContext = context,
        )
        val config = LanLlmPrefs.Config(
            enabled = true,
            runtime = LanLlmPrefs.Runtime.LocalOnDevice,
            backend = LanLlmPrefs.Backend.ChatCompletions,
            baseUrl = "",
            model = "qwen3-local",
            apiKey = "",
            debounceMs = 200,
            sampleCount = 1,
            maxContextChars = 64,
            preferLastCommit = true,
            maxPredictionCandidates = 1,
        )

        runBlocking {
            backend.predict(
                config = config,
                request = LanLlmPredictor.Request(
                    beforeCursor = "今天天气真好",
                    recentCommittedText = "这是最近一次上屏的很长内容，用来验证本地建议请求会裁剪上下文",
                    historyText = "这里是一大段历史上下文，当前本地建议模式不应该继续把它塞进 prompt",
                ),
            )
        }

        assertEquals("", runtime.lastPredictRequest?.historyText)
        assertEquals(
            "上屏的很长内容，用来验证本地建议请求会裁剪上下文",
            runtime.lastPredictRequest?.recentCommittedText,
        )
    }

    private class FakeBackend(
        private val name: String,
    ) : LanLlmPredictionBackend {
        override suspend fun predict(
            config: LanLlmPrefs.Config,
            request: LanLlmPredictor.Request,
            onPartialText: ((String) -> Unit)?,
        ): LanLlmClient.PredictionResponse = LanLlmClient.PredictionResponse(
            suggestions = listOf(name),
            rawContent = name,
        )
    }

    private class RecordingRuntime(
        private val predictions: List<String>,
    ) : LocalLanLlmRuntime {
        var lastPredictRequest: LocalLanLlmPredictionRequest? = null
        var lastPrewarmRequest: LocalLanLlmPredictionRequest? = null
        var predictThreadName: String? = null

        override fun isAvailable(): Boolean = true

        override fun predict(request: LocalLanLlmPredictionRequest): List<String> {
            lastPredictRequest = request
            predictThreadName = Thread.currentThread().name
            return predictions
        }

        override fun prewarm(request: LocalLanLlmPredictionRequest) {
            lastPrewarmRequest = request
        }
    }
}
