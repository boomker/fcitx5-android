package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LanLlmPredictor(
    private val appContext: Context,
    private val client: LanLlmClient = LanLlmClient(),
) {
    data class Request(
        val beforeCursor: String,
        val recentCommittedText: String = "",
        val historyText: String = "",
    )

    private data class SamplePlan(
        val useRecentCommitBias: Boolean,
        val weight: Int,
        val seed: Int?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingJob: Job? = null

    fun request(
        request: Request,
        onResult: (List<String>) -> Unit,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        val config = LanLlmPrefs.read(appContext)
        if (!config.isUsable || request.beforeCursor.isBlank()) {
            onResult(emptyList())
            return
        }

        cancel()
        pendingJob = scope.launch {
            delay(config.debounceMs)
            runCatching {
                if (config.backend == LanLlmPrefs.Backend.Completion) {
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
                        )
                    )
                }
            }.onSuccess {
                onResult(it.suggestions)
            }.onFailure {
                onError?.invoke(it)
                onResult(emptyList())
            }
        }
    }

    private suspend fun predictCompletion(
        config: LanLlmPrefs.Config,
        request: Request,
    ): LanLlmClient.PredictionResponse {
        val prioritizedSamples = if (config.preferLastCommit && request.recentCommittedText.isNotBlank()) 1 else 0
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
            .take(4)

        return LanLlmClient.PredictionResponse(
            suggestions = suggestions,
            rawContent = rawContent,
        )
    }

    fun cancel() {
        pendingJob?.cancel()
        pendingJob = null
    }

    fun close() {
        cancel()
        scope.cancel()
    }
}
