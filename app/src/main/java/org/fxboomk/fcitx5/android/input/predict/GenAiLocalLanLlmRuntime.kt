package org.fxboomk.fcitx5.android.input.predict

import android.os.Build
import android.os.SystemClock
import ai.onnxruntime.genai.Config
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import ai.onnxruntime.genai.TokenizerStream
import java.io.File
import timber.log.Timber

private const val LOCAL_MAX_INTRA_OP_THREADS = 4
private const val LOCAL_INTER_OP_THREADS = 1
private const val LOCAL_GRAPH_OPT_LEVEL = "ORT_ENABLE_ALL"
private const val LOCAL_PROVIDER_CPU = "CPU"
private const val LOCAL_PROVIDER_XNNPACK = "XNNPACK"

internal object GenAiLocalLanLlmRuntime : LocalLanLlmRuntime {
    private const val QWEN_THINKING_TEMPERATURE = 0.6
    private const val QWEN_NON_THINKING_TEMPERATURE = 0.7
    private const val QWEN_THINKING_TOP_P = 0.95
    private const val QWEN_NON_THINKING_TOP_P = 0.8
    private const val QWEN_TOP_K = 20.0

    data class CompatibilityResult(
        val isCompatible: Boolean,
        val stage: String,
        val detail: String? = null,
    )

    private data class GenerationResult(
        val text: String,
        val generatedTokenCount: Int,
        val firstTokenLatencyMs: Long,
        val generationDurationMs: Long,
        val stopReason: String,
    )

    private data class CachedResource<T>(
        val value: T,
        val cacheHit: Boolean,
        val loadDurationMs: Long,
    )

    private data class CachedModelResource(
        val model: Model,
        val cacheHit: Boolean,
        val loadDurationMs: Long,
        val provider: String,
    )

    internal data class LocalGenerationTelemetry(
        val provider: String,
        val intraOpThreads: Int,
        val interOpThreads: Int,
        val graphOptimizationLevel: String,
        val modelLoadMs: Long,
        val modelCacheHit: Boolean,
        val tokenizerLoadMs: Long,
        val tokenizerCacheHit: Boolean,
        val tokenizeMs: Long,
        val inputTokenCount: Int,
        val generatedTokenCount: Int,
        val firstTokenLatencyMs: Long,
        val generationDurationMs: Long,
        val stopReason: String,
    ) {
        val tokensPerSecond: Double
            get() = if (generationDurationMs <= 0L) {
                generatedTokenCount.toDouble()
            } else {
                generatedTokenCount * 1000.0 / generationDurationMs
            }
    }

    private data class LocalGenerationRun(
        val rawText: String,
        val telemetry: LocalGenerationTelemetry,
    )

    data class SmokeResult(
        val stage: String,
        val prompt: String,
        val rawText: String,
        val suggestions: List<String>,
        val error: String? = null,
    )

    private val lock = Any()
    private val generationLock = Any()

    @Volatile
    private var activeBundlePath: String? = null

    @Volatile
    private var activeModel: Model? = null

    @Volatile
    private var activeSessionTuningKey: String? = null

    @Volatile
    private var activeProviderName: String? = null

    @Volatile
    private var activeTokenizer: Tokenizer? = null

    @Volatile
    private var warmedBundleKey: String? = null

    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun predict(request: LocalLanLlmPredictionRequest): List<String> {
        return smoke(request).suggestions
    }

    override fun prewarm(request: LocalLanLlmPredictionRequest) {
        if (!isAvailable()) return
        val bundleDir = File(request.companionDirectory)
        if (!bundleDir.exists()) return
        val bundleKey = bundleKey(request)
        if (bundleKey == warmedBundleKey) return
        val prompt = buildPrompt(request)
        val startAtMs = SystemClock.elapsedRealtime()
        runCatching {
            val run = runGeneration(bundleDir.absolutePath, prompt, request)
            warmedBundleKey = bundleKey
            Timber.i(
                "GenAI prewarm complete bundle=%s durationMs=%d promptChars=%d %s",
                bundleDir.absolutePath,
                SystemClock.elapsedRealtime() - startAtMs,
                prompt.length,
                formatLocalGenerationTelemetry(run.telemetry),
            )
        }.onFailure { error ->
            Timber.w(error, "GenAI prewarm failed")
        }
    }

