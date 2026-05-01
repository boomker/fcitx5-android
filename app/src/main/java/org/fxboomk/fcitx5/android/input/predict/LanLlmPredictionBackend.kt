package org.fxboomk.fcitx5.android.input.predict

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal interface LanLlmPredictionBackend {
    suspend fun predict(
        config: LanLlmPrefs.Config,
        request: LanLlmPredictor.Request,
        onPartialText: ((String) -> Unit)? = null,
    ): LanLlmClient.PredictionResponse
}

internal data class LocalLanLlmPredictionRequest(
    val modelPath: String,
    val companionDirectory: String,
    val beforeCursor: String,
    val recentCommittedText: String,
    val historyText: String,
    val maxPredictionCandidates: Int,
    val maxOutputTokens: Int,
)

internal interface LocalLanLlmRuntime {
    fun isAvailable(): Boolean

    fun predict(request: LocalLanLlmPredictionRequest): List<String>
}

internal class RemoteLanLlmPredictionBackend(
    private val client: LanLlmClient,
) : LanLlmPredictionBackend {
    private data class SamplePlan(
        val useRecentCommitBias: Boolean,
        val weight: Int,
        val seed: Int?,
    )

    override suspend fun predict(
        config: LanLlmPrefs.Config,
        request: LanLlmPredictor.Request,
        onPartialText: ((String) -> Unit)?,
    ): LanLlmClient.PredictionResponse {
        return if (
            config.backend == LanLlmPrefs.Backend.Completion &&
            request.outputMode == LanLlmOutputMode.Suggestions
        ) {
            predictCompletion(config, request)
        } else {
            val useRecentCommitBias = config.preferLastCommit && request.recentCommittedText.isNotBlank()
            client.predict(
                LanLlmClient.PredictionRequest(
                    config = config,
                    beforeCursor = request.beforeCursor,
                    recentCommittedText = request.recentCommittedText,
                    historyText = request.historyText,
                    useRecentCommitBias = useRecentCommitBias,
                    outputMode = request.outputMode,
                    taskMode = request.taskMode,
                    enableThinking = request.enableThinking,
                ),
                onPartialText = onPartialText,
            )
        }
    }

    private suspend fun predictCompletion(
        config: LanLlmPrefs.Config,
        request: LanLlmPredictor.Request,
    ): LanLlmClient.PredictionResponse {
        val prioritizedSamples =
            if (config.preferLastCommit && request.recentCommittedText.isNotBlank()) 1 else 0
        val plans = List(config.sampleCount) { index ->
            SamplePlan(
                useRecentCommitBias = index < prioritizedSamples,
                weight = if (index < prioritizedSamples) 2 else 1,
                seed = 20260419 + index,
            )
        }

        val responses = kotlinx.coroutines.coroutineScope {
            plans.map { plan ->
                async(Dispatchers.IO) {
                    plan to client.predict(
                        LanLlmClient.PredictionRequest(
                            config = config,
                            beforeCursor = request.beforeCursor,
                            recentCommittedText = request.recentCommittedText,
                            historyText = request.historyText,
                            useRecentCommitBias = plan.useRecentCommitBias,
                            seed = plan.seed,
                            outputMode = request.outputMode,
                            taskMode = request.taskMode,
                            enableThinking = request.enableThinking,
                        )
                    )
                }
            }.map { it.await() }
        }

        val ranked = linkedMapOf<String, Int>()
        val rawContent = buildString {
            responses.forEachIndexed { index, (plan, response) ->
                response.suggestions.forEachIndexed { candidateIndex, suggestion ->
                    val score = plan.weight * 10 - candidateIndex
                    ranked[suggestion] = (ranked[suggestion] ?: 0) + score
                }
                if (response.rawContent.isNotBlank()) {
                    if (index > 0) append(" | ")
                    append(response.rawContent.take(80))
                }
            }
        }

        val suggestions = ranked.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key.length }
                    .thenBy { it.key }
            )
            .map { it.key }
            .take(
                if (
                    request.taskMode == LanLlmTaskMode.QuestionAnswer ||
                    request.taskMode == LanLlmTaskMode.Translate
                ) {
                    1
                } else {
                    config.maxPredictionCandidates
                }
            )

        return LanLlmClient.PredictionResponse(
            suggestions = suggestions,
            rawContent = rawContent,
        )
    }
}

internal class LocalLanLlmPredictionBackend(
    private val runtime: LocalLanLlmRuntime = GenAiLocalLanLlmRuntime,
    private val modelManager: LocalLanLlmModelStore = LanLlmLocalModelManager,
    private val resourceManager: LocalLanLlmResourceStore = LanLlmLocalResourceManager,
    private val appContext: android.content.Context,
) : LanLlmPredictionBackend {
    override suspend fun predict(
        config: LanLlmPrefs.Config,
        request: LanLlmPredictor.Request,
        onPartialText: ((String) -> Unit)?,
    ): LanLlmClient.PredictionResponse {
        val model = modelManager.currentModel(appContext)
        if (model == null || !runtime.isAvailable()) {
            return LanLlmClient.PredictionResponse(
                suggestions = emptyList(),
                rawContent = "",
            )
        }
        val resources = resourceManager.prepareRuntimeBundle(appContext, model.file)
        val suggestions = runtime.predict(
            LocalLanLlmPredictionRequest(
                modelPath = resources.model.absolutePath,
                companionDirectory = resources.directory.absolutePath,
                beforeCursor = request.beforeCursor,
                recentCommittedText = request.recentCommittedText,
                historyText = request.historyText,
                maxPredictionCandidates = config.maxPredictionCandidates,
                maxOutputTokens = config.maxOutputTokens,
            )
        ).take(
            if (
                request.outputMode == LanLlmOutputMode.LongForm ||
                request.taskMode == LanLlmTaskMode.QuestionAnswer ||
                request.taskMode == LanLlmTaskMode.Translate
            ) {
                1
            } else {
                config.maxPredictionCandidates
            }
        )
        return LanLlmClient.PredictionResponse(
            suggestions = suggestions,
            rawContent = suggestions.joinToString(separator = " | "),
        )
    }
}

internal fun selectPredictionBackend(
    config: LanLlmPrefs.Config,
    remoteBackend: LanLlmPredictionBackend,
    localBackend: LanLlmPredictionBackend,
): LanLlmPredictionBackend = if (config.isLocalOnDevice) localBackend else remoteBackend
