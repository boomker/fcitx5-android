package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.dependency.UniqueViewComponent
import splitties.dimensions.dp
import kotlin.math.abs

private const val TAG = "LanLlmChip"
private const val QA_PSEUDO_STREAM_CHUNK_CHARS = 2
private const val QA_PSEUDO_STREAM_DELAY_MS = 24L

class AiSuggestionStripComponent(
    private val service: FcitxInputMethodService,
    private val themedContext: Context,
) : UniqueViewComponent<AiSuggestionStripComponent, FrameLayout>(), InputBroadcastReceiver {

    data class CursorAnchorState(
        val horizontal: Float,
        val bottom: Float,
        val top: Float,
        val parentWidth: Float,
        val parentHeight: Float,
    )

    enum class PresentationMode {
        Hidden,
        BubbleAnchored,
        BubbleFallback,
        PanelVisible,
    }

    data class PresentationState(
        val mode: PresentationMode,
        val suggestions: List<String>,
        val anchor: CursorAnchorState?,
        val panelSuggestions: List<String>,
        val isLongFormEnabled: Boolean,
        val isSingleTextMode: Boolean,
        val isSingleTextLoading: Boolean,
        val isQuestionAnswerEnabled: Boolean,
        val isThinkingEnabled: Boolean,
    )

    private enum class PanelContentMode {
        Suggestions,
        QuestionAnswerLoading,
        QuestionAnswerStreaming,
        QuestionAnswerReady,
        LongFormLoading,
        LongFormStreaming,
        LongFormReady,
    }

    private val predictor by lazy { LanLlmPredictor(service.applicationContext) }
    private val recentCommittedSegments = ArrayDeque<String>()
    private val anchorUpdateThresholdPx = themedContext.dp(6).toFloat()

    private var allowPrediction = false
    private var hasClientPreedit = false
    private var hasInputPanelPreedit = false
    private var selectionCollapsed = true
    private var lastRequestedBeforeCursor = ""
    private var lastObservedBeforeCursor = ""
    private var lastCommittedText = ""
    private var lastPureLongDigitRequestAtMs = 0L
    private var commitRevision = 0
    private var predictionSuppressed = false
    private var suppressionUntilCommitRevision = -1
    private var activeSuggestions: List<String> = emptyList()
    private var panelVisible = false
    private var anchorState: CursorAnchorState? = null
    private var panelContentMode = PanelContentMode.Suggestions
    private var panelDisplayedText = ""
    private var taskMode = LanLlmTaskMode.Completion
    private var longFormEnabled = false
    private var thinkingEnabled = false
    private var pseudoStreamToken = 0L
    private var pseudoStreamRunnable: Runnable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onPresentationChanged: ((PresentationState) -> Unit)? = null

    override val view = FrameLayout(themedContext).apply {
        id = View.generateViewId()
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(0, 0)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        allowPrediction = !capFlags.has(CapabilityFlag.PasswordOrSensitive)
        hasClientPreedit = false
        hasInputPanelPreedit = false
        selectionCollapsed = true
        lastObservedBeforeCursor = ""
        lastCommittedText = ""
        lastPureLongDigitRequestAtMs = 0L
        commitRevision = 0
        predictionSuppressed = false
        suppressionUntilCommitRevision = -1
        recentCommittedSegments.clear()
        anchorState = null
        longFormEnabled = false
        resetPanelContentState()
        clearSuggestions(resetRequestState = true)
        requestPredictionIfNeeded()
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        hasClientPreedit = data.isNotEmpty()
        if (!shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        requestPredictionIfNeeded()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        hasInputPanelPreedit = data.preedit.isNotEmpty()
        if (!shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        requestPredictionIfNeeded()
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (!shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        requestPredictionIfNeeded()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        selectionCollapsed = start == end
        if (!selectionCollapsed || !shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        requestPredictionIfNeeded()
    }

    fun hasVisibleSuggestions(): Boolean = activeSuggestions.isNotEmpty()

    fun commitPrimarySuggestion(): Boolean {
        val suggestion = activeSuggestions.firstOrNull() ?: return false
        commitSuggestion(suggestion)
        return true
    }

    fun dismissVisibleSuggestions(): Boolean {
        if (!hasVisibleSuggestions()) return false
        suppressPredictionUntilNextCommit("dismiss-visible-suggestions")
        return true
    }

    fun openSuggestionTable(): Boolean {
        panelVisible = true
        return if (shouldUseSingleTextPanel()) {
            requestPanelTextContent()
        } else if (activeSuggestions.isNotEmpty()) {
            resetPanelContentState()
            dispatchPresentationChanged()
            true
        } else {
            panelVisible = false
            dispatchPresentationChanged()
            false
        }
    }

    fun toggleThinkingMode(): Boolean {
        thinkingEnabled = !thinkingEnabled
        refreshPredictionsForModeToggle()
        return thinkingEnabled
    }

    fun toggleQuestionAnswerMode(): Boolean {
        taskMode = if (taskMode == LanLlmTaskMode.QuestionAnswer) {
            LanLlmTaskMode.Completion
        } else {
            LanLlmTaskMode.QuestionAnswer
        }
        refreshPredictionsForModeToggle()
        return taskMode == LanLlmTaskMode.QuestionAnswer
    }

    fun toggleLongFormMode(): Boolean {
        longFormEnabled = !longFormEnabled
        refreshPredictionsForModeToggle()
        return longFormEnabled
    }

    fun collapsePanel(): Boolean {
        if (!panelVisible) return false
        panelVisible = false
        resetPanelContentState()
        dispatchPresentationChanged()
        return true
    }

    private fun requestLongForm(
        beforeCursor: String,
        config: LanLlmPrefs.Config,
    ): Boolean {
        if (beforeCursor.isBlank()) return false

        val request = LanLlmPredictor.Request(
            beforeCursor = beforeCursor,
            recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
            historyText = buildHistoryText(config.maxContextChars),
            outputMode = LanLlmOutputMode.LongForm,
            taskMode = taskMode,
            enableThinking = thinkingEnabled,
        )
        panelVisible = true
        panelContentMode = PanelContentMode.LongFormLoading
        activeSuggestions = activeSuggestions.take(1)
        panelDisplayedText = ""
        dispatchPresentationChanged()
        predictor.request(
            request = request,
            onResult = { suggestions ->
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val longForm = suggestions.firstOrNull()
                    ?.let {
                        sanitizeSingleCandidate(
                            candidate = it,
                            beforeCursor = beforeCursor,
                            outputMode = LanLlmOutputMode.LongForm,
                            taskMode = taskMode,
                        )
                    }
                    .orEmpty()
                if (longForm.isBlank()) {
                    resetPanelContentState()
                    dispatchPresentationChanged()
                    return@request
                }
                panelDisplayedText = longForm
                panelContentMode = PanelContentMode.LongFormReady
                dispatchPresentationChanged()
            },
            onError = { error ->
                Log.e(TAG, "long-form predict failed: ${error.message}", error)
                resetPanelContentState()
                dispatchPresentationChanged()
            },
            onPartial = { partial ->
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = beforeCursor,
                    outputMode = LanLlmOutputMode.LongForm,
                    taskMode = taskMode,
                )
                if (streamed.isBlank()) return@request
                panelDisplayedText = streamed
                panelContentMode = PanelContentMode.LongFormStreaming
                dispatchPresentationChanged()
            },
        )
        return true
    }

    fun suppressAfterBackspace() {
        suppressPredictionUntilNextCommit("backspace")
    }

    internal fun commitSuggestionFromUi(suggestion: String) {
        commitSuggestion(suggestion)
    }

    fun updateCursorAnchor(anchor: FloatArray?, parent: FloatArray) {
        if (parent.size < 2) return
        anchorState = anchor
            ?.takeIf { it.size >= 4 }
            ?.let { values ->
                val next = CursorAnchorState(
                    horizontal = values[0],
                    bottom = values[1],
                    top = values[3],
                    parentWidth = parent[0],
                    parentHeight = parent[1],
                )
                if (next.isInvalid()) {
                    null
                } else {
                    anchorState?.takeIf { it.isCloseTo(next) } ?: next
                }
            }
        dispatchPresentationChanged()
    }

    fun close() {
        predictor.close()
        cancelPseudoStreaming()
        resetPanelContentState()
        activeSuggestions = emptyList()
        panelVisible = false
        dispatchPresentationChanged()
    }

    private fun suppressPredictionUntilNextCommit(reason: String) {
        predictionSuppressed = true
        suppressionUntilCommitRevision = commitRevision
        Log.d(TAG, "suppress prediction reason=$reason untilCommitRevision=$suppressionUntilCommitRevision")
        clearSuggestions(resetRequestState = true)
    }

    private fun requestPredictionIfNeeded() {
        val keepSingleTextPanelVisible = shouldKeepSingleTextPanelVisible()
        if (!allowPrediction || (!keepSingleTextPanelVisible && (hasClientPreedit || hasInputPanelPreedit)) || !selectionCollapsed) {
            Log.d(TAG, "skip request allow=$allowPrediction clientPreedit=$hasClientPreedit inputPanelPreedit=$hasInputPanelPreedit selectionCollapsed=$selectionCollapsed")
            clearSuggestions(resetRequestState = true)
            return
        }

        val config = LanLlmPrefs.read(service.applicationContext)
        val fetchedBeforeCursor = fetchBeforeCursor(config)
        val beforeCursor = fetchedBeforeCursor.takeLast(config.maxContextChars)
        if (beforeCursor.isBlank()) {
            Log.d(TAG, "skip request blank beforeCursor")
            clearSuggestions(resetRequestState = true)
            return
        }
        if (!LanLlmRequestGate.shouldRequestPrediction(beforeCursor)) {
            Log.d(TAG, "skip request no predictive chars beforeCursor='${beforeCursor.take(40)}'")
            clearSuggestions(resetRequestState = true)
            return
        }
        val nowMs = System.currentTimeMillis()
        if (LanLlmRequestGate.shouldThrottlePureLongDigitInput(beforeCursor, lastPureLongDigitRequestAtMs, nowMs)) {
            Log.d(TAG, "skip request throttled pure long digits beforeCursor='${beforeCursor.take(40)}'")
            clearSuggestions(resetRequestState = false)
            return
        }
        updateCommitContext(fetchedBeforeCursor, config)
        if (predictionSuppressed) {
            if (commitRevision > suppressionUntilCommitRevision) {
                predictionSuppressed = false
                suppressionUntilCommitRevision = -1
            } else {
                Log.d(TAG, "skip request suppressed by commit revision=$commitRevision target=$suppressionUntilCommitRevision")
                clearSuggestions(resetRequestState = false)
                return
            }
        }
        if (beforeCursor == lastRequestedBeforeCursor) {
            Log.d(TAG, "skip request unchanged beforeCursor='${beforeCursor.take(40)}'")
            return
        }
        Log.d(TAG, "request beforeCursor='${beforeCursor.take(60)}'")

        if (panelVisible && shouldUseSingleTextPanel()) {
            requestPanelTextContent(beforeCursor, config)
            return
        }

        lastRequestedBeforeCursor = beforeCursor
        if (LanLlmRequestGate.isPureLongDigitInput(beforeCursor)) {
            lastPureLongDigitRequestAtMs = nowMs
        }
        val shouldShowQuestionAnswerPanel = taskMode == LanLlmTaskMode.QuestionAnswer && panelVisible
        val fallbackSuggestions = activeSuggestions.take(1)
        if (shouldShowQuestionAnswerPanel) {
            cancelPseudoStreaming()
            panelContentMode = PanelContentMode.QuestionAnswerLoading
            panelDisplayedText = ""
            dispatchPresentationChanged()
        }
        predictor.request(
            request = LanLlmPredictor.Request(
                beforeCursor = beforeCursor,
                recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
                historyText = buildHistoryText(config.maxContextChars),
                outputMode = LanLlmOutputMode.Suggestions,
                taskMode = taskMode,
                enableThinking = thinkingEnabled,
            ),
            onResult = { suggestions ->
                if (predictionSuppressed) {
                    Log.d(TAG, "drop result while suppressed values=$suggestions")
                    return@request
                }
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) {
                    return@request
                }
                val normalized = suggestions.mapNotNull {
                    sanitizeSingleCandidate(
                        candidate = it,
                        beforeCursor = beforeCursor,
                        outputMode = LanLlmOutputMode.Suggestions,
                        taskMode = taskMode,
                    ).ifBlank { null }
                }
                if (taskMode == LanLlmTaskMode.QuestionAnswer && shouldShowQuestionAnswerPanel) {
                    val answer = normalized.firstOrNull()
                    if (answer.isNullOrBlank()) {
                        activeSuggestions = fallbackSuggestions
                        resetPanelContentState()
                        dispatchPresentationChanged()
                        return@request
                    }
                    activeSuggestions = listOf(answer)
                    startQuestionAnswerPseudoStreaming(
                        answer = answer,
                        beforeCursor = beforeCursor,
                        outputMode = LanLlmOutputMode.Suggestions,
                    )
                    return@request
                }
                renderSuggestions(normalized)
            },
            onError = { error ->
                Log.e(TAG, "predict failed: ${error.message}", error)
                if (shouldShowQuestionAnswerPanel) {
                    activeSuggestions = fallbackSuggestions
                    resetPanelContentState()
                    dispatchPresentationChanged()
                }
            }
        )
    }

    private fun renderSuggestions(suggestions: List<String>) {
        cancelPseudoStreaming()
        activeSuggestions = suggestions.take(currentSuggestionLimit())
        if (activeSuggestions.isEmpty()) {
            Log.d(TAG, "hide ai suggestion bubble: empty suggestions")
            panelVisible = false
            resetPanelContentState()
            dispatchPresentationChanged()
            return
        }
        dispatchPresentationChanged()
    }

    private fun commitSuggestion(suggestion: String) {
        val config = LanLlmPrefs.read(service.applicationContext)
        noteCommittedText(suggestion, config)
        service.commitText(suggestion)
        clearSuggestions(resetRequestState = true)
    }

    private fun fetchBeforeCursor(config: LanLlmPrefs.Config): String {
        return service.currentInputConnection
            ?.getTextBeforeCursor(config.fetchWindowChars, 0)
            ?.toString()
            .orEmpty()
    }

    private fun updateCommitContext(beforeCursor: String, config: LanLlmPrefs.Config) {
        val previous = lastObservedBeforeCursor
        if (previous.isNotBlank() && beforeCursor.startsWith(previous)) {
            val appended = beforeCursor.removePrefix(previous).trim()
            if (appended.isNotBlank()) {
                noteCommittedText(appended, config)
            }
        }
        lastObservedBeforeCursor = beforeCursor
    }

    private fun noteCommittedText(text: String, config: LanLlmPrefs.Config) {
        val committed = text.trim()
        if (committed.isBlank()) return
        commitRevision += 1
        lastCommittedText = committed.takeLast(config.maxContextChars)
        recentCommittedSegments.addLast(lastCommittedText)
        while (recentCommittedSegments.size > 8) {
            recentCommittedSegments.removeFirst()
        }
        val knownBeforeCursor = fetchBeforeCursor(config)
        lastObservedBeforeCursor = (knownBeforeCursor + committed).takeLast(config.fetchWindowChars)
    }

    private fun buildHistoryText(maxContextChars: Int): String {
        if (maxContextChars <= 0 || recentCommittedSegments.isEmpty()) return ""
        val chunks = mutableListOf<String>()
        var total = 0
        for (index in recentCommittedSegments.lastIndex downTo 0) {
            val segment = recentCommittedSegments[index]
            if (segment.isBlank()) continue
            val addition = if (chunks.isEmpty()) segment.length else segment.length + 1
            if (total + addition > maxContextChars) continue
            chunks.add(segment)
            total += addition
        }
        return chunks.asReversed().joinToString(" ")
    }

    private fun clearSuggestions(resetRequestState: Boolean) {
        predictor.cancel()
        cancelPseudoStreaming()
        resetPanelContentState()
        activeSuggestions = emptyList()
        panelVisible = false
        dispatchPresentationChanged()
        if (resetRequestState) {
            lastRequestedBeforeCursor = ""
        }
    }

    private fun dispatchPresentationChanged() {
        val visibleSuggestions = activeSuggestions.take(currentSuggestionLimit())
        val mode = when {
            panelVisible && panelContentMode != PanelContentMode.Suggestions -> PresentationMode.PanelVisible
            visibleSuggestions.isEmpty() -> PresentationMode.Hidden
            panelVisible -> PresentationMode.PanelVisible
            anchorState != null -> PresentationMode.BubbleAnchored
            else -> PresentationMode.BubbleFallback
        }
        val panelSuggestions = when (panelContentMode) {
            PanelContentMode.Suggestions -> visibleSuggestions
            PanelContentMode.QuestionAnswerLoading,
            PanelContentMode.LongFormLoading -> {
                if (panelDisplayedText.isBlank()) emptyList() else listOf(panelDisplayedText)
            }
            PanelContentMode.QuestionAnswerStreaming,
            PanelContentMode.QuestionAnswerReady,
            PanelContentMode.LongFormStreaming,
            PanelContentMode.LongFormReady -> listOf(panelDisplayedText).filter(String::isNotBlank)
        }
        view.visibility = if (mode == PresentationMode.Hidden) View.GONE else View.INVISIBLE
        onPresentationChanged?.invoke(
            PresentationState(
                mode = mode,
                suggestions = visibleSuggestions,
                anchor = anchorState,
                panelSuggestions = panelSuggestions,
                isLongFormEnabled = longFormEnabled,
                isSingleTextMode = isSingleTextPanelMode(),
                isSingleTextLoading = isLoadingPanelMode(),
                isQuestionAnswerEnabled = taskMode == LanLlmTaskMode.QuestionAnswer,
                isThinkingEnabled = thinkingEnabled,
            )
        )
    }

    private fun refreshPredictionsForModeToggle() {
        lastRequestedBeforeCursor = ""
        activeSuggestions = activeSuggestions.take(currentSuggestionLimit())
        if (panelVisible && shouldUseSingleTextPanel()) {
            requestPredictionIfNeeded()
        } else {
            resetPanelContentState()
            requestPredictionIfNeeded()
            dispatchPresentationChanged()
        }
    }

    private fun currentSuggestionLimit(): Int {
        return if (taskMode == LanLlmTaskMode.QuestionAnswer || panelContentMode != PanelContentMode.Suggestions) {
            1
        } else {
            LanLlmPrefs.read(service.applicationContext).maxPredictionCandidates
        }
    }

    private fun resetPanelContentState() {
        panelContentMode = PanelContentMode.Suggestions
        panelDisplayedText = ""
    }

    private fun isLongFormPanelMode(): Boolean {
        return panelContentMode == PanelContentMode.LongFormLoading ||
            panelContentMode == PanelContentMode.LongFormStreaming ||
            panelContentMode == PanelContentMode.LongFormReady
    }

    private fun isSingleTextPanelMode(): Boolean {
        return panelContentMode != PanelContentMode.Suggestions ||
            taskMode == LanLlmTaskMode.QuestionAnswer ||
            longFormEnabled
    }

    private fun isLoadingPanelMode(): Boolean {
        return panelContentMode == PanelContentMode.QuestionAnswerLoading ||
            panelContentMode == PanelContentMode.QuestionAnswerStreaming ||
            panelContentMode == PanelContentMode.LongFormLoading ||
            panelContentMode == PanelContentMode.LongFormStreaming
    }

    private fun cancelPseudoStreaming() {
        pseudoStreamToken += 1
        pseudoStreamRunnable?.let(mainHandler::removeCallbacks)
        pseudoStreamRunnable = null
    }

    private fun shouldUseSingleTextPanel(): Boolean {
        return taskMode == LanLlmTaskMode.QuestionAnswer || longFormEnabled
    }

    private fun shouldKeepSingleTextPanelVisible(): Boolean {
        return panelVisible && shouldUseSingleTextPanel()
    }

    private fun currentPanelOutputMode(): LanLlmOutputMode {
        return if (longFormEnabled) LanLlmOutputMode.LongForm else LanLlmOutputMode.Suggestions
    }

    private fun requestPanelTextContent(
        beforeCursorOverride: String? = null,
        configOverride: LanLlmPrefs.Config? = null,
    ): Boolean {
        val config = configOverride ?: LanLlmPrefs.read(service.applicationContext)
        val beforeCursor = (beforeCursorOverride ?: fetchBeforeCursor(config)).takeLast(config.maxContextChars)
        if (beforeCursor.isBlank()) {
            panelVisible = false
            resetPanelContentState()
            dispatchPresentationChanged()
            return false
        }
        return if (taskMode == LanLlmTaskMode.QuestionAnswer) {
            requestQuestionAnswerContent(beforeCursor, config)
        } else {
            requestLongForm(beforeCursor, config)
        }
    }

    private fun requestQuestionAnswerContent(
        beforeCursor: String,
        config: LanLlmPrefs.Config,
    ): Boolean {
        val request = LanLlmPredictor.Request(
            beforeCursor = beforeCursor,
            recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
            historyText = buildHistoryText(config.maxContextChars),
            outputMode = currentPanelOutputMode(),
            taskMode = LanLlmTaskMode.QuestionAnswer,
            enableThinking = thinkingEnabled,
        )
        lastRequestedBeforeCursor = beforeCursor
        cancelPseudoStreaming()
        panelVisible = true
        panelContentMode = PanelContentMode.QuestionAnswerLoading
        activeSuggestions = activeSuggestions.take(1)
        panelDisplayedText = ""
        dispatchPresentationChanged()
        var streamedAnswer = ""
        var receivedStreamingPartial = false
        predictor.request(
            request = request,
            onPartial = { partial ->
                if (predictionSuppressed) return@request
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = beforeCursor,
                    outputMode = request.outputMode,
                    taskMode = LanLlmTaskMode.QuestionAnswer,
                )
                if (streamed.isBlank()) return@request
                cancelPseudoStreaming()
                receivedStreamingPartial = true
                streamedAnswer = streamed
                activeSuggestions = listOf(streamed)
                panelVisible = true
                panelDisplayedText = streamed
                panelContentMode = PanelContentMode.QuestionAnswerStreaming
                dispatchPresentationChanged()
            },
            onResult = { suggestions ->
                if (predictionSuppressed) return@request
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val answer = suggestions.firstOrNull()
                    ?.let {
                        sanitizeSingleCandidate(
                            candidate = it,
                            beforeCursor = beforeCursor,
                            outputMode = request.outputMode,
                            taskMode = LanLlmTaskMode.QuestionAnswer,
                        )
                    }
                    .orEmpty()
                if (answer.isBlank()) {
                    if (receivedStreamingPartial && streamedAnswer.isNotBlank()) {
                        activeSuggestions = listOf(streamedAnswer)
                        panelDisplayedText = streamedAnswer
                        panelContentMode = PanelContentMode.QuestionAnswerReady
                    } else {
                        resetPanelContentState()
                    }
                    dispatchPresentationChanged()
                    return@request
                }
                if (receivedStreamingPartial) {
                    activeSuggestions = listOf(answer)
                    panelDisplayedText = answer
                    panelContentMode = PanelContentMode.QuestionAnswerReady
                    dispatchPresentationChanged()
                } else {
                    startQuestionAnswerPseudoStreaming(
                        answer = answer,
                        beforeCursor = beforeCursor,
                        outputMode = request.outputMode,
                    )
                }
            },
            onError = { error ->
                Log.e(TAG, "question-answer predict failed: ${error.message}", error)
                resetPanelContentState()
                dispatchPresentationChanged()
            },
        )
        return true
    }

    private fun startQuestionAnswerPseudoStreaming(
        answer: String,
        beforeCursor: String,
        outputMode: LanLlmOutputMode,
    ) {
        cancelPseudoStreaming()
        val token = pseudoStreamToken
        val sanitizedAnswer = sanitizeSingleCandidate(
            candidate = answer,
            beforeCursor = beforeCursor,
            outputMode = outputMode,
            taskMode = LanLlmTaskMode.QuestionAnswer,
        )
        if (sanitizedAnswer.isBlank()) {
            resetPanelContentState()
            dispatchPresentationChanged()
            return
        }
        activeSuggestions = listOf(sanitizedAnswer)
        panelVisible = true
        val firstIndex = minOf(QA_PSEUDO_STREAM_CHUNK_CHARS, sanitizedAnswer.length)
        panelDisplayedText = sanitizedAnswer.substring(0, firstIndex)
        panelContentMode = if (firstIndex >= sanitizedAnswer.length) {
            PanelContentMode.QuestionAnswerReady
        } else {
            PanelContentMode.QuestionAnswerStreaming
        }
        dispatchPresentationChanged()
        if (firstIndex >= sanitizedAnswer.length) {
            pseudoStreamRunnable = null
            return
        }

        fun schedule(index: Int) {
            if (token != pseudoStreamToken) return
            if (index >= sanitizedAnswer.length) {
                panelDisplayedText = sanitizedAnswer
                panelContentMode = PanelContentMode.QuestionAnswerReady
                pseudoStreamRunnable = null
                dispatchPresentationChanged()
                return
            }
            val nextIndex = minOf(index + QA_PSEUDO_STREAM_CHUNK_CHARS, sanitizedAnswer.length)
            panelDisplayedText = sanitizedAnswer.substring(0, nextIndex)
            panelContentMode = PanelContentMode.QuestionAnswerStreaming
            dispatchPresentationChanged()
            val nextRunnable = Runnable { schedule(nextIndex) }
            pseudoStreamRunnable = nextRunnable
            mainHandler.postDelayed(nextRunnable, QA_PSEUDO_STREAM_DELAY_MS)
        }

        val startRunnable = Runnable { schedule(firstIndex) }
        pseudoStreamRunnable = startRunnable
        mainHandler.post(startRunnable)
    }

    private fun sanitizeSingleCandidate(
        candidate: String,
        beforeCursor: String,
        outputMode: LanLlmOutputMode,
        taskMode: LanLlmTaskMode,
    ): String {
        val compact = candidate.lineSequence()
            .map(String::trim)
            .joinToString(separator = "")
            .trim()
        if (compact.isBlank()) return ""
        val maxChars = when (LanLlmLanguageDetector.detect(beforeCursor)) {
            LanLlmLanguage.Chinese -> when {
                outputMode == LanLlmOutputMode.LongForm -> 50
                taskMode == LanLlmTaskMode.QuestionAnswer -> 24
                else -> 20
            }
            LanLlmLanguage.English -> when {
                outputMode == LanLlmOutputMode.LongForm -> 90
                taskMode == LanLlmTaskMode.QuestionAnswer -> 36
                else -> 30
            }
        }
        return compact.take(maxChars).trimEnd()
    }

    private fun CursorAnchorState.isInvalid(): Boolean {
        return horizontal.isNaN() ||
            bottom.isNaN() ||
            top.isNaN() ||
            parentWidth <= 0f ||
            parentHeight <= 0f
    }

    private fun CursorAnchorState.isCloseTo(other: CursorAnchorState): Boolean {
        return abs(horizontal - other.horizontal) < anchorUpdateThresholdPx &&
            abs(bottom - other.bottom) < anchorUpdateThresholdPx &&
            abs(top - other.top) < anchorUpdateThresholdPx &&
            abs(parentWidth - other.parentWidth) < 1f &&
            abs(parentHeight - other.parentHeight) < 1f
    }
}