    fun checkCompatibility(bundleDirectory: String): CompatibilityResult {
        if (!isAvailable()) {
            return CompatibilityResult(
                isCompatible = false,
                stage = "runtime_unavailable",
                detail = "GenAI runtime requires Android 24+",
            )
        }
        val bundleDir = File(bundleDirectory)
        if (!bundleDir.exists()) {
            return CompatibilityResult(
                isCompatible = false,
                stage = "bundle_missing",
                detail = "Bundle directory does not exist: ${bundleDir.absolutePath}",
            )
        }
        return runCatching {
            Model(bundleDir.absolutePath).use { model ->
                Tokenizer(model).use { tokenizer ->
                    tokenizer.encode("你好").use { }
                }
            }
            CompatibilityResult(
                isCompatible = true,
                stage = "compatible",
            )
        }.getOrElse { error ->
            Timber.e(error, "GenAI compatibility check failed")
            CompatibilityResult(
                isCompatible = false,
                stage = error::class.java.simpleName,
                detail = error.message ?: error.stackTraceToString(),
            )
        }
    }

    fun smoke(request: LocalLanLlmPredictionRequest): SmokeResult {
        if (!isAvailable()) {
            return SmokeResult(
                stage = "unavailable",
                prompt = "",
                rawText = "",
                suggestions = emptyList(),
                error = "GenAI runtime requires Android 24+",
            )
        }
        val bundleDir = File(request.companionDirectory)
        val prompt = buildPrompt(request)
        if (!bundleDir.exists()) {
            return SmokeResult(
                stage = "bundle",
                prompt = prompt,
                rawText = "",
                suggestions = emptyList(),
                error = "GenAI bundle directory does not exist: ${bundleDir.absolutePath}",
            )
        }
        return runCatching {
            val startAtMs = SystemClock.elapsedRealtime()
            val run = runGeneration(bundleDir.absolutePath, prompt, request)
            Timber.i(
                "GenAI smoke start bundle=%s promptChars=%d inputTokens=%d maxOutputTokens=%d mode=%s task=%s thinking=%s modelCacheHit=%s tokenizerCacheHit=%s",
                bundleDir.absolutePath,
                prompt.length,
                run.telemetry.inputTokenCount,
                request.maxOutputTokens,
                request.outputMode,
                request.taskMode,
                request.enableThinking,
                run.telemetry.modelCacheHit,
                run.telemetry.tokenizerCacheHit,
            )
            Timber.i(
                "GenAI smoke complete rawChars=%d durationMs=%d %s",
                normalizeGeneratedThinkArtifacts(run.rawText).length,
                SystemClock.elapsedRealtime() - startAtMs,
                formatLocalGenerationTelemetry(run.telemetry),
            )
            SmokeResult(
                stage = if (run.telemetry.generatedTokenCount == 0) "complete_empty" else "complete",
                prompt = prompt,
                rawText = normalizeGeneratedThinkArtifacts(run.rawText),
                suggestions = LanLlmSuggestionParser.parse(
                    normalizeGeneratedThinkArtifacts(run.rawText),
                    request.beforeCursor,
                )
                    .take(request.maxPredictionCandidates),
            )
        }.getOrElse { error ->
            Timber.e(error, "GenAI smoke failed")
            SmokeResult(
                stage = error::class.java.simpleName,
                prompt = prompt,
                rawText = "",
                suggestions = emptyList(),
                error = error.stackTraceToString(),
            )
        }
    }

