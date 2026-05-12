package org.fxboomk.fcitx5.android.input.predict

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

internal const val LOCAL_SUGGESTION_MAX_OUTPUT_TOKENS = 96
internal const val FULL_TEXT_MODE_MIN_OUTPUT_TOKENS = 1024
private const val LOCAL_SUGGESTION_CONTEXT_CHARS = 24
private const val LOCAL_WARMUP_MAX_OUTPUT_TOKENS = 8
private const val LOCAL_WARMUP_PROMPT = "你好"
internal const val MAX_PREDICTION_CANDIDATE_LIMIT = 8

internal interface LlmPredictionBackend {
    suspend fun predict(
        config: LlmPrefs.Config,
        request: LlmPredictor.Request,
        onPartialText: ((String) -> Unit)? = null,
    ): LlmClient.PredictionResponse
}

internal interface WarmablePredictionBackend {
    fun prewarm(config: LlmPrefs.Config)
}

internal data class LocalLlmPredictionRequest(
    val modelPath: String,
    val companionDirectory: String,
    val beforeCursor: String,
    val recentCommittedText: String,
    val historyText: String,
    val maxPredictionCandidates: Int,
    val maxOutputTokens: Int,
    val outputMode: LlmOutputMode,
    val taskMode: LlmTaskMode,
    val enableThinking: Boolean,
    val personaPreset: LlmPrefs.PersonaPreset,
    val customPersona: String,
)

internal interface LocalLlmRuntime {
    fun isAvailable(): Boolean

    fun predict(request: LocalLlmPredictionRequest): List<String>

    fun prewarm(request: LocalLlmPredictionRequest)
}

internal fun optimizedLocalMaxOutputTokens(
    config: LlmPrefs.Config,
    request: LlmPredictor.Request,
): Int = when {
    request.outputMode == LlmOutputMode.Suggestions &&
        request.taskMode == LlmTaskMode.Completion ->
        minOf(config.maxOutputTokens, LOCAL_SUGGESTION_MAX_OUTPUT_TOKENS)

    request.outputMode == LlmOutputMode.LongForm ||
        request.taskMode == LlmTaskMode.QuestionAnswer ->
        maxOf(config.maxOutputTokens, FULL_TEXT_MODE_MIN_OUTPUT_TOKENS)

    else -> config.maxOutputTokens
}

internal fun normalizedPredictionCandidateLimit(value: Int): Int =
    value.coerceIn(1, MAX_PREDICTION_CANDIDATE_LIMIT)

internal data class LocalContextPayload(
    val recentCommittedText: String,
    val historyText: String,
)

internal fun optimizedLocalContextPayload(
    request: LlmPredictor.Request,
): LocalContextPayload = if (
    request.outputMode == LlmOutputMode.Suggestions &&
    request.taskMode == LlmTaskMode.Completion
) {
    LocalContextPayload(
        recentCommittedText = request.recentCommittedText.takeLast(LOCAL_SUGGESTION_CONTEXT_CHARS),
        historyText = "",
    )
} else {
    LocalContextPayload(
        recentCommittedText = request.recentCommittedText,
        historyText = request.historyText,
    )
}

