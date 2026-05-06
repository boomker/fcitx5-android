package org.fxboomk.fcitx5.android.input.predict

import android.util.Log
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "LanLlmPredictor"

internal enum class LanLlmOutputMode {
    Suggestions,
    LongForm,
}

internal enum class LanLlmTaskMode {
    Completion,
    QuestionAnswer,
    Translate,
}

internal class LanLlmPredictor(
    private val appContext: Context,
    private val client: LanLlmClient = LanLlmClient(
        thinkingSuppressionStore = LanLlmSharedPrefsThinkingSuppressionStore.fromContext(appContext.applicationContext),
    ),
    private val remoteBackend: LanLlmPredictionBackend = RemoteLanLlmPredictionBackend(client),
    private val localBackend: LanLlmPredictionBackend = LocalLanLlmPredictionBackend(appContext = appContext.applicationContext),
    private val configReader: (Context) -> LanLlmPrefs.Config = { LanLlmPrefs.read(it) },
) {
    internal class RequestTracker {
        private var activeRequestId = 0L

        fun replaceActiveRequest(): Long = synchronized(this) {
            activeRequestId += 1
            activeRequestId
        }

        fun invalidate() {
            synchronized(this) {
                activeRequestId += 1
            }
        }

        fun isActive(requestId: Long): Boolean = synchronized(this) {
            requestId == activeRequestId
        }
    }

    data class Request(
        val beforeCursor: String,
        val recentCommittedText: String = "",
        val historyText: String = "",
        val outputMode: LanLlmOutputMode = LanLlmOutputMode.Suggestions,
        val taskMode: LanLlmTaskMode = LanLlmTaskMode.Completion,
        val enableThinking: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestTracker = RequestTracker()
    private var pendingJob: Job? = null
    private var prewarmJob: Job? = null

    fun request(
        request: Request,
        onResult: (List<String>) -> Unit,
        onError: ((Throwable) -> Unit)? = null,
        onPartial: ((String) -> Unit)? = null,
    ) {
        val config = configReader(appContext)
        val backend = selectPredictionBackend(config, remoteBackend, localBackend)
        val requestId = requestTracker.replaceActiveRequest()
        pendingJob?.cancel()
        pendingJob = null
        if (!config.isUsable || request.beforeCursor.isBlank()) {
            onResult(emptyList())
            return
        }
        startPrewarmIfNeeded(config, backend)

        pendingJob = scope.launch {
            val runningJob = checkNotNull(coroutineContext[Job])
            try {
                delay(config.debounceMs)
                if (!runningJob.isActive || !requestTracker.isActive(requestId)) {
                    return@launch
                }
                val result = runCatching {
                    backend.predict(
                        config = config,
                        request = request,
                        onPartialText = if (
                            request.outputMode == LanLlmOutputMode.LongForm ||
                            request.taskMode == LanLlmTaskMode.QuestionAnswer ||
                            request.taskMode == LanLlmTaskMode.Translate
                        ) {
                            { partial ->
                                scope.launch {
                                    if (runningJob.isActive && requestTracker.isActive(requestId)) {
                                        onPartial?.invoke(partial)
                                    }
                                }
                            }
                        } else {
                            null
                        },
                    )
                }

                if (!runningJob.isActive || !requestTracker.isActive(requestId)) {
                    return@launch
                }

                result.onSuccess {
                    val limit = if (
                        request.outputMode == LanLlmOutputMode.LongForm ||
                        request.taskMode == LanLlmTaskMode.QuestionAnswer ||
                        request.taskMode == LanLlmTaskMode.Translate
                    ) {
                        1
                    } else {
                        normalizedPredictionCandidateLimit(config.maxPredictionCandidates)
                    }
                    onResult(it.suggestions.take(limit))
                }.onFailure {
                    onError?.invoke(it)
                    onResult(emptyList())
                }
            } finally {
                if (pendingJob === runningJob) {
                    pendingJob = null
                }
            }
        }
    }

    private fun startPrewarmIfNeeded(
        config: LanLlmPrefs.Config,
        backend: LanLlmPredictionBackend,
    ) {
        val warmable = backend as? WarmablePredictionBackend ?: return
        if (prewarmJob?.isActive == true) return
        prewarmJob = scope.launch(Dispatchers.Default) {
            runCatching {
                warmable.prewarm(config)
            }.onFailure { error ->
                Log.w(TAG, "local prewarm failed: ${error.message}", error)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (prewarmJob === job) {
                    prewarmJob = null
                }
            }
        }
    }

    fun cancel() {
        requestTracker.invalidate()
        pendingJob?.cancel()
        pendingJob = null
    }

    fun close() {
        cancel()
        scope.cancel()
    }
}