    private fun configureSearch(
        params: GeneratorParams,
        request: LocalLanLlmPredictionRequest,
        inputTokenCount: Int,
    ) {
        val targetMaxLength = (inputTokenCount + request.maxOutputTokens).toDouble()
        val temperature = if (request.enableThinking) {
            QWEN_THINKING_TEMPERATURE
        } else {
            QWEN_NON_THINKING_TEMPERATURE
        }
        val topP = if (request.enableThinking) {
            QWEN_THINKING_TOP_P
        } else {
            QWEN_NON_THINKING_TOP_P
        }
        params.setSearchOption("do_sample", true)
        params.setSearchOption("max_length", targetMaxLength)
        params.setSearchOption("temperature", temperature)
        params.setSearchOption("top_k", QWEN_TOP_K)
        params.setSearchOption("top_p", topP)
    }

    private fun acquireModel(
        bundlePath: String,
        sessionTuning: LocalSessionTuning,
    ): CachedModelResource = synchronized(lock) {
        val startAtMs = SystemClock.elapsedRealtime()
        val current = activeModel
        if (
            bundlePath == activeBundlePath &&
            current != null &&
            sessionTuning.cacheKey == activeSessionTuningKey
        ) {
            return CachedModelResource(
                model = current,
                cacheHit = true,
                loadDurationMs = SystemClock.elapsedRealtime() - startAtMs,
                provider = activeProviderName ?: LOCAL_PROVIDER_CPU,
            )
        }
        activeTokenizer?.close()
        activeTokenizer = null
        current?.close()
        val candidates = localExecutionProviderCandidates()
        var selectedProvider = LOCAL_PROVIDER_CPU
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                Config(bundlePath).use { config ->
                    config.overlay(buildSessionConfigOverlay(sessionTuning))
                    candidate.providerName?.let { providerName ->
                        config.clearProviders()
                        config.appendProvider(providerName)
                        candidate.options.forEach { (name, value) ->
                            config.setProviderOption(providerName, name, value)
                        }
                    }
                    activeModel = Model(config)
                }
                selectedProvider = candidate.name
                break
            } catch (error: Throwable) {
                lastError = error
                activeModel = null
                Timber.w(
                    error,
                    "GenAI provider init failed provider=%s type=%s detail=%s",
                    candidate.name,
                    error::class.java.simpleName,
                    summarizeProviderInitFailure(error),
                )
            }
        }
        if (activeModel == null && lastError != null) {
            throw lastError
        }
        activeBundlePath = bundlePath
        activeSessionTuningKey = sessionTuning.cacheKey
        activeProviderName = selectedProvider
        CachedModelResource(
            model = checkNotNull(activeModel),
            cacheHit = false,
            loadDurationMs = SystemClock.elapsedRealtime() - startAtMs,
            provider = selectedProvider,
        )
    }

    private fun acquireTokenizer(model: Model): CachedResource<Tokenizer> = synchronized(lock) {
        val startAtMs = SystemClock.elapsedRealtime()
        val current = activeTokenizer
        if (current != null) {
            return CachedResource(
                value = current,
                cacheHit = true,
                loadDurationMs = SystemClock.elapsedRealtime() - startAtMs,
            )
        }
        activeTokenizer = Tokenizer(model)
        CachedResource(
            value = checkNotNull(activeTokenizer),
            cacheHit = false,
            loadDurationMs = SystemClock.elapsedRealtime() - startAtMs,
        )
    }

    private fun generateText(
        model: Model,
        params: GeneratorParams,
        inputSequences: ai.onnxruntime.genai.Sequences,
        tokenizerStream: TokenizerStream,
        request: LocalLanLlmPredictionRequest,
    ): GenerationResult {
        val builder = StringBuilder()
        val generationStartAtMs = SystemClock.elapsedRealtime()
        var firstTokenLatencyMs = -1L
        Generator(model, params).use { generator ->
            generator.appendTokenSequences(inputSequences)
            var generatedCount = 0
            var stopReason: String? = null
            while (!generator.isDone() && generatedCount < request.maxOutputTokens) {
                generator.generateNextToken()
                val token = generator.getLastTokenInSequence(0)
                builder.append(tokenizerStream.decode(token))
                generatedCount += 1
                if (firstTokenLatencyMs < 0L) {
                    firstTokenLatencyMs = SystemClock.elapsedRealtime() - generationStartAtMs
                }
                stopReason = localGenerationStopReason(request, builder.toString())
                if (stopReason != null) {
                    break
                }
            }
            val resolvedStopReason = stopReason ?: when {
                generator.isDone() -> "generator_done"
                generatedCount >= request.maxOutputTokens -> "max_output_tokens"
                else -> "unknown"
            }
            return GenerationResult(
                text = builder.toString(),
                generatedTokenCount = generatedCount,
                firstTokenLatencyMs = firstTokenLatencyMs.coerceAtLeast(0L),
                generationDurationMs = SystemClock.elapsedRealtime() - generationStartAtMs,
                stopReason = resolvedStopReason,
            )
        }
    }

    private fun runGeneration(
        bundlePath: String,
        prompt: String,
        request: LocalLanLlmPredictionRequest,
    ): LocalGenerationRun {
        val sessionTuning = localSessionTuning()
        val model = acquireModel(bundlePath, sessionTuning)
        val tokenizer = acquireTokenizer(model.model)
        val tokenizeStartAtMs = SystemClock.elapsedRealtime()
        val inputSequences = tokenizer.value.encode(prompt)
        val inputTokenCount = inputSequences.getSequence(0).size
        val tokenizeMs = SystemClock.elapsedRealtime() - tokenizeStartAtMs
        val stream = tokenizer.value.createStream()
        val params = GeneratorParams(model.model).also {
            configureSearch(
                params = it,
                request = request,
                inputTokenCount = inputTokenCount,
            )
        }
        val generation = synchronized(generationLock) {
            params.use { generatorParams ->
                inputSequences.use { sequences ->
                    stream.use { tokenizerStream ->
                        generateText(
                            model = model.model,
                            params = generatorParams,
                            inputSequences = sequences,
                            tokenizerStream = tokenizerStream,
                            request = request,
                        )
                    }
                }
            }
        }
        return LocalGenerationRun(
            rawText = generation.text.trim(),
            telemetry = LocalGenerationTelemetry(
                provider = model.provider,
                intraOpThreads = sessionTuning.intraOpThreads,
                interOpThreads = sessionTuning.interOpThreads,
                graphOptimizationLevel = sessionTuning.graphOptimizationLevel,
                modelLoadMs = model.loadDurationMs,
                modelCacheHit = model.cacheHit,
                tokenizerLoadMs = tokenizer.loadDurationMs,
                tokenizerCacheHit = tokenizer.cacheHit,
                tokenizeMs = tokenizeMs,
                inputTokenCount = inputTokenCount,
                generatedTokenCount = generation.generatedTokenCount,
                firstTokenLatencyMs = generation.firstTokenLatencyMs,
                generationDurationMs = generation.generationDurationMs,
                stopReason = generation.stopReason,
            ),
        )
    }

    private fun buildPrompt(request: LocalLanLlmPredictionRequest): String =
        LanLlmPrompt.localOnDevicePrompt(
            beforeCursor = request.beforeCursor,
            recentCommittedText = request.recentCommittedText,
            historyText = request.historyText,
            maxPredictionCandidates = request.maxPredictionCandidates,
            outputMode = request.outputMode,
            taskMode = request.taskMode,
        )

    private fun summarizeProviderInitFailure(error: Throwable): String =
        error.message
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { it.isNotBlank() }
            .orEmpty()

    private fun bundleKey(request: LocalLanLlmPredictionRequest): String {
        val modelFile = File(request.modelPath)
        return buildString {
            append(request.companionDirectory)
            append('|')
            append(modelFile.length())
            append('|')
            append(modelFile.lastModified())
        }
    }

    fun close() = synchronized(lock) {
        activeTokenizer?.close()
        activeTokenizer = null
        activeModel?.close()
        activeModel = null
        activeBundlePath = null
        activeSessionTuningKey = null
        activeProviderName = null
        warmedBundleKey = null
    }
}

