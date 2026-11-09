package org.fxboomk.fcitx5.android.input.predict

import timber.log.Timber

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.preference.PreferenceManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.EditorSessionTracker
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

internal fun questionAnswerCommitText(answer: String, fromExpandedArea: Boolean): String {
    return if (fromExpandedArea) {
        "\n" + answer.trimStart('\n')
    } else {
        answer
    }
}

internal fun expandedCommitText(state: AiSuggestionStripComponent.PresentationState): String? =
    state.singleTextCommitText?.takeIf { it.isNotBlank() }
        ?: state.panelSuggestions.firstOrNull { it.isNotBlank() }
        ?: state.suggestions.firstOrNull { it.isNotBlank() }

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
    state.panelSuggestions.isNotEmpty() ||
    state.errorMessage != null

internal fun hasCompletedAiResult(
    state: AiSuggestionStripComponent.PresentationState,
): Boolean = !state.isLoading &&
    (state.suggestions.isNotEmpty() || state.panelSuggestions.isNotEmpty())

internal fun canCommitAiSuggestionFromUi(
    suggestion: String,
    state: AiSuggestionStripComponent.PresentationState,
): Boolean = suggestion.isNotBlank() &&
    (suggestion in state.suggestions || suggestion in state.panelSuggestions ||
        suggestion == state.singleTextCommitText)

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
        val isTranslateEnabled: Boolean,
        val errorMessage: String? = null,
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
    private var errorMessage: String? = null
    private var suggestionsPreservedOnCursorMove = false
    private var panelVisible = false
    private var anchorState: CursorAnchorState? = null
    private var panelContentMode = PanelContentMode.Suggestions
    private var panelDisplayedText = ""
    private var taskMode = LlmTaskMode.Completion
    private var longFormEnabled = false
    private var briefAnswerEnabled = false
    private data class SelectionRequest(
        val selected: SelectedAiText,
        val connection: InputConnection,
        val token: EditorSessionTracker.Token,
        val previousTaskMode: LlmTaskMode,
        val previousLongFormEnabled: Boolean,
        val previousBriefAnswerEnabled: Boolean,
    )
    private var selectionRequest: SelectionRequest? = null
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
        isTranslateEnabled = false,
        errorMessage = null,
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

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean) {
        restoreModeAfterSelectionRequest()
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
        if (!config.enabled) {
            resetPanelContentState()
            clearSuggestions(resetRequestState = true)
            return
        }
        restoreRememberedMode(config)
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
        if (selectionRequest != null) {
            validateSelectionRequest()
            return
        }
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
        if (selectionRequest != null) return commitSelectedTextResult(suggestion)
        if (taskMode == LlmTaskMode.Translate || isTranslatePanelMode()) {
            commitTranslatedText(suggestion)
        } else {
            commitSuggestion(suggestion)
        }
        return true
    }

    fun currentExpandedCommitText(): String? = expandedCommitText(latestPresentationState)

    fun dismissVisibleSuggestions(): Boolean {
        if (!hasVisibleSuggestions()) return false
        suppressPredictionUntilNextCommit("dismiss-visible-suggestions")
        return true
    }

    fun dismissPredictionError(): Boolean {
        if (errorMessage == null) return false
        suppressPredictionUntilNextCommit("dismiss-prediction-error")
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

    fun selectPersona(value: String): Boolean {
        if (selectionRequest != null) clearSuggestions(resetRequestState = true)
        val normalized = value.ifBlank { LlmPrefs.PersonaPreset.Custom.value }
        val prefs = PreferenceManager.getDefaultSharedPreferences(service.applicationContext)
        prefs.edit().putString(LlmPrefs.KEY_PERSONA_PRESET, normalized).apply()
        refreshPredictionsForModeToggle()
        return true
    }

    fun toggleQuestionAnswerMode(): Boolean {
        if (selectionRequest != null) clearSuggestions(resetRequestState = true)
        briefAnswerEnabled = false
        taskMode = if (taskMode == LlmTaskMode.QuestionAnswer) {
            LlmTaskMode.Completion
        } else {
            longFormEnabled = false
            exitTranslateMode()
            LlmTaskMode.QuestionAnswer
        }
        persistRememberedUiMode()
        refreshPredictionsForModeToggle()
        return taskMode == LlmTaskMode.QuestionAnswer
    }

    fun toggleTranslateMode(): Boolean {
        if (selectionRequest != null) clearSuggestions(resetRequestState = true)
        briefAnswerEnabled = false
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
        if (selectionRequest != null) clearSuggestions(resetRequestState = true)
        briefAnswerEnabled = false
        longFormEnabled = !longFormEnabled
        if (longFormEnabled) {
            taskMode = LlmTaskMode.Completion
        }
        persistRememberedUiMode()
        refreshPredictionsForModeToggle()
        return longFormEnabled
    }

    internal fun requestAction(action: AiCandidateAction): Boolean {
        if (selectionRequest != null) clearSuggestions(resetRequestState = true)
        if (action == AiCandidateAction.Settings) return false
        val config = LlmPrefs.read(service.applicationContext)
        val flags = CapabilityFlags.fromEditorInfo(service.currentInputEditorInfo)
        if (!config.enabled || flags.has(CapabilityFlag.PasswordOrSensitive) ||
            hasClientPreedit || hasInputPanelPreedit || !selectionCollapsed ||
            service.hardwarePredictionSession.isSuppressed
        ) return false
        taskMode = when (action) {
            AiCandidateAction.Polish -> LlmTaskMode.Completion
            AiCandidateAction.Translate -> LlmTaskMode.Translate
            AiCandidateAction.Reply -> LlmTaskMode.QuestionAnswer
            AiCandidateAction.Settings -> return false
        }
        longFormEnabled = action == AiCandidateAction.Polish
        briefAnswerEnabled = action == AiCandidateAction.Reply
        persistRememberedUiMode()
        if (predictorCreated) predictor.cancel()
        cancelPseudoStreaming()
        lastRequestedBeforeCursor = ""
        activeSuggestions = emptyList()
        errorMessage = null
        resetPanelContentState()
        return requestPanelTextContent(configOverride = config, trigger = PredictionTrigger.Explicit)
    }

    /** Explicit editing actions process the selection, not the text around the cursor. */
    internal fun requestSelectionAction(action: AiCandidateAction, selectedText: String): Boolean {
        val config = LlmPrefs.read(service.applicationContext)
        val request = selectedAiTextRequest(action, selectedText, config.thinkingEffort) ?: return false
        val info = service.currentInputEditorInfo ?: return false
        if (!config.enabled || !config.isUsable ||
            CapabilityFlags.fromEditorInfo(info).has(CapabilityFlag.PasswordOrSensitive) ||
            hasClientPreedit || hasInputPanelPreedit || service.hardwarePredictionSession.isSuppressed
        ) return false
        val connection = service.applicationInputConnection ?: return false
        val token = service.editorSession.capture() ?: return false
        val range = service.currentInputSelection
        if (range.start == range.end || connection.getSelectedText(0)?.toString() != selectedText) return false
        val selected = SelectedAiText(
            text = selectedText,
            start = range.start,
            end = range.end,
            before = connection.getTextBeforeCursor(FULL_TEXT_FETCH_CHARS, 0)?.toString().orEmpty(),
            after = connection.getTextAfterCursor(FULL_TEXT_FETCH_CHARS, 0)?.toString().orEmpty(),
        )
        clearSuggestions(resetRequestState = true)
        val session = SelectionRequest(selected, connection, token, taskMode, longFormEnabled, briefAnswerEnabled)
        selectionRequest = session
        selectionCollapsed = false
        taskMode = request.taskMode
        longFormEnabled = request.outputMode == LlmOutputMode.LongForm
        briefAnswerEnabled = request.outputMode == LlmOutputMode.BriefAnswer
        panelVisible = true
        panelContentMode = selectionPanelMode(action, loading = true)
        lastRequestedBeforeCursor = selectedText
        dispatchPresentationChanged()
        var streamedText = ""
        predictor.request(
            request = request,
            onPartial = { partial ->
                if (selectionRequest !== session || !validateSelectionRequest()) return@request
                val sanitized = sanitizeSingleCandidate(partial, selectedText, request.outputMode, request.taskMode, isPartial = true)
                if (sanitized.isBlank()) return@request
                streamedText = sanitized
                panelDisplayedText = sanitized
                activeSuggestions = listOf(selectionCommitText(action, sanitized))
                panelContentMode = selectionPanelMode(action, streaming = true)
                dispatchPresentationChanged()
            },
            onResult = { suggestions ->
                if (selectionRequest !== session || !validateSelectionRequest()) return@request
                val finalText = suggestions.firstOrNull()?.let {
                    sanitizeSingleCandidate(it, selectedText, request.outputMode, request.taskMode)
                }.orEmpty()
                val result = mergeSingleTextPanelResult(streamedText, finalText)
                if (result.isBlank()) {
                    clearSuggestions(resetRequestState = true)
                    return@request
                }
                panelDisplayedText = result
                activeSuggestions = listOf(selectionCommitText(action, result))
                panelContentMode = selectionPanelMode(action)
                dispatchPresentationChanged()
            },
            onError = { error ->
                if (selectionRequest !== session || !validateSelectionRequest()) return@request
                clearSuggestions(resetRequestState = true)
                errorMessage = predictionErrorMessage(error)
                panelVisible = true
                dispatchPresentationChanged()
            },
        )
        return true
    }

    private fun selectionPanelMode(action: AiCandidateAction, loading: Boolean = false, streaming: Boolean = false): PanelContentMode =
        when (action) {
            AiCandidateAction.Translate -> when {
                loading -> PanelContentMode.TranslateLoading
                streaming -> PanelContentMode.TranslateStreaming
                else -> PanelContentMode.TranslateReady
            }
            AiCandidateAction.Reply -> when {
                loading -> PanelContentMode.QuestionAnswerLoading
                streaming -> PanelContentMode.QuestionAnswerStreaming
                else -> PanelContentMode.QuestionAnswerReady
            }
            else -> when {
                loading -> PanelContentMode.LongFormLoading
                streaming -> PanelContentMode.LongFormStreaming
                else -> PanelContentMode.LongFormReady
            }
        }

    private fun selectionCommitText(action: AiCandidateAction, text: String): String =
        if (action == AiCandidateAction.Translate) extractCommittedTranslation(text) else text

    private fun validateSelectionRequest(): Boolean {
        val session = selectionRequest ?: return false
        val range = service.currentInputSelection
        val info = service.currentInputEditorInfo
        val current = session.token.isCurrent() &&
            service.applicationInputConnection === session.connection &&
            LlmPrefs.read(service.applicationContext).enabled &&
            !service.hardwarePredictionSession.isSuppressed &&
            info != null && !CapabilityFlags.fromEditorInfo(info).has(CapabilityFlag.PasswordOrSensitive) &&
            !hasClientPreedit && !hasInputPanelPreedit &&
            session.selected.matches(
                range.start, range.end,
                session.connection.getSelectedText(0)?.toString().orEmpty(),
                session.connection.getTextBeforeCursor(FULL_TEXT_FETCH_CHARS, 0)?.toString().orEmpty(),
                session.connection.getTextAfterCursor(FULL_TEXT_FETCH_CHARS, 0)?.toString().orEmpty(),
            )
        if (!current) clearSuggestions(resetRequestState = true)
        return current
    }

    private fun commitSelectedTextResult(text: String): Boolean {
        if (!validateSelectionRequest()) return false
        val selected = checkNotNull(selectionRequest).selected
        val config = LlmPrefs.read(service.applicationContext)
        service.recordAiInsertionUndoSnapshot(text, selected.text, selected.start, selected.end)
        noteCommittedText(text, config)
        service.commitText(text)
        clearSuggestions(resetRequestState = true)
        return true
    }

    private fun restoreModeAfterSelectionRequest() {
        val session = selectionRequest ?: return
        taskMode = session.previousTaskMode
        longFormEnabled = session.previousLongFormEnabled
        briefAnswerEnabled = session.previousBriefAnswerEnabled
        selectionRequest = null
    }

    private fun exitTranslateMode() {
        if (taskMode == LlmTaskMode.Translate) {
            taskMode = LlmTaskMode.Completion
        }
    }

    fun collapsePanel(): Boolean {
        if (!panelVisible) return false
        if (selectionRequest != null) {
            clearSuggestions(resetRequestState = true)
            return true
        }
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

        val request = LlmPredictor.Request(
            beforeCursor = beforeCursor,
            recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
            historyText = buildHistoryText(config.maxContextChars),
            outputMode = LlmOutputMode.LongForm,
            taskMode = taskMode,
            thinkingEffort = config.thinkingEffort,
        )
        errorMessage = null
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
                val latestBeforeCursor = fetchEntireInputText(config).beforeCursor
                if (latestBeforeCursor != beforeCursor) return@request
                errorMessage = null
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
                Timber.tag(TAG).e(error, "long-form predict failed: ${error.message}")
                errorMessage = predictionErrorMessage(error)
                activeSuggestions = emptyList()
                panelVisible = true
                resetPanelContentState()
                dispatchPresentationChanged()
            },
            onPartial = { partial ->
                if (trigger == PredictionTrigger.Automatic && predictionSuppressed) return@request
                val latestBeforeCursor = fetchEntireInputText(config).beforeCursor
                if (latestBeforeCursor != beforeCursor) return@request
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = beforeCursor,
                    outputMode = LlmOutputMode.LongForm,
                    taskMode = taskMode,
                    isPartial = true,
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

    fun cancelHardwarePrediction(): Boolean {
        val wasActive = activeSuggestions.isNotEmpty() || panelVisible || errorMessage != null ||
            (predictorCreated && predictor.hasPendingRequest())
        suppressPredictionUntilNextCommit("hardware-dismiss")
        return wasActive
    }

    fun resumeHardwarePredictionAfterCommit() {
        predictionSuppressed = false
        suppressionUntilCommitRevision = -1
    }

    internal fun commitSuggestionFromUi(suggestion: String, fromExpandedArea: Boolean = false) {
        if (!canCommitAiSuggestionFromUi(suggestion, latestPresentationState)) return
        if (selectionRequest != null) {
            commitSelectedTextResult(suggestion)
            return
        }
        when {
            taskMode == LlmTaskMode.Translate || isTranslatePanelMode() ->
                commitTranslatedText(suggestion)

            taskMode == LlmTaskMode.QuestionAnswer || isQuestionAnswerPanelMode() ->
                commitQuestionAnswer(suggestion, fromExpandedArea)

            isLongFormPanelMode() ->
                commitPolishedText(suggestion)

            else ->
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
        restoreModeAfterSelectionRequest()
        startInputPredictionGeneration += 1
        if (predictorCreated) {
            predictor.close()
        }
        cancelPseudoStreaming()
        resetPanelContentState()
        activeSuggestions = emptyList()
        errorMessage = null
        panelVisible = false
        dispatchPresentationChanged()
    }

    private fun suppressPredictionUntilNextCommit(reason: String) {
        predictionSuppressed = true
        suppressionUntilCommitRevision = commitRevision
        Timber.tag(TAG).d("suppress prediction reason=$reason untilCommitRevision=$suppressionUntilCommitRevision")
        clearSuggestions(resetRequestState = true)
    }

    private fun requestPredictionIfNeeded(trigger: PredictionTrigger = PredictionTrigger.Automatic) {
        if (selectionRequest != null) {
            validateSelectionRequest()
            return
        }
        if (service.hardwarePredictionSession.isSuppressed) {
            clearSuggestions(resetRequestState = true)
            return
        }
        val isAutomatic = trigger == PredictionTrigger.Automatic
        val keepSingleTextPanelVisible = shouldKeepSingleTextPanelVisible()
        if (!allowPrediction || (!keepSingleTextPanelVisible && (hasClientPreedit || hasInputPanelPreedit)) || !selectionCollapsed) {
            Timber.tag(TAG).d("skip request allow=$allowPrediction clientPreedit=$hasClientPreedit inputPanelPreedit=$hasInputPanelPreedit selectionCollapsed=$selectionCollapsed")
            clearSuggestions(resetRequestState = true)
            return
        }

        val config = LlmPrefs.read(service.applicationContext)
        if (!config.enabled) {
            clearSuggestions(resetRequestState = true)
            return
        }
        if (isAutomatic && !config.autoPredictEnabled) {
            Timber.tag(TAG).d("skip request auto prediction disabled")
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
            Timber.tag(TAG).d("skip request blank beforeCursor")
            clearSuggestions(resetRequestState = true)
            return
        }
        if (!LlmRequestGate.shouldRequestPrediction(beforeCursor)) {
            Timber.tag(TAG).d("skip request no predictive chars beforeCursor='${beforeCursor.take(40)}'")
            clearSuggestions(resetRequestState = true)
            return
        }
        val nowMs = System.currentTimeMillis()
        if (isAutomatic &&
            LlmRequestGate.shouldThrottlePureLongDigitInput(beforeCursor, lastPureLongDigitRequestAtMs, nowMs)
        ) {
            Timber.tag(TAG).d("skip request throttled pure long digits beforeCursor='${beforeCursor.take(40)}'")
            clearSuggestions(resetRequestState = false)
            return
        }
        updateCommitContext(fetchedBeforeCursor, config)
        if (isAutomatic && predictionSuppressed) {
            if (commitRevision > suppressionUntilCommitRevision) {
                predictionSuppressed = false
                suppressionUntilCommitRevision = -1
            } else {
                Timber.tag(TAG).d("skip request suppressed by commit revision=$commitRevision target=$suppressionUntilCommitRevision")
                clearSuggestions(resetRequestState = false)
                return
            }
        }
        if (isAutomatic && beforeCursor == lastRequestedBeforeCursor) {
            Timber.tag(TAG).d("skip request unchanged beforeCursor='${beforeCursor.take(40)}'")
            return
        }
        Timber.tag(TAG).d("request beforeCursor='${beforeCursor.take(60)}'")

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
        errorMessage = null
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
                thinkingEffort = config.thinkingEffort,
            ),
            onResult = { suggestions ->
                if (isAutomatic && predictionSuppressed) {
                    Timber.tag(TAG).d("drop result while suppressed values=$suggestions")
                    return@request
                }
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) {
                    return@request
                }
                errorMessage = null
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
                Timber.tag(TAG).e(error, "predict failed: ${error.message}")
                errorMessage = predictionErrorMessage(error)
                activeSuggestions = emptyList()
                panelVisible = shouldShowQuestionAnswerPanel
                resetPanelContentState()
                dispatchPresentationChanged()
            }
        )
    }

    private fun renderSuggestions(suggestions: List<String>) {
        cancelPseudoStreaming()
        activeSuggestions = suggestions.take(currentSuggestionLimit())
        if (activeSuggestions.isEmpty()) {
            Timber.tag(TAG).d("hide ai suggestion bubble: empty suggestions")
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

    private fun commitQuestionAnswer(answer: String, fromExpandedArea: Boolean) {
        commitSuggestion(questionAnswerCommitText(answer, fromExpandedArea))
    }

    private fun commitPolishedText(polished: String) {
        val config = LlmPrefs.read(service.applicationContext)
        val snapshot = fetchEntireInputText(config)
        val inputConnection = service.currentInputConnection
        if (inputConnection == null || snapshot.beforeCursor.isEmpty()) {
            commitSuggestion(polished)
            return
        }
        val selection = service.currentInputSelection
        service.recordAiRewriteUndoSnapshot(
            beforeCursor = snapshot.beforeCursor,
            afterCursor = snapshot.afterCursor,
            selectionStart = selection.start,
            selectionEnd = selection.end,
        )
        val replacedLength = snapshot.beforeCursor.length
        noteCommittedText(polished, config)
        lastObservedBeforeCursor = polished.takeLast(config.fetchWindowChars)
        inputConnection.withBatchEdit {
            finishComposingText()
            if (selection.start != selection.end) {
                setSelection(selection.start, selection.start)
            }
            deleteSurroundingText(replacedLength, 0)
            commitText(polished, 1)
        }
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
        restoreModeAfterSelectionRequest()
        if (predictorCreated) {
            predictor.cancel()
        }
        cancelPseudoStreaming()
        resetPanelContentState()
        activeSuggestions = emptyList()
        errorMessage = null
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
            errorMessage != null && panelVisible -> PresentationMode.PanelVisible
            errorMessage != null && anchorState != null -> PresentationMode.BubbleAnchored
            errorMessage != null -> PresentationMode.BubbleFallback
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
            isTranslateEnabled = taskMode == LlmTaskMode.Translate,
            errorMessage = errorMessage,
        )
        onPresentationChanged?.invoke(
            latestPresentationState
        )
    }

    private fun predictionErrorMessage(error: Throwable): String {
        val failure = error as? LlmPredictionFailure
        val message = failure?.providerMessage?.takeIf(String::isNotBlank) ?: when (failure?.kind) {
            LlmPredictionFailure.Kind.BILLING_OR_QUOTA -> themedContext.getString(R.string.ai_clip_error_quota)
            LlmPredictionFailure.Kind.AUTHENTICATION -> themedContext.getString(R.string.ai_clip_error_authentication)
            LlmPredictionFailure.Kind.RATE_LIMIT -> themedContext.getString(R.string.ai_clip_error_rate_limit)
            LlmPredictionFailure.Kind.SERVICE_UNAVAILABLE -> themedContext.getString(R.string.ai_clip_error_service)
            LlmPredictionFailure.Kind.NETWORK -> themedContext.getString(R.string.ai_clip_error_network)
            LlmPredictionFailure.Kind.INVALID_RESPONSE -> themedContext.getString(R.string.ai_clip_error_invalid_response)
            null -> themedContext.getString(R.string.ai_clip_error_generic)
        }
        return if (failure?.willSwitchEndpointOnNextRequest == true) {
            "${themedContext.getString(R.string.ai_clip_error_next_endpoint)}\n$message"
        } else message
    }

    private fun restoreRememberedMode(config: LlmPrefs.Config) {
        briefAnswerEnabled = false
        val remembered = LlmPrefs.readRememberedUiMode(
            prefs = PreferenceManager.getDefaultSharedPreferences(service.applicationContext),
            defaultThinkingEnabled = config.isLocalOnDevice,
        )
        taskMode = remembered.taskMode
        longFormEnabled = remembered.longFormEnabled && taskMode == LlmTaskMode.Completion
    }

    private fun persistRememberedUiMode() {
        LlmPrefs.persistRememberedUiMode(
            prefs = PreferenceManager.getDefaultSharedPreferences(service.applicationContext),
            taskMode = taskMode,
            longFormEnabled = longFormEnabled,
            thinkingEnabled = false,
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
        return when {
            briefAnswerEnabled -> LlmOutputMode.BriefAnswer
            longFormEnabled -> LlmOutputMode.LongForm
            else -> LlmOutputMode.Suggestions
        }
    }

    private fun requestPanelTextContent(
        beforeCursorOverride: String? = null,
        configOverride: LlmPrefs.Config? = null,
        trigger: PredictionTrigger = PredictionTrigger.Automatic,
    ): Boolean {
        if (selectionRequest != null) return validateSelectionRequest()
        if (service.hardwarePredictionSession.isSuppressed) {
            clearSuggestions(resetRequestState = true)
            return false
        }
        val config = configOverride ?: LlmPrefs.read(service.applicationContext)
        if (taskMode == LlmTaskMode.Translate) {
            return requestTranslateContent(config, trigger)
        }
        val beforeCursor = beforeCursorOverride
            ?.takeIf { !longFormEnabled && !briefAnswerEnabled }
            ?.let { it.takeLast(config.maxContextChars) }
            ?: if (longFormEnabled || briefAnswerEnabled) {
                fetchEntireInputText(config).beforeCursor
            } else {
                fetchBeforeCursor(config).takeLast(config.maxContextChars)
            }
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
            thinkingEffort = config.thinkingEffort,
        )
        lastRequestedBeforeCursor = sourceText
        cancelPseudoStreaming()
        errorMessage = null
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
                    isPartial = true,
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
                errorMessage = null
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
                Timber.tag(TAG).e(error, "translate predict failed: ${error.message}")
                errorMessage = predictionErrorMessage(error)
                activeSuggestions = emptyList()
                panelVisible = true
                resetPanelContentState()
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
        val request = LlmPredictor.Request(
            beforeCursor = beforeCursor,
            recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
            historyText = buildHistoryText(config.maxContextChars),
            outputMode = currentPanelOutputMode(),
            taskMode = LlmTaskMode.QuestionAnswer,
            thinkingEffort = config.thinkingEffort,
        )
        lastRequestedBeforeCursor = beforeCursor
        cancelPseudoStreaming()
        errorMessage = null
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
                val latestBeforeCursor = if (request.outputMode == LlmOutputMode.BriefAnswer) {
                    fetchEntireInputText(config).beforeCursor
                } else fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                val streamed = sanitizeSingleCandidate(
                    candidate = partial,
                    beforeCursor = beforeCursor,
                    outputMode = request.outputMode,
                    taskMode = LlmTaskMode.QuestionAnswer,
                    isPartial = true,
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
                val latestBeforeCursor = if (request.outputMode == LlmOutputMode.BriefAnswer) {
                    fetchEntireInputText(config).beforeCursor
                } else fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) return@request
                errorMessage = null
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
                Timber.tag(TAG).e(error, "question-answer predict failed: ${error.message}")
                errorMessage = predictionErrorMessage(error)
                activeSuggestions = emptyList()
                panelVisible = true
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
        isPartial: Boolean = false,
    ): String {
        val compact = when {
            taskMode == LlmTaskMode.Translate -> if (isPartial) {
                normalizeTranslationPartialCandidate(beforeCursor, candidate)
            } else {
                normalizeTranslationCandidate(beforeCursor, candidate)
            }

            outputMode == LlmOutputMode.LongForm ->
                candidate.replace("\r\n", "\n").trim()

            else -> candidate.lineSequence()
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
