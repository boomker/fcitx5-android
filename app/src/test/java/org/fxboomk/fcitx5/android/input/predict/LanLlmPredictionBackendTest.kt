/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

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
        val backend = LocalLanLlmPredictionBackend(
            runtime = object : LocalLanLlmRuntime {
                override fun isAvailable(): Boolean = true

                override fun predict(request: LocalLanLlmPredictionRequest): List<String> =
                    listOf("候选一", "候选二", "候选三")
            },
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

        val response = kotlinx.coroutines.runBlocking {
            backend.predict(
                config = config,
                request = LanLlmPredictor.Request(beforeCursor = "你好"),
            )
        }

        assertEquals(listOf("候选一", "候选二"), response.suggestions)
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
}
