package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.dependency.UniqueViewComponent
import splitties.dimensions.dp

private const val TAG = "LanLlmChip"

class AiSuggestionStripComponent(
    private val service: FcitxInputMethodService,
    private val themedContext: Context,
    private val theme: Theme,
) : UniqueViewComponent<AiSuggestionStripComponent, HorizontalScrollView>(), InputBroadcastReceiver {

    private val predictor by lazy { LanLlmPredictor(service.applicationContext) }
    private val recentCommittedSegments = ArrayDeque<String>()

    private var allowPrediction = false
    private var hasClientPreedit = false
    private var hasInputPanelPreedit = false
    private var selectionCollapsed = true
    private var lastRequestedBeforeCursor = ""
    private var lastObservedBeforeCursor = ""
    private var lastCommittedText = ""
    private var commitRevision = 0
    private var predictionSuppressed = false
    private var suppressionUntilCommitRevision = -1
    private var activeSuggestions: List<String> = emptyList()

    private val chipContainer = LinearLayout(themedContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(2), dp(8), dp(4))
    }

    override val view = HorizontalScrollView(themedContext).apply {
        id = View.generateViewId()
        visibility = View.GONE
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        addView(
            chipContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        allowPrediction = !capFlags.has(CapabilityFlag.PasswordOrSensitive)
        hasClientPreedit = false
        hasInputPanelPreedit = false
        selectionCollapsed = true
        lastObservedBeforeCursor = ""
        lastCommittedText = ""
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

    fun hasVisibleSuggestions(): Boolean = activeSuggestions.isNotEmpty() && view.visibility == View.VISIBLE

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

    fun suppressAfterBackspace() {
        suppressPredictionUntilNextCommit("backspace")
    }

    fun close() {
        predictor.close()
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
        chipContainer.removeAllViews()
        activeSuggestions = suggestions.take(4)
        if (activeSuggestions.isEmpty()) {
            Log.d(TAG, "hide chip strip: empty suggestions")
            view.visibility = View.GONE
            return
        }

        activeSuggestions.forEachIndexed { index, suggestion ->
            chipContainer.addView(createChip(suggestion, isPrimary = index == 0))
        }
        view.visibility = View.VISIBLE
    }

    private fun createChip(suggestion: String, isPrimary: Boolean): TextView {
        return TextView(themedContext).apply {
            text = suggestion
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(if (isPrimary) theme.accentKeyTextColor else theme.candidateTextColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = themedContext.dp(18).toFloat()
                setColor(if (isPrimary) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
                setStroke(themedContext.dp(1), theme.dividerColor)
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = themedContext.dp(8)
            }
            layoutParams = params
            setOnClickListener {
                commitSuggestion(suggestion)
            }
        }
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
        activeSuggestions = emptyList()
        chipContainer.removeAllViews()
        view.visibility = View.GONE
        if (resetRequestState) {
            lastRequestedBeforeCursor = ""
        }
    }
}