internal data class LocalSessionTuning(
    val intraOpThreads: Int,
    val interOpThreads: Int,
    val graphOptimizationLevel: String,
) {
    val cacheKey: String
        get() = "$intraOpThreads|$interOpThreads|$graphOptimizationLevel"
}

internal fun localSessionTuning(
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
): LocalSessionTuning {
    val intraOpThreads = availableProcessors
        .coerceAtLeast(2)
        .coerceAtMost(LOCAL_MAX_INTRA_OP_THREADS)
    return LocalSessionTuning(
        intraOpThreads = intraOpThreads,
        interOpThreads = LOCAL_INTER_OP_THREADS,
        graphOptimizationLevel = LOCAL_GRAPH_OPT_LEVEL,
    )
}

internal data class LocalExecutionProviderCandidate(
    val name: String,
    val providerName: String? = null,
    val options: Map<String, String> = emptyMap(),
)

internal fun localExecutionProviderCandidates(): List<LocalExecutionProviderCandidate> = listOf(
    LocalExecutionProviderCandidate(
        name = LOCAL_PROVIDER_XNNPACK,
        providerName = LOCAL_PROVIDER_XNNPACK,
    ),
    LocalExecutionProviderCandidate(
        name = LOCAL_PROVIDER_CPU,
    )
)

