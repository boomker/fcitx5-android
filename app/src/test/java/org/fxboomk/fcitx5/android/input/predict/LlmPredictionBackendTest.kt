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

class LlmPredictionBackendTest {

    @Test
    fun selectPredictionBackendChoosesLocalWhenRuntimeIsLocal() {
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
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
        val backend = LocalLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLlmModelStore {
                override fun currentModel(context: android.content.Context): LlmLocalModelManager.InstalledModel? =
                    LlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LlmLocalModelManager.CompatibilityInfo(
                            state = LlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LlmLocalResourceManager.ResourceBundle =
                    LlmLocalResourceManager.ResourceBundle(
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
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
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
                request = LlmPredictor.Request(beforeCursor = "你好"),
            )
        }

        assertEquals(listOf("候选一", "候选二"), response.suggestions)
        assertEquals(LOCAL_SUGGESTION_MAX_OUTPUT_TOKENS, runtime.lastPredictRequest?.maxOutputTokens)
        assertEquals(LlmOutputMode.Suggestions, runtime.lastPredictRequest?.outputMode)
        assertEquals(LlmTaskMode.Completion, runtime.lastPredictRequest?.taskMode)
        assertFalse(runtime.lastPredictRequest?.enableThinking ?: true)
    }

    @Test
    fun localBackendNormalizesPredictionCandidateLimitBeforeRuntimeAndFinalTake() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(
            predictions = (1..10).map { "候选$it" }
        )
        val backend = LocalLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLlmModelStore {
                override fun currentModel(context: android.content.Context): LlmLocalModelManager.InstalledModel? =
                    LlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LlmLocalModelManager.CompatibilityInfo(
                            state = LlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LlmLocalResourceManager.ResourceBundle =
                    LlmLocalResourceManager.ResourceBundle(
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
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
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
                request = LlmPredictor.Request(beforeCursor = "你好"),
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
        val backend = LocalLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLlmModelStore {
                override fun currentModel(context: android.content.Context): LlmLocalModelManager.InstalledModel? =
                    LlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LlmLocalModelManager.CompatibilityInfo(
                            state = LlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LlmLocalResourceManager.ResourceBundle =
                    LlmLocalResourceManager.ResourceBundle(
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
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
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
                    request = LlmPredictor.Request(beforeCursor = "你好"),
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
        val backend = LocalLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLlmModelStore {
                override fun currentModel(context: android.content.Context): LlmLocalModelManager.InstalledModel? =
                    LlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LlmLocalModelManager.CompatibilityInfo(
                            state = LlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LlmLocalResourceManager.ResourceBundle =
                    LlmLocalResourceManager.ResourceBundle(
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
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
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
        assertEquals(LlmOutputMode.Suggestions, runtime.lastPrewarmRequest?.outputMode)
        assertEquals(LlmTaskMode.Completion, runtime.lastPrewarmRequest?.taskMode)
        assertEquals(false, runtime.lastPrewarmRequest?.enableThinking)
    }

    @Test
    fun localBackendTrimsCompletionSuggestionContextForOnDevicePrompt() {
        val context = ContextWrapper(null)
        val runtime = RecordingRuntime(
            predictions = listOf("候选一")
        )
        val backend = LocalLlmPredictionBackend(
            runtime = runtime,
            modelManager = object : LocalLlmModelStore {
                override fun currentModel(context: android.content.Context): LlmLocalModelManager.InstalledModel? =
                    LlmLocalModelManager.InstalledModel(
                        file = java.io.File("/tmp/model.onnx"),
                        displayName = "model.onnx",
                        source = LlmLocalModelManager.Source.Imported,
                        sizeBytes = 123,
                        updatedAtMillis = 1L,
                        compatibility = LlmLocalModelManager.CompatibilityInfo(
                            state = LlmLocalModelManager.Compatibility.Compatible,
                        ),
                    )
            },
            resourceManager = object : LocalLlmResourceStore {
                override fun prepareRuntimeBundle(
                    context: android.content.Context,
                    modelFile: java.io.File,
                ): LlmLocalResourceManager.ResourceBundle =
                    LlmLocalResourceManager.ResourceBundle(
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
        val config = LlmPrefs.Config(
            enabled = true,
            runtime = LlmPrefs.Runtime.LocalOnDevice,
            backend = LlmPrefs.Backend.ChatCompletions,
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
                request = LlmPredictor.Request(
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
    ) : LlmPredictionBackend {
        override suspend fun predict(
            config: LlmPrefs.Config,
            request: LlmPredictor.Request,
            onPartialText: ((String) -> Unit)?,
        ): LlmClient.PredictionResponse = LlmClient.PredictionResponse(
            suggestions = listOf(name),
            rawContent = name,
        )
    }

    private class RecordingRuntime(
        private val predictions: List<String>,
    ) : LocalLlmRuntime {
        var lastPredictRequest: LocalLlmPredictionRequest? = null
        var lastPrewarmRequest: LocalLlmPredictionRequest? = null
        var predictThreadName: String? = null

        override fun isAvailable(): Boolean = true

        override fun predict(request: LocalLlmPredictionRequest): List<String> {
            lastPredictRequest = request
            predictThreadName = Thread.currentThread().name
            return predictions
        }

        override fun prewarm(request: LocalLlmPredictionRequest) {
            lastPrewarmRequest = request
        }
    }
}