internal class RemoteLlmPredictionBackend(
    private val client: LlmClient,
) : LlmPredictionBackend {
    private data class SamplePlan(
        val useRecentCommitBias: Boolean,
        val weight: Int,
        val seed: Int?,
    )

    override suspend fun predict(
        config: LlmPrefs.Config,
        request: LlmPredictor.Request,
        onPartialText: ((String) -> Unit)?,
    ): LlmClient.PredictionResponse {
        return if (
            config.backend == LlmPrefs.Backend.Completion &&
            request.outputMode == LlmOutputMode.Suggestions
        ) {
            predictCompletion(config, request)
        } else {
            val useRecentCommitBias = config.preferLastCommit && request.recentCommittedText.isNotBlank()
            client.predict(
                LlmClient.PredictionRequest(
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
        config: LlmPrefs.Config,
        request: LlmPredictor.Request,
    ): LlmClient.PredictionResponse {
        val candidateLimit = normalizedPredictionCandidateLimit(config.maxPredictionCandidates)
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
                        LlmClient.PredictionRequest(
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
                    request.taskMode == LlmTaskMode.QuestionAnswer ||
                    request.taskMode == LlmTaskMode.Translate
                ) {
                    1
                } else {
                    candidateLimit
                }
            )

        return LlmClient.PredictionResponse(
            suggestions = suggestions,
            rawContent = rawContent,
        )
    }
}

internal class LocalLlmPredictionBackend(
    private val runtime: LocalLlmRuntime = GenAiLocalLlmRuntime,
    private val modelManager: LocalLlmModelStore = LlmLocalModelManager,
    private val resourceManager: LocalLlmResourceStore = LlmLocalResourceManager,
    private val appContext: android.content.Context,
) : LlmPredictionBackend, WarmablePredictionBackend {
    override fun prewarm(config: LlmPrefs.Config) {
        val model = modelManager.currentModel(appContext) ?: return
        if (!runtime.isAvailable()) return
        val resources = resourceManager.prepareRuntimeBundle(appContext, model.file)
        runtime.prewarm(
            LocalLlmPredictionRequest(
                modelPath = resources.model.absolutePath,
                companionDirectory = resources.directory.absolutePath,
                beforeCursor = LOCAL_WARMUP_PROMPT,
                recentCommittedText = "",
                historyText = "",
                maxPredictionCandidates = 1,
                maxOutputTokens = LOCAL_WARMUP_MAX_OUTPUT_TOKENS,
                outputMode = LlmOutputMode.Suggestions,
                taskMode = LlmTaskMode.Completion,
                enableThinking = false,
                personaPreset = config.personaPreset,
                customPersona = config.customPersona,
            )
        )
    }

    override suspend fun predict(
        config: LlmPrefs.Config,
        request: LlmPredictor.Request,
        onPartialText: ((String) -> Unit)?,
    ): LlmClient.PredictionResponse = withContext(Dispatchers.Default) {
        val model = modelManager.currentModel(appContext)
        if (model == null || !runtime.isAvailable()) {
            return@withContext LlmClient.PredictionResponse(
                suggestions = emptyList(),
                rawContent = "",
            )
        }
        val resources = resourceManager.prepareRuntimeBundle(appContext, model.file)
        val maxOutputTokens = optimizedLocalMaxOutputTokens(config, request)
        val contextPayload = optimizedLocalContextPayload(request)
        val candidateLimit = normalizedPredictionCandidateLimit(config.maxPredictionCandidates)
        val suggestions = runtime.predict(
            LocalLlmPredictionRequest(
                modelPath = resources.model.absolutePath,
                companionDirectory = resources.directory.absolutePath,
                beforeCursor = request.beforeCursor,
                recentCommittedText = contextPayload.recentCommittedText,
                historyText = contextPayload.historyText,
                maxPredictionCandidates = candidateLimit,
                maxOutputTokens = maxOutputTokens,
                outputMode = request.outputMode,
                taskMode = request.taskMode,
                enableThinking = request.enableThinking,
                personaPreset = config.personaPreset,
                customPersona = config.customPersona,
            )
        ).take(
            if (
                request.outputMode == LlmOutputMode.LongForm ||
                request.taskMode == LlmTaskMode.QuestionAnswer ||
                request.taskMode == LlmTaskMode.Translate
            ) {
                1
            } else {
                candidateLimit
            }
        )
        LlmClient.PredictionResponse(
            suggestions = suggestions,
            rawContent = suggestions.joinToString(separator = " | "),
        )
    }
}

internal fun selectPredictionBackend(
    config: LlmPrefs.Config,
    remoteBackend: LlmPredictionBackend,
    localBackend: LlmPredictionBackend,
): LlmPredictionBackend = if (config.isLocalOnDevice) localBackend else remoteBackend