internal fun buildSessionConfigOverlay(sessionTuning: LocalSessionTuning): String = """
    {
      "model": {
        "decoder": {
          "session_options": {
            "intra_op_num_threads": ${sessionTuning.intraOpThreads},
            "inter_op_num_threads": ${sessionTuning.interOpThreads},
            "graph_optimization_level": "${sessionTuning.graphOptimizationLevel}"
          }
        }
      }
    }
""".trimIndent()

internal fun formatLocalGenerationTelemetry(telemetry: GenAiLocalLanLlmRuntime.LocalGenerationTelemetry): String =
    "provider=${telemetry.provider} intraOpThreads=${telemetry.intraOpThreads} interOpThreads=${telemetry.interOpThreads} " +
        "graphOptimizationLevel=${telemetry.graphOptimizationLevel} " +
        "modelLoadMs=${telemetry.modelLoadMs} modelCacheHit=${telemetry.modelCacheHit} " +
        "tokenizerLoadMs=${telemetry.tokenizerLoadMs} tokenizerCacheHit=${telemetry.tokenizerCacheHit} " +
        "tokenizeMs=${telemetry.tokenizeMs} generatedTokens=${telemetry.generatedTokenCount} " +
        "firstTokenMs=${telemetry.firstTokenLatencyMs} generateMs=${telemetry.generationDurationMs} " +
        "tokensPerSecond=${"%.2f".format(telemetry.tokensPerSecond)} stopReason=${telemetry.stopReason}"

internal fun localGenerationStopReason(
    request: LocalLanLlmPredictionRequest,
    generatedText: String,
): String? {
    if ("<|im_end|>" in generatedText || "<|endoftext|>" in generatedText) {
        return "control_token"
    }
    if (request.outputMode != LanLlmOutputMode.Suggestions) {
        return null
    }
    return when (request.taskMode) {
        LanLlmTaskMode.Completion -> {
            val suggestions = LanLlmSuggestionParser.parse(generatedText, request.beforeCursor)
            when {
                suggestions.size >= request.maxPredictionCandidates -> "enough_suggestions"
                suggestions.isNotEmpty() && generatedText.trimEnd().endsWith("}") -> "structured_payload_complete"
                else -> null
            }
        }

        LanLlmTaskMode.QuestionAnswer,
        LanLlmTaskMode.Translate,
        -> {
            val suggestion = LanLlmSuggestionParser.parseSingleText(generatedText).firstOrNull()
            if (!suggestion.isNullOrBlank() && '\n' in generatedText) {
                "single_text_newline"
            } else {
                null
            }
        }
    }
}

internal fun shouldStopLocalGeneration(
    request: LocalLanLlmPredictionRequest,
    generatedText: String,
): Boolean = localGenerationStopReason(request, generatedText) != null
