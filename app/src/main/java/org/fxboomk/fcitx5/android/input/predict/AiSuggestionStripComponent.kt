package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.dependency.UniqueViewComponent
import org.fxboomk.fcitx5.android.utils.withBatchEdit
import splitties.dimensions.dp
import kotlin.math.abs

private const val TAG = "LlmChip"
private const val QA_PSEUDO_STREAM_CHUNK_CHARS = 2
private const val QA_PSEUDO_STREAM_DELAY_MS = 24L
private const val FULL_TEXT_FETCH_CHARS = 20_000
private val translationMeaningPrefixes = listOf("释义：", "释义:", "含义：", "含义:")
private val translationPartOfSpeechRegex = Regex(
    """^(?:(?:n|v|vt|vi|adj|adv|prep|pron|conj|int|num|art|aux|phr)\.)\s*(.+)$""",
    RegexOption.IGNORE_CASE,
)

internal fun mergeSingleTextPanelResult(
    streamedText: String,
    finalText: String,
): String {
    val streamed = streamedText.trimEnd()
    val final = finalText.trimEnd()
    if (final.isBlank()) return streamed
    if (streamed.isBlank()) return final
    if (final == streamed) return final
    if (final.startsWith(streamed)) return final
    if (streamed.startsWith(final)) return streamed
    return if (final.length >= streamed.length) final else streamed
}

