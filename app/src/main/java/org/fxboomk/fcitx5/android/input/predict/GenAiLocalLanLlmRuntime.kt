package org.fxboomk.fcitx5.android.input.predict

import android.os.Build
import android.os.SystemClock
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import ai.onnxruntime.genai.TokenizerStream
import java.io.File
import timber.log.Timber

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
            val model = ensureModel(bundleDir.absolutePath)
            val tokenizer = ensureTokenizer(model)
            val inputSequences = tokenizer.encode(prompt)
            val inputTokenCount = inputSequences.getSequence(0).size
            synchronized(generationLock) {
                val stream = tokenizer.createStream()
                val params = GeneratorParams(model).also {
                    configureSearch(
                        params = it,
                        request = request,
                        inputTokenCount = inputTokenCount,
                    )
                }
                params.use { generatorParams ->
                    inputSequences.use { sequences ->
                        stream.use { tokenizerStream ->
                            generateText(
                                model = model,
                                params = generatorParams,
                                inputSequences = sequences,
                                tokenizerStream = tokenizerStream,
                                request = request,
                            )
                        }
                    }
                }
            }
            warmedBundleKey = bundleKey
            Timber.i(
                "GenAI prewarm complete bundle=%s durationMs=%d promptChars=%d",
                bundleDir.absolutePath,
                SystemClock.elapsedRealtime() - startAtMs,
                prompt.length,
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
            val model = ensureModel(bundleDir.absolutePath)
            val tokenizer = ensureTokenizer(model)
            val inputSequences = tokenizer.encode(prompt)
            val inputTokenCount = inputSequences.getSequence(0).size
            val stream = tokenizer.createStream()
            val params = GeneratorParams(model).also {
                configureSearch(
                    params = it,
                    request = request,
                    inputTokenCount = inputTokenCount,
                )
            }
            Timber.i(
                "GenAI smoke start bundle=%s promptChars=%d inputTokens=%d maxOutputTokens=%d mode=%s task=%s thinking=%s",
                bundleDir.absolutePath,
                prompt.length,
                inputTokenCount,
                request.maxOutputTokens,
                request.outputMode,
                request.taskMode,
                request.enableThinking,
            )
            val generation = synchronized(generationLock) {
                params.use { generatorParams ->
                    inputSequences.use { sequences ->
                        stream.use { tokenizerStream ->
                            generateText(
                                model = model,
                                params = generatorParams,
                                inputSequences = sequences,
                                tokenizerStream = tokenizerStream,
                                request = request,
                            )
                        }
                    }
                }
            }
            val rawText = generation.text.trim()
            Timber.i(
                "GenAI smoke complete generatedTokens=%d rawChars=%d durationMs=%d",
                generation.generatedTokenCount,
                rawText.length,
                SystemClock.elapsedRealtime() - startAtMs,
            )
            SmokeResult(
                stage = if (generation.generatedTokenCount == 0) "complete_empty" else "complete",
                prompt = prompt,
                rawText = rawText,
                suggestions = LanLlmSuggestionParser.parse(rawText, request.beforeCursor)
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

    private fun ensureModel(bundlePath: String): Model = synchronized(lock) {
        val current = activeModel
        if (bundlePath == activeBundlePath && current != null) {
            return current
        }
        activeTokenizer?.close()
        activeTokenizer = null
        current?.close()
        activeModel = Model(bundlePath)
        activeBundlePath = bundlePath
        checkNotNull(activeModel)
    }

    private fun ensureTokenizer(model: Model): Tokenizer = synchronized(lock) {
        val current = activeTokenizer
        if (current != null) return current
        activeTokenizer = Tokenizer(model)
        checkNotNull(activeTokenizer)
    }

    private fun generateText(
        model: Model,
        params: GeneratorParams,
        inputSequences: ai.onnxruntime.genai.Sequences,
        tokenizerStream: TokenizerStream,
        request: LocalLanLlmPredictionRequest,
    ): GenerationResult {
        val builder = StringBuilder()
        Generator(model, params).use { generator ->
            generator.appendTokenSequences(inputSequences)
            var generatedCount = 0
            while (!generator.isDone() && generatedCount < request.maxOutputTokens) {
                generator.generateNextToken()
                val token = generator.getLastTokenInSequence(0)
                builder.append(tokenizerStream.decode(token))
                generatedCount += 1
                if (shouldStopLocalGeneration(request, builder.toString())) {
                    break
                }
            }
            return GenerationResult(
                text = builder.toString(),
                generatedTokenCount = generatedCount,
            )
        }
    }

    private fun buildPrompt(request: LocalLanLlmPredictionRequest): String =
        LanLlmPrompt.completionPrompt(
            beforeCursor = request.beforeCursor,
            recentCommittedText = request.recentCommittedText,
            historyText = request.historyText,
            useRecentCommitBias = request.recentCommittedText.isNotBlank(),
            outputMode = request.outputMode,
            taskMode = request.taskMode,
            enableThinking = request.enableThinking,
        )

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
        warmedBundleKey = null
    }
}

internal fun shouldStopLocalGeneration(
    request: LocalLanLlmPredictionRequest,
    generatedText: String,
): Boolean {
    if ("<|im_end|>" in generatedText || "<|endoftext|>" in generatedText) {
        return true
    }
    if (request.outputMode != LanLlmOutputMode.Suggestions) {
        return false
    }
    return when (request.taskMode) {
        LanLlmTaskMode.Completion -> {
            val suggestions = LanLlmSuggestionParser.parse(generatedText, request.beforeCursor)
            suggestions.size >= request.maxPredictionCandidates ||
                (suggestions.isNotEmpty() && generatedText.trimEnd().endsWith("}"))
        }

        LanLlmTaskMode.QuestionAnswer,
        LanLlmTaskMode.Translate,
        -> {
            val suggestion = LanLlmSuggestionParser.parseSingleText(generatedText).firstOrNull()
            !suggestion.isNullOrBlank() && '\n' in generatedText
        }
    }
}
