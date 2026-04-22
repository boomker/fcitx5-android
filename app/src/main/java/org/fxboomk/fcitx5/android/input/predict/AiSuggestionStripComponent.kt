package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.dependency.UniqueViewComponent
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.R
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

private const val TAG = "LanLlmChip"

class AiSuggestionStripComponent(
    private val service: FcitxInputMethodService,
    private val themedContext: Context,
    private val theme: Theme,
) : UniqueViewComponent<AiSuggestionStripComponent, FrameLayout>(), InputBroadcastReceiver {

    private val predictor by lazy { LanLlmPredictor(service.applicationContext) }
    private val windowManager: InputWindowManager by manager.must()
    private val recentCommittedSegments = ArrayDeque<String>()

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
    private var expandedWindowVisible = false

    private val openButton = TextView(themedContext).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(theme.accentKeyTextColor)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = themedContext.dp(18).toFloat()
            setColor(theme.accentKeyBackgroundColor)
            setStroke(themedContext.dp(1), theme.dividerColor)
        }
        setOnClickListener {
            openSuggestionTable()
        }
    }

    override val view = FrameLayout(themedContext).apply {
        id = View.generateViewId()
        visibility = View.GONE
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(
            openButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
            }
        )
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
        suppressionUntilCommitRevision = -1
        recentCommittedSegments.clear()
        clearSuggestions(resetRequestState = true)
        requestPredictionIfNeeded()
    }

    override fun onClientPreeditUpdate(data: FormattedText) {
        hasClientPreedit = data.isNotEmpty()
        requestPredictionIfNeeded()
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        hasInputPanelPreedit = data.preedit.isNotEmpty()
        requestPredictionIfNeeded()
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        requestPredictionIfNeeded()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        selectionCollapsed = start == end
        requestPredictionIfNeeded()
    }

    fun hasVisibleSuggestions(): Boolean = activeSuggestions.isNotEmpty() && (view.visibility == View.VISIBLE || expandedWindowVisible)

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
        if (activeSuggestions.isEmpty()) return false
        windowManager.attachWindow(AiSuggestionWindow)
        return true
    }

    fun suppressAfterBackspace() {
        suppressPredictionUntilNextCommit("backspace")
    }

    internal fun currentSuggestionsSnapshot(): List<String> = activeSuggestions.toList()

    internal fun commitSuggestionFromWindow(suggestion: String) {
        commitSuggestion(suggestion)
    }

    fun close() {
        predictor.close()
    }

    override fun onWindowAttached(window: InputWindow) {
        expandedWindowVisible = window === AiSuggestionWindow
        updateButtonVisibility()
    }

    override fun onWindowDetached(window: InputWindow) {
        if (window === AiSuggestionWindow) {
            expandedWindowVisible = false
            updateButtonVisibility()
        }
    }

    private fun suppressPredictionUntilNextCommit(reason: String) {
        predictionSuppressed = true
        suppressionUntilCommitRevision = commitRevision
        Log.d(TAG, "suppress prediction reason=$reason untilCommitRevision=$suppressionUntilCommitRevision")
        clearSuggestions(resetRequestState = true)
    }

    private fun requestPredictionIfNeeded() {
        if (!allowPrediction || hasClientPreedit || hasInputPanelPreedit || !selectionCollapsed) {
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
                Log.d(TAG, "resume prediction after commit revision advanced revision=$commitRevision")
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

        lastRequestedBeforeCursor = beforeCursor
        if (LanLlmRequestGate.isPureLongDigitInput(beforeCursor)) {
            lastPureLongDigitRequestAtMs = nowMs
        }
        predictor.request(
            request = LanLlmPredictor.Request(
                beforeCursor = beforeCursor,
                recentCommittedText = if (config.preferLastCommit) lastCommittedText.takeLast(config.maxContextChars) else "",
                historyText = buildHistoryText(config.maxContextChars),
            ),
            onResult = { suggestions ->
                if (predictionSuppressed) {
                    Log.d(TAG, "drop result while suppressed values=$suggestions")
                    return@request
                }
                val latestBeforeCursor = fetchBeforeCursor(config).takeLast(config.maxContextChars)
                if (latestBeforeCursor != beforeCursor) {
                    Log.d(TAG, "drop stale result old='${beforeCursor.take(40)}' latest='${latestBeforeCursor.take(40)}'")
                    return@request
                }
                Log.d(TAG, "render suggestions count=${suggestions.size} values=$suggestions")
                renderSuggestions(suggestions)
            },
            onError = { error ->
                Log.e(TAG, "predict failed: ${error.message}", error)
            }
        )
    }

    private fun renderSuggestions(suggestions: List<String>) {
        val maxPredictionCandidates = LanLlmPrefs.read(service.applicationContext).maxPredictionCandidates
        activeSuggestions = suggestions.take(maxPredictionCandidates)
        if (activeSuggestions.isEmpty()) {
            Log.d(TAG, "hide ai suggestion button: empty suggestions")
            updateButtonVisibility()
            return
        }
        openButton.text = themedContext.getString(R.string.ai_clip_open_count, activeSuggestions.size)
        openButton.contentDescription = openButton.text
        updateButtonVisibility()
    }

    private fun commitSuggestion(suggestion: String) {
        val config = LanLlmPrefs.read(service.applicationContext)
        noteCommittedText(suggestion, config)
        service.commitText(suggestion)
        clearSuggestions(resetRequestState = true)
        returnToKeyboardIfNeeded()
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
        activeSuggestions = emptyList()
        if (expandedWindowVisible) {
            expandedWindowVisible = false
            returnToKeyboardIfNeeded()
        }
        updateButtonVisibility()
        if (resetRequestState) {
            lastRequestedBeforeCursor = ""
        }
    }

    private fun updateButtonVisibility() {
        view.visibility = if (activeSuggestions.isNotEmpty() && !expandedWindowVisible) View.VISIBLE else View.GONE
    }

    private fun returnToKeyboardIfNeeded() {
        val keyboardWindow = windowManager.getEssentialWindow(KeyboardWindow)
        if (windowManager.currentWindowOrNull() !== keyboardWindow) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }
}