internal fun extractCommittedTranslation(displayText: String): String {
    val lines = displayText.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()
    if (lines.isEmpty()) return ""

    translationMeaningPrefixes.forEach { prefix ->
        lines.firstNotNullOfOrNull { line ->
            line.takeIf { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }?.let(::extractPrimaryMeaning)?.takeIf(String::isNotBlank)?.let { return it }
    }

    lines.firstNotNullOfOrNull { line ->
        translationPartOfSpeechRegex.matchEntire(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }?.let(::extractPrimaryMeaning)?.takeIf(String::isNotBlank)?.let { return it }

    return displayText.trim()
}

private fun extractPrimaryMeaning(gloss: String): String = gloss
    .split('；', ';', '/', '／', '、')
    .firstOrNull()
    .orEmpty()
    .trim()

internal fun hasInteractiveAiContent(
    state: AiSuggestionStripComponent.PresentationState,
): Boolean = state.isPanelOpen ||
    state.isLoading ||
    state.suggestions.isNotEmpty() ||
    state.panelSuggestions.isNotEmpty()

internal fun hasCompletedAiResult(
    state: AiSuggestionStripComponent.PresentationState,
): Boolean = !state.isLoading &&
    (state.suggestions.isNotEmpty() || state.panelSuggestions.isNotEmpty())

internal fun shouldAutoRequestRememberedTranslate(
    displayMode: LlmPrefs.PredictionDisplayMode,
    taskMode: LlmTaskMode,
    panelVisible: Boolean,
    isAutomatic: Boolean,
): Boolean = taskMode == LlmTaskMode.Translate &&
    (panelVisible || (!isAutomatic && displayMode != LlmPrefs.PredictionDisplayMode.FloatingWindow))

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
        val singleTextCommitText: String?,
        val isPanelOpen: Boolean,
        val isLongFormEnabled: Boolean,
        val isSingleTextMode: Boolean,
        val isLoading: Boolean,
        val loadingLabel: String?,
        val isQuestionAnswerEnabled: Boolean,
        val isThinkingEnabled: Boolean,
        val isTranslateEnabled: Boolean,
    )

    private data class InputTextSnapshot(
        val text: String,
        val beforeCursor: String,
        val afterCursor: String,
    )

    private enum class PanelContentMode {
        Suggestions,
        SuggestionsLoading,
        QuestionAnswerLoading,
        QuestionAnswerStreaming,
        QuestionAnswerReady,
        TranslateLoading,
        TranslateStreaming,
        TranslateReady,
        LongFormLoading,
        LongFormStreaming,
        LongFormReady,
    }

    private enum class PredictionTrigger {
        Automatic,
        Explicit,
    }

    private var predictorCreated = false
    private val predictor by lazy {
        predictorCreated = true
        LlmPredictor(service.applicationContext)
    }
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
    private var suggestionsPreservedOnCursorMove = false
    private var panelVisible = false
    private var anchorState: CursorAnchorState? = null
    private var panelContentMode = PanelContentMode.Suggestions
    private var panelDisplayedText = ""
    private var taskMode = LlmTaskMode.Completion
    private var longFormEnabled = false
    private var thinkingEnabled = false
    private var rememberedThinkingEnabled = false
    private var thinkingModeRuntime: LlmPrefs.Runtime? = null
    private var pseudoStreamToken = 0L
    private var pseudoStreamRunnable: Runnable? = null
    private var startInputPredictionGeneration = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestPresentationState = PresentationState(
        mode = PresentationMode.Hidden,
        suggestions = emptyList(),
        anchor = null,
        panelSuggestions = emptyList(),
        singleTextCommitText = null,
        isPanelOpen = false,
        isLongFormEnabled = false,
        isSingleTextMode = false,
        isLoading = false,
        loadingLabel = null,
        isQuestionAnswerEnabled = false,
        isThinkingEnabled = false,
        isTranslateEnabled = false,
    )

    var onPresentationChanged: ((PresentationState) -> Unit)? = null

    override val view = FrameLayout(themedContext).apply {
        id = View.generateViewId()
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(0, 0)
    }

    init {
        restoreRememberedMode(LlmPrefs.read(service.applicationContext))
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        val config = LlmPrefs.read(service.applicationContext)
        allowPrediction = config.enabled && !capFlags.has(CapabilityFlag.PasswordOrSensitive)
        hasClientPreedit = false
        hasInputPanelPreedit = false
        selectionCollapsed = true
        suggestionsPreservedOnCursorMove = false
        lastObservedBeforeCursor = ""
        lastCommittedText = ""
        lastPureLongDigitRequestAtMs = 0L
        commitRevision = 0
        predictionSuppressed = false
        suppressionUntilCommitRevision = -1
        recentCommittedSegments.clear()
        anchorState = null
        thinkingModeRuntime = null
        if (!config.enabled) {
            resetPanelContentState()
            clearSuggestions(resetRequestState = true)
            return
        }
        restoreRememberedMode(config)
        syncThinkingModeForRuntime(config)
        resetPanelContentState()
        clearSuggestions(resetRequestState = true)
        val generation = ++startInputPredictionGeneration
        mainHandler.post {
            if (generation == startInputPredictionGeneration) {
                requestPredictionIfNeeded()
            }
        }
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        hasClientPreedit = data.isNotEmpty()
        suggestionsPreservedOnCursorMove = false
        if (!shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        requestPredictionIfNeeded()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        hasInputPanelPreedit = data.preedit.isNotEmpty()
        suggestionsPreservedOnCursorMove = false
        if (!shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        requestPredictionIfNeeded()
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (!shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        if (suggestionsPreservedOnCursorMove) {
            return
        }
        requestPredictionIfNeeded()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        selectionCollapsed = start == end
        if (!selectionCollapsed || !shouldKeepSingleTextPanelVisible()) {
            collapsePanel()
        }
        if (selectionCollapsed && activeSuggestions.isNotEmpty()) {
            suggestionsPreservedOnCursorMove = true
            return
        }
        suggestionsPreservedOnCursorMove = false
        requestPredictionIfNeeded()
    }

    fun hasVisibleSuggestions(): Boolean = activeSuggestions.isNotEmpty()

    fun commitPrimarySuggestion(): Boolean {
        val suggestion = activeSuggestions.firstOrNull() ?: return false
        if (taskMode == LlmTaskMode.Translate || isTranslatePanelMode()) {
            commitTranslatedText(suggestion)
        } else {
            commitSuggestion(suggestion)
        }
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
            requestPanelTextContent(trigger = PredictionTrigger.Explicit)
        } else if (activeSuggestions.isNotEmpty()) {
            resetPanelContentState()
            dispatchPresentationChanged()
            true
        } else {
            panelContentMode = PanelContentMode.SuggestionsLoading
            panelDisplayedText = ""
            dispatchPresentationChanged()
            requestPredictionIfNeeded(PredictionTrigger.Explicit)
            true
        }
    }

    fun toggleThinkingMode(): Boolean {
        thinkingEnabled = !thinkingEnabled
        persistRememberedUiMode()
        refreshPredictionsForModeToggle()
        return thinkingEnabled
    }

    fun toggleQuestionAnswerMode(): Boolean {
        taskMode = if (taskMode == LlmTaskMode.QuestionAnswer) {
            LlmTaskMode.Completion
        } else {
            exitTranslateMode()
            LlmTaskMode.QuestionAnswer
        }
        persistRememberedUiMode()
        refreshPredictionsForModeToggle()
        return taskMode == LlmTaskMode.QuestionAnswer
    }

    fun toggleTranslateMode(): Boolean {
        taskMode = if (taskMode == LlmTaskMode.Translate) {
            LlmTaskMode.Completion
        } else {
            longFormEnabled = false
            LlmTaskMode.Translate
        }
        persistRememberedUiMode()
        refreshPredictionsForModeToggle()
        return taskMode == LlmTaskMode.Translate
    }

    fun toggleLongFormMode(): Boolean {
        longFormEnabled = !longFormEnabled
        if (longFormEnabled) {
            exitTranslateMode()
        }
        persistRememberedUiMode()
        refreshPredictionsForModeToggle()
        return longFormEnabled
    }

    private fun exitTranslateMode() {
        if (taskMode == LlmTaskMode.Translate) {
            taskMode = LlmTaskMode.Completion
        }
    }

    fun collapsePanel(): Boolean {
        if (!panelVisible) return false
        panelVisible = false
        resetPanelContentState()
        dispatchPresentationChanged()
        return true
    }

    fun isPanelVisible(): Boolean = panelVisible

    fun currentPresentationState(): PresentationState = latestPresentationState

    private fun requestLongForm(
        beforeCursor: String,
        config: LlmPrefs.Config,
        trigger: PredictionTrigger,
    ): Boolean {
        if (beforeCursor.isBlank()) return false
        syncThinkingModeForRuntime(config)

        val request = LlmPredictor.Request(
            beforeCursor = beforeCursor,
            recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
            historyText = buildHistoryText(config.maxContextChars),
            outputMode = LlmOutputMode.LongForm,
            taskMode = taskMode,
            enableThinking = thinkingEnabled,
        )
        panelVisible = true
        panelContentMode = PanelContentMode.LongFormLoading
        activeSuggestions = activeSuggestions.take(1)
        panelDisplayedText = ""
        dispatchPresentationChanged()
        var streamedLongForm = ""
        var receivedStreamingPartial = false
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
                            outputMode = LlmOutputMode.LongForm,
                            taskMode = taskMode,
                        )
                    }
                    .orEmpty()
                val mergedLongForm = mergeSingleTextPanelResult(
                    streamedText = streamedLongForm,
                    finalText = longForm,
                )
                if (mergedLongForm.isBlank()) {
                    resetPanelContentState()
                    dispatchPresentationChanged()
                    return@request
                }
                panelDisplayedText = mergedLongForm
                panelContentMode = PanelContentMode.LongFormReady
                dispatchPresentationChanged()
            },
            onError = { error ->
                Log.e(TAG, "long-form predict failed: ${error.message}", error)
                if (receivedStreamingPartial && streamedLongForm.isNotBlank()) {
                    panelDisplayedText = streamedLongForm
                    panelContentMode = PanelContentMode.LongFormReady
                } else {
                    resetPanelContentState()
                }
                dispatchPresentationChanged()
            },
            onPartial = { partial ->
                if (trigger == PredictionTrigger.Automatic && predictionSuppressed) return@request
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = beforeCursor,
                    outputMode = LlmOutputMode.LongForm,
                    taskMode = taskMode,
                )
                if (streamed.isBlank()) return@request
                receivedStreamingPartial = true
                streamedLongForm = streamed
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
        if (taskMode == LlmTaskMode.Translate || isTranslatePanelMode()) {
            commitTranslatedText(suggestion)
        } else {
            commitSuggestion(suggestion)
        }
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
        startInputPredictionGeneration += 1
        if (predictorCreated) {
            predictor.close()
        }
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

    private fun requestPredictionIfNeeded(trigger: PredictionTrigger = PredictionTrigger.Automatic) {
        val isAutomatic = trigger == PredictionTrigger.Automatic
        val keepSingleTextPanelVisible = shouldKeepSingleTextPanelVisible()
        if (!allowPrediction || (!keepSingleTextPanelVisible && (hasClientPreedit || hasInputPanelPreedit)) || !selectionCollapsed) {
            Log.d(TAG, "skip request allow=$allowPrediction clientPreedit=$hasClientPreedit inputPanelPreedit=$hasInputPanelPreedit selectionCollapsed=$selectionCollapsed")
            clearSuggestions(resetRequestState = true)
            return
        }

        val config = LlmPrefs.read(service.applicationContext)
        if (!config.enabled) {
            clearSuggestions(resetRequestState = true)
            return
        }
        syncThinkingModeForRuntime(config)
        if (isAutomatic && !config.autoPredictEnabled) {
            Log.d(TAG, "skip request auto prediction disabled")
            clearSuggestions(resetRequestState = true)
            return
        }
        if (taskMode == LlmTaskMode.Translate) {
            if (shouldAutoRequestRememberedTranslate(
                    displayMode = config.predictionDisplayMode,
                    taskMode = taskMode,
                    panelVisible = panelVisible,
                    isAutomatic = isAutomatic,
                )
            ) {
                requestPanelTextContent(configOverride = config, trigger = trigger)
            } else {
                clearSuggestions(resetRequestState = false)
            }
            return
        }
        val fetchedBeforeCursor = fetchBeforeCursor(config)
        val beforeCursor = fetchedBeforeCursor.takeLast(config.maxContextChars)
        if (beforeCursor.isBlank()) {
            Log.d(TAG, "skip request blank beforeCursor")
            clearSuggestions(resetRequestState = true)
            return
        }
        if (!LlmRequestGate.shouldRequestPrediction(beforeCursor)) {
            Log.d(TAG, "skip request no predictive chars beforeCursor='${beforeCursor.take(40)}'")
            clearSuggestions(resetRequestState = true)
            return
        }
        val nowMs = System.currentTimeMillis()
        if (isAutomatic &&
            LlmRequestGate.shouldThrottlePureLongDigitInput(beforeCursor, lastPureLongDigitRequestAtMs, nowMs)
        ) {
            Log.d(TAG, "skip request throttled pure long digits beforeCursor='${beforeCursor.take(40)}'")
            clearSuggestions(resetRequestState = false)
            return
        }
        updateCommitContext(fetchedBeforeCursor, config)
        if (isAutomatic && predictionSuppressed) {
            if (commitRevision > suppressionUntilCommitRevision) {
                predictionSuppressed = false
                suppressionUntilCommitRevision = -1
            } else {
                Log.d(TAG, "skip request suppressed by commit revision=$commitRevision target=$suppressionUntilCommitRevision")
                clearSuggestions(resetRequestState = false)
                return
            }
        }
        if (isAutomatic && beforeCursor == lastRequestedBeforeCursor) {
            Log.d(TAG, "skip request unchanged beforeCursor='${beforeCursor.take(40)}'")
            return
        }
        Log.d(TAG, "request beforeCursor='${beforeCursor.take(60)}'")

        if (panelVisible && shouldUseSingleTextPanel()) {
            requestPanelTextContent(beforeCursor, config, trigger)
            return
        }

        lastRequestedBeforeCursor = beforeCursor
        if (isAutomatic && LlmRequestGate.isPureLongDigitInput(beforeCursor)) {
            lastPureLongDigitRequestAtMs = nowMs
        }
        val shouldShowQuestionAnswerPanel = taskMode == LlmTaskMode.QuestionAnswer && panelVisible
        val shouldShowSuggestionsLoadingPanel = panelVisible && panelContentMode == PanelContentMode.SuggestionsLoading
        val fallbackSuggestions = activeSuggestions.take(1)
        if (shouldShowQuestionAnswerPanel) {
            cancelPseudoStreaming()
            panelContentMode = PanelContentMode.QuestionAnswerLoading
            panelDisplayedText = ""
            dispatchPresentationChanged()
        }
        predictor.request(
            request = LlmPredictor.Request(
                beforeCursor = beforeCursor,
                recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
                historyText = buildHistoryText(config.maxContextChars),
                outputMode = LlmOutputMode.Suggestions,
                taskMode = taskMode,
                enableThinking = thinkingEnabled,
            ),
            onResult = { suggestions ->
                if (isAutomatic && predictionSuppressed) {
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
                        outputMode = LlmOutputMode.Suggestions,
                        taskMode = taskMode,
                    ).ifBlank { null }
                }
                if (taskMode == LlmTaskMode.QuestionAnswer && shouldShowQuestionAnswerPanel) {
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
                        outputMode = LlmOutputMode.Suggestions,
                    )
                    return@request
                }
                if (shouldShowSuggestionsLoadingPanel && normalized.isEmpty()) {
                    panelVisible = false
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
        if (panelContentMode == PanelContentMode.SuggestionsLoading) {
            resetPanelContentState()
        }
        dispatchPresentationChanged()
    }

    private fun commitSuggestion(suggestion: String) {
        val config = LlmPrefs.read(service.applicationContext)
        val selection = service.currentInputSelection
        val replacedText = if (selection.start == selection.end) {
            ""
        } else {
            service.currentInputConnection
                ?.getSelectedText(0)
                ?.toString()
                .orEmpty()
        }
        service.recordAiInsertionUndoSnapshot(
            insertedText = suggestion,
            replacedText = replacedText,
            selectionStart = selection.start,
            selectionEnd = selection.end,
        )
        noteCommittedText(suggestion, config)
        service.commitText(suggestion)
        clearSuggestions(resetRequestState = true)
    }

    private fun commitTranslatedText(translation: String) {
        val config = LlmPrefs.read(service.applicationContext)
        val snapshot = fetchEntireInputText(config)
        val inputConnection = service.currentInputConnection
        if (inputConnection == null) {
            commitSuggestion(translation)
            return
        }
        val selection = service.currentInputSelection
        service.recordAiRewriteUndoSnapshot(
            beforeCursor = snapshot.beforeCursor,
            afterCursor = snapshot.afterCursor,
            selectionStart = selection.start,
            selectionEnd = selection.end,
        )
        noteCommittedText(translation, config)
        lastObservedBeforeCursor = translation.takeLast(config.fetchWindowChars)
        inputConnection.withBatchEdit {
            finishComposingText()
            deleteSurroundingText(snapshot.beforeCursor.length, snapshot.afterCursor.length)
            commitText(translation, 1)
        }
        clearSuggestions(resetRequestState = true)
    }

    private fun fetchBeforeCursor(config: LlmPrefs.Config): String {
        return service.currentInputConnection
            ?.getTextBeforeCursor(config.fetchWindowChars, 0)
            ?.toString()
            .orEmpty()
    }

    private fun fetchEntireInputText(config: LlmPrefs.Config): InputTextSnapshot {
        val inputConnection = service.currentInputConnection
            ?: return InputTextSnapshot(text = "", beforeCursor = "", afterCursor = "")
        val fetchChars = maxOf(config.fetchWindowChars, FULL_TEXT_FETCH_CHARS)
        val beforeCursor = inputConnection.getTextBeforeCursor(fetchChars, 0)?.toString().orEmpty()
        val afterCursor = inputConnection.getTextAfterCursor(fetchChars, 0)?.toString().orEmpty()
        val extractedText = inputConnection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty()
        return InputTextSnapshot(
            text = extractedText.ifBlank { beforeCursor + afterCursor },
            beforeCursor = beforeCursor,
            afterCursor = afterCursor,
        )
    }

    private fun updateCommitContext(beforeCursor: String, config: LlmPrefs.Config) {
        val previous = lastObservedBeforeCursor
        if (previous.isNotBlank() && beforeCursor.startsWith(previous)) {
            val appended = beforeCursor.removePrefix(previous).trim()
            if (appended.isNotBlank()) {
                noteCommittedText(appended, config)
            }
        }
        lastObservedBeforeCursor = beforeCursor
    }

    private fun noteCommittedText(text: String, config: LlmPrefs.Config) {
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
        if (predictorCreated) {
            predictor.cancel()
        }
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
        val config = LlmPrefs.read(service.applicationContext)
        val visibleSuggestions = activeSuggestions.take(currentSuggestionLimit())
        val baseMode = when {
            panelVisible && panelContentMode != PanelContentMode.Suggestions -> PresentationMode.PanelVisible
            visibleSuggestions.isEmpty() -> PresentationMode.Hidden
            panelVisible -> PresentationMode.PanelVisible
            anchorState != null -> PresentationMode.BubbleAnchored
            else -> PresentationMode.BubbleFallback
        }
        val mode = if (config.predictionDisplayMode != LlmPrefs.PredictionDisplayMode.FloatingWindow) {
            PresentationMode.Hidden
        } else {
            baseMode
        }
        val panelSuggestions = when (panelContentMode) {
            PanelContentMode.Suggestions -> visibleSuggestions
            PanelContentMode.SuggestionsLoading -> emptyList()
            PanelContentMode.QuestionAnswerLoading,
            PanelContentMode.LongFormLoading -> {
                if (panelDisplayedText.isBlank()) emptyList() else listOf(panelDisplayedText)
            }
            PanelContentMode.QuestionAnswerStreaming,
            PanelContentMode.QuestionAnswerReady,
            PanelContentMode.TranslateLoading -> {
                if (panelDisplayedText.isBlank()) emptyList() else listOf(panelDisplayedText)
            }
            PanelContentMode.TranslateStreaming,
            PanelContentMode.TranslateReady,
            PanelContentMode.LongFormStreaming,
            PanelContentMode.LongFormReady -> listOf(panelDisplayedText).filter(String::isNotBlank)
        }
        val singleTextCommitText = when {
            panelSuggestions.isEmpty() -> null
            panelContentMode == PanelContentMode.TranslateLoading ||
                panelContentMode == PanelContentMode.TranslateStreaming ||
                panelContentMode == PanelContentMode.TranslateReady ->
                extractCommittedTranslation(panelSuggestions.first())
                    .ifBlank { null }
            else -> panelSuggestions.first()
        }
        view.visibility = if (mode == PresentationMode.Hidden) View.GONE else View.INVISIBLE
        latestPresentationState = PresentationState(
            mode = mode,
            suggestions = visibleSuggestions,
            anchor = anchorState,
            panelSuggestions = panelSuggestions,
            singleTextCommitText = singleTextCommitText,
            isPanelOpen = panelVisible,
            isLongFormEnabled = longFormEnabled,
            isSingleTextMode = isSingleTextPanelMode(),
            isLoading = isLoadingPanelMode(),
            loadingLabel = loadingLabel(),
            isQuestionAnswerEnabled = taskMode == LlmTaskMode.QuestionAnswer,
            isThinkingEnabled = thinkingEnabled,
            isTranslateEnabled = taskMode == LlmTaskMode.Translate,
        )
        onPresentationChanged?.invoke(
            latestPresentationState
        )
    }

    private fun syncThinkingModeForRuntime(config: LlmPrefs.Config) {
        if (thinkingModeRuntime == config.runtime) return
        rememberedThinkingEnabled = LlmPrefs.readRememberedUiMode(
            prefs = PreferenceManager.getDefaultSharedPreferences(service.applicationContext),
            defaultThinkingEnabled = config.isLocalOnDevice,
        ).thinkingEnabled
        thinkingEnabled = rememberedThinkingEnabled
        thinkingModeRuntime = config.runtime
    }

    private fun restoreRememberedMode(config: LlmPrefs.Config) {
        val remembered = LlmPrefs.readRememberedUiMode(
            prefs = PreferenceManager.getDefaultSharedPreferences(service.applicationContext),
            defaultThinkingEnabled = config.isLocalOnDevice,
        )
        taskMode = remembered.taskMode
        longFormEnabled = remembered.longFormEnabled
        rememberedThinkingEnabled = remembered.thinkingEnabled
        thinkingEnabled = rememberedThinkingEnabled
    }

    private fun persistRememberedUiMode() {
        rememberedThinkingEnabled = thinkingEnabled
        LlmPrefs.persistRememberedUiMode(
            prefs = PreferenceManager.getDefaultSharedPreferences(service.applicationContext),
            taskMode = taskMode,
            longFormEnabled = longFormEnabled,
            thinkingEnabled = rememberedThinkingEnabled,
        )
    }

    private fun refreshPredictionsForModeToggle() {
        lastRequestedBeforeCursor = ""
        activeSuggestions = activeSuggestions.take(currentSuggestionLimit())
        if (shouldUseSingleTextPanel()) {
            panelVisible = true
            requestPredictionIfNeeded(PredictionTrigger.Explicit)
        } else {
            resetPanelContentState()
            requestPredictionIfNeeded(PredictionTrigger.Explicit)
            dispatchPresentationChanged()
        }
    }

    private fun currentSuggestionLimit(): Int {
        return if (
            taskMode == LlmTaskMode.QuestionAnswer ||
            taskMode == LlmTaskMode.Translate ||
            panelContentMode != PanelContentMode.Suggestions &&
            panelContentMode != PanelContentMode.SuggestionsLoading
        ) {
            1
        } else {
            LlmPrefs.read(service.applicationContext).maxPredictionCandidates
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

    private fun isQuestionAnswerPanelMode(): Boolean {
        return panelContentMode == PanelContentMode.QuestionAnswerLoading ||
            panelContentMode == PanelContentMode.QuestionAnswerStreaming ||
            panelContentMode == PanelContentMode.QuestionAnswerReady
    }

    private fun isTranslatePanelMode(): Boolean {
        return panelContentMode == PanelContentMode.TranslateLoading ||
            panelContentMode == PanelContentMode.TranslateStreaming ||
            panelContentMode == PanelContentMode.TranslateReady
    }

    private fun isSingleTextPanelMode(): Boolean {
        return isQuestionAnswerPanelMode() ||
            isTranslatePanelMode() ||
            isLongFormPanelMode() ||
            taskMode == LlmTaskMode.QuestionAnswer ||
            taskMode == LlmTaskMode.Translate ||
            longFormEnabled
    }

    private fun isLoadingPanelMode(): Boolean {
        return panelContentMode == PanelContentMode.SuggestionsLoading ||
            panelContentMode == PanelContentMode.QuestionAnswerLoading ||
            panelContentMode == PanelContentMode.QuestionAnswerStreaming ||
            panelContentMode == PanelContentMode.TranslateLoading ||
            panelContentMode == PanelContentMode.TranslateStreaming ||
            panelContentMode == PanelContentMode.LongFormLoading ||
            panelContentMode == PanelContentMode.LongFormStreaming
    }

    private fun cancelPseudoStreaming() {
        pseudoStreamToken += 1
        pseudoStreamRunnable?.let(mainHandler::removeCallbacks)
        pseudoStreamRunnable = null
    }

    private fun shouldUseSingleTextPanel(): Boolean {
        return taskMode == LlmTaskMode.QuestionAnswer ||
            taskMode == LlmTaskMode.Translate ||
            longFormEnabled
    }

    private fun shouldKeepSingleTextPanelVisible(): Boolean {
        return panelVisible && shouldUseSingleTextPanel()
    }

    private fun currentPanelOutputMode(): LlmOutputMode {
        return if (longFormEnabled) LlmOutputMode.LongForm else LlmOutputMode.Suggestions
    }

    private fun requestPanelTextContent(
        beforeCursorOverride: String? = null,
        configOverride: LlmPrefs.Config? = null,
        trigger: PredictionTrigger = PredictionTrigger.Automatic,
    ): Boolean {
        val config = configOverride ?: LlmPrefs.read(service.applicationContext)
        if (taskMode == LlmTaskMode.Translate) {
            return requestTranslateContent(config, trigger)
        }
        val beforeCursor = (beforeCursorOverride ?: fetchBeforeCursor(config)).takeLast(config.maxContextChars)
        if (beforeCursor.isBlank()) {
            panelVisible = false
            resetPanelContentState()
            dispatchPresentationChanged()
            return false
        }
        return if (taskMode == LlmTaskMode.QuestionAnswer) {
            requestQuestionAnswerContent(beforeCursor, config, trigger)
        } else {
            requestLongForm(beforeCursor, config, trigger)
        }
    }

    private fun requestTranslateContent(
        config: LlmPrefs.Config,
        trigger: PredictionTrigger,
    ): Boolean {
        syncThinkingModeForRuntime(config)
        val inputSnapshot = fetchEntireInputText(config)
        val sourceText = inputSnapshot.text.trim()
        if (sourceText.isBlank()) {
            panelVisible = false
            resetPanelContentState()
            dispatchPresentationChanged()
            return false
        }
        val request = LlmPredictor.Request(
            beforeCursor = sourceText,
            outputMode = LlmOutputMode.Suggestions,
            taskMode = LlmTaskMode.Translate,
            enableThinking = thinkingEnabled,
        )
        lastRequestedBeforeCursor = sourceText
        cancelPseudoStreaming()
        panelVisible = true
        panelContentMode = PanelContentMode.TranslateLoading
        activeSuggestions = activeSuggestions.take(1)
        panelDisplayedText = ""
        dispatchPresentationChanged()
        var streamedTranslation = ""
        var receivedStreamingPartial = false
        predictor.request(
            request = request,
            onPartial = { partial ->
                if (trigger == PredictionTrigger.Automatic && predictionSuppressed) return@request
                val latestText = fetchEntireInputText(config).text.trim()
                if (latestText != sourceText) return@request
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = sourceText,
                    outputMode = LlmOutputMode.Suggestions,
                    taskMode = LlmTaskMode.Translate,
                )
                if (streamed.isBlank()) return@request
                receivedStreamingPartial = true
                streamedTranslation = streamed
                activeSuggestions = listOf(extractCommittedTranslation(streamed).ifBlank { streamed })
                panelVisible = true
                panelDisplayedText = streamed
                panelContentMode = PanelContentMode.TranslateStreaming
                dispatchPresentationChanged()
            },
            onResult = { suggestions ->
                if (trigger == PredictionTrigger.Automatic && predictionSuppressed) return@request
                val latestText = fetchEntireInputText(config).text.trim()
                if (latestText != sourceText) return@request
                val translated = suggestions.firstOrNull()
                    ?.let {
                        sanitizeSingleCandidate(
                            candidate = it,
                            beforeCursor = sourceText,
                            outputMode = LlmOutputMode.Suggestions,
                            taskMode = LlmTaskMode.Translate,
                        )
                    }
                    .orEmpty()
                val mergedTranslation = mergeSingleTextPanelResult(
                    streamedText = streamedTranslation,
                    finalText = translated,
                )
                if (mergedTranslation.isBlank()) {
                    resetPanelContentState()
                    dispatchPresentationChanged()
                    return@request
                }
                activeSuggestions = listOf(
                    extractCommittedTranslation(mergedTranslation).ifBlank { mergedTranslation }
                )
                panelVisible = true
                panelDisplayedText = mergedTranslation
                panelContentMode = PanelContentMode.TranslateReady
                dispatchPresentationChanged()
            },
            onError = { error ->
                Log.e(TAG, "translate predict failed: ${error.message}", error)
                if (receivedStreamingPartial && streamedTranslation.isNotBlank()) {
                    activeSuggestions = listOf(
                        extractCommittedTranslation(streamedTranslation).ifBlank { streamedTranslation }
                    )
                    panelVisible = true
                    panelDisplayedText = streamedTranslation
                    panelContentMode = PanelContentMode.TranslateReady
                } else {
                    resetPanelContentState()
                }
                dispatchPresentationChanged()
            },
        )
        return true
    }

    private fun requestQuestionAnswerContent(
        beforeCursor: String,
        config: LlmPrefs.Config,
        trigger: PredictionTrigger,
    ): Boolean {
        syncThinkingModeForRuntime(config)
        val request = LlmPredictor.Request(
            beforeCursor = beforeCursor,
            recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
            historyText = buildHistoryText(config.maxContextChars),
            outputMode = currentPanelOutputMode(),
            taskMode = LlmTaskMode.QuestionAnswer,
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
                if (trigger == PredictionTrigger.Automatic && predictionSuppressed) return@request
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = beforeCursor,
                    outputMode = request.outputMode,
                    taskMode = LlmTaskMode.QuestionAnswer,
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
                if (trigger == PredictionTrigger.Automatic && predictionSuppressed) return@request
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val answer = suggestions.firstOrNull()
                    ?.let {
                        sanitizeSingleCandidate(
                            candidate = it,
                            beforeCursor = beforeCursor,
                            outputMode = request.outputMode,
                            taskMode = LlmTaskMode.QuestionAnswer,
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
                    val mergedAnswer = mergeSingleTextPanelResult(
                        streamedText = streamedAnswer,
                        finalText = answer,
                    )
                    activeSuggestions = listOf(mergedAnswer)
                    panelDisplayedText = mergedAnswer
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
        outputMode: LlmOutputMode,
    ) {
        cancelPseudoStreaming()
        val token = pseudoStreamToken
        val sanitizedAnswer = sanitizeSingleCandidate(
            candidate = answer,
            beforeCursor = beforeCursor,
            outputMode = outputMode,
            taskMode = LlmTaskMode.QuestionAnswer,
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
        outputMode: LlmOutputMode,
        taskMode: LlmTaskMode,
    ): String {
        val compact = if (taskMode == LlmTaskMode.Translate) {
            LlmSuggestionParser.normalizeSingleTextDisplay(candidate)
        } else {
            candidate.lineSequence()
                .map(String::trim)
                .joinToString(separator = "")
                .trim()
        }
        if (compact.isBlank()) return ""
        if (
            taskMode == LlmTaskMode.Translate ||
            taskMode == LlmTaskMode.QuestionAnswer ||
            outputMode == LlmOutputMode.LongForm
        ) {
            return compact.trimEnd()
        }
        val maxChars = when (LlmLanguageDetector.detect(beforeCursor)) {
            LlmLanguage.Chinese -> 20
            LlmLanguage.English -> 30
        }
        return compact.take(maxChars).trimEnd()
    }

    private fun loadingLabel(): String? = when (panelContentMode) {
        PanelContentMode.SuggestionsLoading -> themedContext.getString(R.string.ai_clip_suggestions_loading)
        PanelContentMode.QuestionAnswerLoading,
        PanelContentMode.QuestionAnswerStreaming -> themedContext.getString(R.string.ai_clip_answer_loading)
        PanelContentMode.TranslateLoading,
        PanelContentMode.TranslateStreaming -> themedContext.getString(R.string.ai_clip_translate_loading)
        PanelContentMode.LongFormLoading,
        PanelContentMode.LongFormStreaming -> themedContext.getString(R.string.ai_clip_long_form_loading)
        else -> null
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
