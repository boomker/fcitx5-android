/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

@file:Suppress("NotifyDataSetChanged")
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.annotation.Keep
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.hasVisibleCandidateContent
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fxboomk.fcitx5.android.input.candidates.CandidateItemUi
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.candidates.isCandidateVisibleToUser
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fxboomk.fcitx5.android.input.dependency.UniqueViewComponent
import org.fxboomk.fcitx5.android.input.dependency.context
import org.fxboomk.fcitx5.android.input.dependency.fcitx
import org.fxboomk.fcitx5.android.input.dependency.inputView
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max

internal const val HORIZONTAL_CANDIDATE_OUTER_PADDING_DP = 4
internal const val HORIZONTAL_CANDIDATE_HIGHLIGHT_PADDING_DP = 8
internal const val HORIZONTAL_CANDIDATE_VERTICAL_PADDING_DP = 4

internal fun activeCandidateIndex(cursorIndex: Int, candidateCount: Int): Int {
    if (candidateCount <= 0) return -1
    return cursorIndex.coerceIn(0, candidateCount - 1)
}

/** Space uses the highlight; an explicit selection key keeps its numbered position. */
internal fun predictionCandidateIndex(digit: Int?, activeIndex: Int, candidateCount: Int): Int? =
    (digit?.minus(1) ?: activeIndex).takeIf { it in 0 until candidateCount }

internal fun RecyclerView.isPredictionCandidateVisible(
    position: Int,
    index: Int,
    candidate: CandidateWord,
): Boolean {
    val holder = findViewHolderForAdapterPosition(position) as? CandidateViewHolder ?: return false
    // Highlight-only updates may be pending when the next key arrives. Verify the
    // rendered candidate itself so those updates cannot send space back to the engine.
    return holder.bindingAdapterPosition == position && holder.idx == index &&
        holder.candidate == candidate && holder.itemView.isCandidateVisibleToUser()
}

/**
 * Selection-key number shown at the top-right corner of a candidate item when a physical
 * keyboard drives the bar. fcitx selects candidates with digit keys 1-9, so items beyond
 * the ninth position get no label.
 */
internal fun indexLabelForPosition(position: Int, enabled: Boolean): Int? =
    if (enabled && position in 0 until MAX_INDEX_LABEL) position + 1 else null

private const val MAX_INDEX_LABEL = 9

internal data class HorizontalCandidateLayoutSizing(
    val minWidth: Int,
    val flexGrow: Float,
    val secondLayoutPassNeeded: Boolean,
)

internal fun resolveHorizontalCandidateLayoutSizing(
    fillStyle: HorizontalCandidateMode,
    candidateCount: Int,
    availableWidth: Int,
    maxSpanCount: Int,
    dividerWidth: Int,
): HorizontalCandidateLayoutSizing {
    val safeMaxSpanCount = maxSpanCount.coerceAtLeast(1)
    return when (fillStyle) {
        NeverFillWidth -> HorizontalCandidateLayoutSizing(0, 0f, false)
        AutoFillWidth -> HorizontalCandidateLayoutSizing(
            minWidth = (availableWidth / safeMaxSpanCount - dividerWidth).coerceAtLeast(0),
            flexGrow = if (candidateCount < safeMaxSpanCount) 0f else 1f,
            secondLayoutPassNeeded = candidateCount < safeMaxSpanCount,
        )
        AlwaysFillWidth -> HorizontalCandidateLayoutSizing(
            minWidth = 0,
            flexGrow = if (candidateCount <= 1) 0f else 1f,
            secondLayoutPassNeeded = false,
        )
    }
}

internal fun moveActiveCandidateIndex(
    currentIndex: Int,
    delta: Int,
    candidateCount: Int,
): Int {
    if (candidateCount <= 0) return -1
    val base = activeCandidateIndex(currentIndex, candidateCount)
    return (base + delta).coerceIn(0, candidateCount - 1)
}

internal fun nextCandidateRowStart(
    currentStart: Int,
    prefetchedCandidateCount: Int,
    visibleRowCount: Int,
): Int = currentStart + visibleRowCount.coerceIn(1, prefetchedCandidateCount.coerceAtLeast(1))

internal data class AiCandidateRowPlacement(
    val candidates: Array<String>,
    val visibleAiCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AiCandidateRowPlacement
        return candidates.contentEquals(other.candidates) &&
            visibleAiCount == other.visibleAiCount
    }

    override fun hashCode(): Int {
        var result = candidates.contentHashCode()
        result = 31 * result + visibleAiCount
        return result
    }
}

internal fun placeAiCandidatesInRow(
    nativeCandidates: Array<String>,
    aiSuggestions: List<String>,
    availableWidth: Int,
    dividerWidth: Int,
    maxCandidateCount: Int,
    measureAiCandidateWidth: ((String) -> Int)? = null,
    measureCandidateWidth: (String) -> Int,
): AiCandidateRowPlacement {
    if (aiSuggestions.isEmpty()) {
        return AiCandidateRowPlacement(nativeCandidates, visibleAiCount = 0)
    }
    val safeAvailableWidth = availableWidth.coerceAtLeast(1)
    val safeDividerWidth = dividerWidth.coerceAtLeast(0)
    val safeMaxCandidateCount = maxCandidateCount.coerceAtLeast(0)
    var usedWidth = nativeCandidates.sumOf {
        measureCandidateWidth(it) + safeDividerWidth
    }
    var candidateCount = nativeCandidates.size
    var visibleAiCount = 0
    for (suggestion in aiSuggestions) {
        if (candidateCount >= safeMaxCandidateCount) break
        val occupiedWidth = (measureAiCandidateWidth ?: measureCandidateWidth)(suggestion) + safeDividerWidth
        if (usedWidth + occupiedWidth > safeAvailableWidth) break
        usedWidth += occupiedWidth
        candidateCount++
        visibleAiCount++
    }
    return AiCandidateRowPlacement(
        candidates = nativeCandidates + aiSuggestions.take(visibleAiCount).toTypedArray(),
        visibleAiCount = visibleAiCount,
    )
}

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val inputView by manager.inputView()
    private val theme by manager.theme()
    private val bar: KawaiiBarComponent by manager.must()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val firstPageCandidates = FirstPageCandidateTracker()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val toolbarDynamicHeightPref = AppPrefs.getInstance().keyboard.toolbarDynamicHeight

    // Grow the bar to fit candidates when the dynamic toolbar height is on
    @Keep
    private val onDynamicHeightChangeListener =
        ManagedPreference.OnChangeListener<Boolean> { _, _ ->
            syncDynamicBarHeight(adapter.candidates)
        }

    init {
        toolbarDynamicHeightPref.registerOnChangeListener(onDynamicHeightChangeListener)
    }

    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f
    private var inlineMode = false
    private var indexLabelsEnabled = false

    fun setInlineMode(inline: Boolean) {
        if (inlineMode == inline) return
        inlineMode = inline
        adapter.notifyDataSetChanged()
    }

    /**
     * Show selection-key numbers on candidate items (including AI prediction items) while
     * the bar doubles as the physical-keyboard candidate display; hidden on the touch keyboard.
     */
    fun setIndexLabelsEnabled(enabled: Boolean) {
        if (indexLabelsEnabled == enabled) return
        indexLabelsEnabled = enabled
        adapter.notifyDataSetChanged()
    }

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false
    private var lastPagedData: PagedCandidateEvent.Data? = null
    private var pagedCandidateFlowActive = false
    private var pendingLegacyCandidateUpdate: Runnable? = null
    private var showingAiSuggestions = false
    private var displayedAiStartIndex = -1
    private var displayedNativeCount = 0
    private var hasExpandableCandidates = false
    private var hasExpandedNativeCandidates = false
    private var aiDisplayMode = LlmPrefs.PredictionDisplayMode.FloatingWindow
    private var aiSuggestions: List<String> = emptyList()
    private var calculatorSuggestion: String? = null
    private var expandedAiSuggestions: List<String> = emptyList()
    private val rowWindowHistory = ArrayDeque<RowWindow>()

    private data class NativeCandidateSnapshot(
        val candidates: Array<CandidateWord> = emptyArray(),
        val total: Int = 0,
        val indexOffset: Int = 0,
        val activeIndex: Int = -1,
        val excludedIndices: Set<Int> = emptySet(),
    )

    private var nativeCandidateSnapshot = NativeCandidateSnapshot()
    private data class ViewportGeometry(
        val width: Int,
        val height: Int,
        val availableWidth: Int,
        val spanCount: Int,
        val style: HorizontalCandidateMode,
        val inline: Boolean,
        val textSize: Float,
        val typeface: Any?,
    )
    private var viewportProbeJob: Job? = null
    private var viewportProbeVersion = 0L
    private var viewportInputVersion = 0L
    private var viewportPanel: FcitxEvent.InputPanelEvent.Data? = null
    private var viewportProbeSource: NativeCandidateSnapshot? = null
    private var viewportProbeGeometry: ViewportGeometry? = null
    private var completedViewportSource: NativeCandidateSnapshot? = null
    private var completedViewportGeometry: ViewportGeometry? = null
    private var completedViewportInputVersion = -1L
    private var viewportApplying = false
    private var viewportPolicyActive = false
    /** UI-owned highlight. Engine events may omit the cursor and must not reset it. */
    private var activeCandidateOverride: Int? = null
    /** Absolute engine cursor, kept locally while the UI can highlight AI items. */
    private var engineNativeCursorIndex = -1
    private var displayedCalculatorIndex = -1
    var excludedNativeCandidateIndices: Set<Int> = emptySet()
        private set
    private var nextNativeCandidateStart = 0

    private data class RowWindow(
        val start: Int,
        val candidates: Array<CandidateWord>,
        val excludedIndices: Set<Int>,
    )

    private data class ForwardRowShiftSnapshot(
        val currentStart: Int,
        val currentCandidates: Array<CandidateWord>,
        val currentExcludedIndices: Set<Int>,
        val nextStart: Int,
        val nextLimit: Int,
        val nextExcludedIndices: Set<Int>,
    )

    private data class CandidateWordRowPlacement(
        val candidates: Array<CandidateWord>,
        val visibleAiCount: Int,
    )

    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val measurementCandidateUi by lazy {
        CandidateItemUi(context, theme).also { ui ->
            ui.root.minimumWidth = context.dp(40)
            ui.configureHorizontalHighlightSpacing(
                outerPadding = context.dp(HORIZONTAL_CANDIDATE_OUTER_PADDING_DP),
                highlightPadding = context.dp(HORIZONTAL_CANDIDATE_HIGHLIGHT_PADDING_DP),
                verticalPadding = context.dp(HORIZONTAL_CANDIDATE_VERTICAL_PADDING_DP),
            )
            ui.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded() {
        _expandedCandidateOffset.tryEmit(nextNativeCandidateStart)
        bar.syncExpandedCandidateState(hasExpandableCandidates = hasExpandableCandidates)
    }

    private fun resetRowWindowState() {
        rowWindowHistory.clear()
        excludedNativeCandidateIndices = emptySet()
        nextNativeCandidateStart = 0
    }

    private fun nativeIndexAt(position: Int): Int =
        adapter.candidates[position].nativeIndex.takeIf { it >= 0 }
            ?: (position + adapter.indexOffset)

    internal fun mappedNativeCandidateAt(position: Int): CandidateWord? =
        adapter.candidates.getOrNull(position)?.takeIf {
            position < displayedNativeCount && it.nativeIndex >= 0
        }

    internal fun wasCandidateOnFirstPage(nativeIndex: Int): Boolean? {
        recordFirstPageCandidates()
        return firstPageCandidates.contains(nativeIndex, lastPagedData?.hasPrev == true)
    }

    private fun recordFirstPageCandidates() {
        if (preeditState.isEmpty || nativeCandidateSnapshot.indexOffset != 0 ||
            rowWindowHistory.isNotEmpty() || lastPagedData?.hasPrev == true ||
            nativeCandidateSnapshot.candidates.isEmpty() || !view.isCandidateVisibleToUser()) return
        if (view.hasPendingAdapterUpdates() || (displayedNativeCount > 0 && view.childCount == 0)) return
        val visible = HashSet<Int>()
        val bounds = Rect()
        var boundNativeChildren = 0
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            val holder = view.getChildViewHolder(child) as? CandidateViewHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position !in 0 until displayedNativeCount || !child.isCandidateVisibleToUser()) continue
            if (holder.candidate != adapter.candidates.getOrNull(position) ||
                holder.idx != nativeIndexAt(position)) return
            boundNativeChildren++
            if (child.getLocalVisibleRect(bounds) && bounds.left <= 0 && bounds.top <= 0 &&
                bounds.right >= child.width && bounds.bottom >= child.height) {
                visible.add(holder.idx)
            }
        }
        if (displayedNativeCount > 0 && boundNativeChildren == 0) return
        firstPageCandidates.record(visible, isFirstPage = true)
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) {
            firstPageCandidates.clear()
            viewportInputVersion++
            completedViewportSource = null
            viewportProbeJob?.cancel()
        } else {
            view.post {
                recordFirstPageCandidates()
                requestCandidateViewportProbe()
            }
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        val previous = viewportPanel
        viewportPanel = data
        if (previous == null || !previous.preedit.spanEquals(data.preedit) ||
            previous.preedit.cursor != data.preedit.cursor || !previous.tabs.contentEquals(data.tabs)) {
            viewportInputVersion++
            completedViewportSource = null
            requestCandidateViewportProbe()
        }
    }

    fun hasRowSwipeCandidates(): Boolean = pagedCandidateFlowActive &&
        (adapter.candidates.isNotEmpty() || hasExpandedNativeCandidates)

    fun hasCandidates(): Boolean = adapter.candidates.isNotEmpty()

    private fun effectiveActiveCandidateIndex(fallback: Int = adapter.activeIndex): Int =
        activeCandidateOverride?.takeIf { it in adapter.candidates.indices } ?: fallback

    internal fun hasVisiblePredictionCandidate(digit: Int? = null): Boolean {
        val position = predictionCandidateIndex(
            digit,
            effectiveActiveCandidateIndex(),
            adapter.candidates.size,
        ) ?: return false
        return view.isPredictionCandidateVisible(
            position, nativeIndexAt(position), adapter.candidates[position]
        )
    }

    internal fun hasVisibleAiSuggestions(): Boolean = adapter.candidates.indices.any {
        isAiCandidatePosition(it) && hasVisiblePredictionCandidate(it + 1)
    }

    internal fun selectVisiblePredictionCandidate(digit: Int? = null): Boolean {
        if (!hasVisiblePredictionCandidate(digit)) return false
        val position = predictionCandidateIndex(
            digit,
            effectiveActiveCandidateIndex(),
            adapter.candidates.size,
        ) ?: return false
        if (selectByDigitKey(position + 1)) return true
        val index = nativeIndexAt(position)
        val expected = lastPagedData ?: return false
        service.postFcitxJob {
            if (!service.hardwarePredictionSession.isSuppressed) selectPrediction(index, expected)
        }
        return true
    }

    fun hasNativeCandidates(): Boolean = nativeCandidateSnapshot.candidates.isNotEmpty()

    fun isShowingAiSuggestions(): Boolean = showingAiSuggestions

    fun currentExpandedAiSuggestions(): List<String> = expandedAiSuggestions

    fun hasExpandedNativeCandidates(): Boolean = hasExpandedNativeCandidates

    fun hasExpandableDeferredCandidates(): Boolean = hasExpandedNativeCandidates &&
        nativeCandidateSnapshot.candidates.any { it.isDeferredFallback || it.isAbbreviation }

    fun clearPredictionCandidates() {
        firstPageCandidates.clear()
        clearNativeCandidateFlow()
        resetRowWindowState()
        updateNativeCandidateSnapshot(emptyArray(), 0, 0, -1)
        activeCandidateOverride = null
        engineNativeCursorIndex = -1
        aiSuggestions = emptyList()
        calculatorSuggestion = null
        renderCurrentCandidates()
    }

    fun moveActiveCandidate(delta: Int, syncEngine: Boolean = false): Boolean {
        if (delta == 0 || adapter.candidates.isEmpty()) return false
        val previousIndex = activeCandidateIndex(
            effectiveActiveCandidateIndex(),
            adapter.candidates.size,
        )
        val nextIndex = moveActiveCandidateIndex(previousIndex, delta, adapter.candidates.size)
        if (nextIndex == previousIndex) return false
        activeCandidateOverride = nextIndex
        adapter.updateActiveIndex(nextIndex)
        // AI/calculator items are not engine candidates. When the highlight lands
        // on a native item, synchronize the engine cursor from its last known
        // absolute position so native → AI → native transitions remain correct.
        if (syncEngine && pagedCandidateFlowActive && nextIndex < displayedNativeCount) {
            val targetIndex = nativeIndexAt(nextIndex)
            val currentIndex = engineNativeCursorIndex.takeIf { it >= 0 }
                ?: (nativeCandidateSnapshot.indexOffset + nativeCandidateSnapshot.activeIndex)
            val indexDelta = targetIndex - currentIndex
            if (indexDelta != 0) {
                if (viewportPolicyActive) {
                    service.postFcitxJob { setCandidateCursor(targetIndex) }
                } else {
                    service.postCandidateCursorNavigation(indexDelta)
                }
                engineNativeCursorIndex = targetIndex
            }
        }
        return true
    }

    fun selectActiveCandidate(): Boolean {
        if (viewportApplying) return false
        val idx = effectiveActiveCandidateIndex()
        if (idx !in adapter.candidates.indices) return false
        if (isCalculatorCandidatePosition(idx)) {
            calculatorSuggestion?.let(inputView::commitCalculatorSuggestionFromUi)
            return true
        }
        if (isAiCandidatePosition(idx)) {
            if (!hasVisiblePredictionCandidate(idx + 1)) return false
            inputView.commitAiSuggestionFromUi(adapter.candidates[idx].text)
            return true
        }
        val nativeIndex = nativeIndexAt(idx)
        service.postFcitxJob { select(nativeIndex) }
        return true
    }

    internal fun highlightedNativeCandidateIndex(): Int? =
        effectiveActiveCandidateIndex().takeIf { it in 0 until displayedNativeCount }?.let(::nativeIndexAt)

    internal fun highlightedCandidateText(): String? =
        effectiveActiveCandidateIndex().takeIf { it in 0 until displayedNativeCount }
            ?.let { adapter.candidates.getOrNull(it)?.text }

    /**
     * Commit the displayed item carrying the index label [digit] (1-9) when it is the
     * calculator result, an AI prediction, or a native item moved for recovery. Other
     * native positions keep flowing to fcitx for engine-side selection.
     */
    fun selectByDigitKey(digit: Int): Boolean {
        if (viewportApplying) return false
        val position = digit - 1
        if (position !in adapter.candidates.indices) return false
        if (isCalculatorCandidatePosition(position)) {
            calculatorSuggestion?.let(inputView::commitCalculatorSuggestionFromUi)
            return true
        }
        if (isAiCandidatePosition(position)) {
            if (!hasVisiblePredictionCandidate(digit)) return false
            inputView.commitAiSuggestionFromUi(adapter.candidates[position].text)
            return true
        }
        val nativeIndex = nativeIndexAt(position)
        if (nativeIndex != position + adapter.indexOffset) {
            service.postFcitxJob { select(nativeIndex) }
            return true
        }
        return false
    }

    private fun isCalculatorCandidatePosition(position: Int): Boolean =
        position == displayedCalculatorIndex

    private fun isAiCandidatePosition(position: Int): Boolean =
        displayedAiStartIndex >= 0 && position >= displayedAiStartIndex

    private fun candidateFetchBatchSize(): Int = max(maxSpanCountPref.getValue() * 3, 24)

    /**
     * Height (dp) needed for a candidate, including its optional pinyin line.
     */
    private fun measuredCandidateRowHeightDp(candidate: CandidateWord): Int {
        measurementCandidateUi.apply {
            setFontScale(if (inlineMode) INLINE_CANDIDATE_FONT_SCALE else 1f)
            updateCandidate(candidate)
            applyConfiguredTypeface()
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val density = context.resources.displayMetrics.density
        return ceil(measurementCandidateUi.root.measuredHeight / density).toInt()
    }

    /**
     * Report the height the candidate row requires so the toolbar can grow when the
     * dynamic height setting is on or a candidate has top pinyin; clears the
     * requirement when empty and the forced height when top pinyin disappears.
     */
    private fun syncDynamicBarHeight(candidates: Array<CandidateWord>) {
        val topPinyin = candidates.firstOrNull { it.topPinyinAnnotation != null }
        val required = if (candidates.isNotEmpty()) {
            val sample = topPinyin ?: CandidateWord("", "测", "", false)
            measuredCandidateRowHeightDp(sample)
        } else 0
        bar.setCandidateRequiredHeight(required, force = topPinyin != null)
    }

    private fun measuredCandidateTextWidth(candidate: String, layoutMinWidth: Int): Int {
        measurementCandidateUi.apply {
            root.minimumWidth = max(context.dp(40), layoutMinWidth)
            setFontScale(if (inlineMode) INLINE_CANDIDATE_FONT_SCALE else 1f)
            updateCandidate(CandidateWord("", candidate, "", false))
            applyConfiguredTypeface()
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        return measurementCandidateUi.root.measuredWidth
    }

    private fun measuredCandidateWidth(candidate: CandidateWord, layoutMinWidth: Int): Int {
        if (candidate.topPinyinAnnotation == null) {
            return measuredCandidateTextWidth(candidate.textWithComment(), layoutMinWidth)
        }
        measurementCandidateUi.apply {
            root.minimumWidth = max(context.dp(40), layoutMinWidth)
            setFontScale(if (inlineMode) INLINE_CANDIDATE_FONT_SCALE else 1f)
            updateCandidate(candidate)
            applyConfiguredTypeface()
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(view.height, if (view.height > 0) MeasureSpec.EXACTLY else MeasureSpec.UNSPECIFIED),
            )
        }
        return measurementCandidateUi.root.measuredWidth
    }

    private fun singleRowCount(candidates: Array<CandidateWord>): Int {
        if (candidates.isEmpty()) return 0
        val availableWidth = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1)
        val dividerWidth = dividerDrawable.intrinsicWidth
        val maxSpanCount = maxSpanCountPref.getValue()
        val layoutMinWidth = when (fillStyle) {
            NeverFillWidth -> 0
            AutoFillWidth -> (view.width / maxSpanCount - dividerWidth).coerceAtLeast(0)
            AlwaysFillWidth -> 0
        }
        var usedWidth = 0
        var count = 0
        for (candidate in candidates) {
            val itemWidth = measuredCandidateWidth(candidate, layoutMinWidth)
            val occupiedWidth = itemWidth + dividerWidth
            if (count > 0 && usedWidth + occupiedWidth > availableWidth) {
                break
            }
            usedWidth += occupiedWidth
            count++
            if (
                (fillStyle == AutoFillWidth || fillStyle == AlwaysFillWidth) &&
                count >= maxSpanCount
            ) {
                break
            }
        }
        return max(count, 1)
    }

    private fun normalizedSingleRowCandidates(candidates: Array<CandidateWord>): Array<CandidateWord> {
        val count = singleRowCount(candidates).coerceAtMost(candidates.size)
        return if (count == candidates.size) candidates else candidates.copyOfRange(0, count)
    }

    private fun nativeRowCandidates(): Array<CandidateWord> {
        val snapshot = nativeCandidateSnapshot
        val initialRow = snapshot.indexOffset == 0 && rowWindowHistory.isEmpty() && lastPagedData?.hasPrev != true
        return normalizedNativeRow(snapshot.candidates, snapshot.indexOffset, snapshot.excludedIndices, initialRow)
    }

    private fun normalizedNativeRow(
        source: Array<CandidateWord>,
        indexOffset: Int,
        excludedIndices: Set<Int>,
        initialRow: Boolean,
    ): Array<CandidateWord> {
        val candidates = nativeCandidatesAfterConsumedFallbacks(
            source, indexOffset, excludedIndices, initialRow,
        )
        if (initialRow && candidates.any { it.isFallback || it.isBestAbbreviation }) {
            val dividerWidth = dividerDrawable.intrinsicWidth
            val maxSpanCount = maxSpanCountPref.getValue().coerceAtLeast(1)
            val minWidth = if (fillStyle == AutoFillWidth) {
                (view.width / maxSpanCount - dividerWidth).coerceAtLeast(0)
            } else 0
            val row = placeAbbreviationCandidateInRow(
                candidates = candidates.mapIndexed { index, candidate ->
                    candidate.copy(nativeIndex = candidate.nativeIndex.takeIf { it >= 0 } ?: index)
                }.toTypedArray(),
                availableWidth = view.width - view.paddingLeft - view.paddingRight,
                dividerWidth = dividerWidth,
                maxCandidateCount = if (fillStyle == NeverFillWidth) Int.MAX_VALUE else maxSpanCount,
            ) { measuredCandidateWidth(it, minWidth) }
            if (row.isNotEmpty()) return row
        }
        return normalizedSingleRowCandidates(candidates)
    }

    private fun ownsInitialViewport(): Boolean = inputView.handleEvents &&
        AppPrefs.getInstance().candidates.mode.getValue() != FloatingCandidatesMode.Always &&
        view.isShown && view.width > 0 && !preeditState.isEmpty &&
        nativeCandidateSnapshot.indexOffset == 0 && rowWindowHistory.isEmpty() &&
        lastPagedData?.hasPrev != true

    private fun viewportGeometry(): ViewportGeometry {
        measurementCandidateUi.setFontScale(if (inlineMode) INLINE_CANDIDATE_FONT_SCALE else 1f)
        measurementCandidateUi.applyConfiguredTypeface()
        return ViewportGeometry(
            view.width, view.height, (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1),
            maxSpanCountPref.getValue().coerceAtLeast(1), fillStyle, inlineMode,
            measurementCandidateUi.text.textSize, measurementCandidateUi.text.typeface,
        )
    }

    private fun invalidateViewportBindings() {
        firstPageCandidates.clear()
        lastRenderedCandidates = null
        for (index in 0 until view.childCount) {
            val holder = view.getChildViewHolder(view.getChildAt(index)) as? CandidateViewHolder ?: continue
            inputView.unbindCandidateGesture(holder.ui.root)
        }
    }

    private fun requestCandidateViewportProbe(force: Boolean = false) {
        if (force) completedViewportSource = null
        if (viewportApplying) return
        if (!ownsInitialViewport()) {
            viewportProbeJob?.cancel()
            return
        }
        val source = nativeCandidateSnapshot
        val geometry = viewportGeometry()
        val inputVersion = viewportInputVersion
        if (completedViewportSource === source && completedViewportGeometry == geometry &&
            completedViewportInputVersion == inputVersion) return
        if (viewportProbeJob?.isActive == true && viewportProbeSource === source &&
            viewportProbeGeometry == geometry) return
        viewportProbeJob?.cancel()
        val version = ++viewportProbeVersion
        viewportProbeSource = source
        viewportProbeGeometry = geometry
        viewportProbeJob = service.lifecycleScope.launch {
            fun rememberCompletedProbe() {
                if (nativeCandidateSnapshot === source && viewportInputVersion == inputVersion) {
                    completedViewportSource = source
                    completedViewportGeometry = geometry
                    completedViewportInputVersion = inputVersion
                }
            }
            try {
                var limit = candidateFetchBatchSize()
                var snapshot = fcitx.runOnReady { getCandidateViewportSnapshot(limit) }
                viewportPolicyActive = snapshot.foldingEnabled
                if (!snapshot.foldingEnabled) {
                    rememberCompletedProbe()
                    return@launch
                }
                while (isActive && version == viewportProbeVersion) {
                    if (!ownsInitialViewport() || nativeCandidateSnapshot !== source ||
                        viewportInputVersion != inputVersion || viewportGeometry() != geometry) return@launch
                    val minWidth = if (fillStyle == AutoFillWidth) {
                        (view.width / geometry.spanCount - dividerDrawable.intrinsicWidth).coerceAtLeast(0)
                    } else 0
                    val probe = probeCandidateViewport(snapshot, geometry.availableWidth,
                        normalizeRow = { normalizedNativeRow(it, 0, emptySet(), true) },
                        measureCandidateWidth = { measuredCandidateWidth(it, minWidth) },
                    )
                    if (probe.needsMoreCandidates && snapshot.total > snapshot.candidates.size) {
                        val nextLimit = minOf(snapshot.total.toLong(), limit.toLong() * 2).toInt()
                        if (nextLimit > limit) {
                            val expanded = fcitx.runOnReady { getCandidateViewportSnapshot(nextLimit) }
                            if (expanded.revision != snapshot.revision) return@launch
                            limit = nextLimit
                            snapshot = expanded
                            continue
                        }
                    }
                    if (probe.candidates.isEmpty()) {
                        rememberCompletedProbe()
                        return@launch
                    }
                    viewportApplying = true
                    invalidateViewportBindings()
                    val accepted = fcitx.runOnReady {
                        setCandidateViewportIfCurrent(
                            snapshot.revision, probe.candidates.size, probe.firstPageIndices, probe.firstSymbolIndex,
                        )
                    }
                    // Consume the native candidate update before old gesture bindings can resume.
                    yield()
                    if (accepted) {
                        rememberCompletedProbe()
                    } else {
                        val current = fcitx.runOnReady { getCandidateViewportSnapshot(1) }
                        // A same-revision rejection cannot be fixed by repeatedly applying this layout.
                        if (current.revision == snapshot.revision) rememberCompletedProbe()
                    }
                    return@launch
                }
            } finally {
                if (version == viewportProbeVersion) {
                    val restoreBindings = viewportApplying
                    viewportApplying = false
                    viewportProbeJob = null
                    if (ownsInitialViewport()) renderCurrentCandidates()
                    // An unchanged native row skips adapter rebinding, but its gestures were suspended.
                    if (restoreBindings) adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
        }
    }

    private fun aiSuggestionCandidate(text: String) = CandidateWord("", text, "", false)

    private fun placeAiSuggestionsAfterNative(
        nativeCandidates: Array<CandidateWord>,
        suggestions: List<String> = aiSuggestions,
    ): CandidateWordRowPlacement {
        if (nativeCandidates.any { it.isFallback || it.isBestAbbreviation }) {
            // Native guarantees retain their visible slots; suggestions remain expandable.
            return CandidateWordRowPlacement(nativeCandidates, visibleAiCount = 0)
        }
        val availableWidth = (view.width - view.paddingLeft - view.paddingRight).coerceAtLeast(1)
        val maxSpanCount = maxSpanCountPref.getValue()
        val layoutMinWidth = when (fillStyle) {
            NeverFillWidth -> 0
            AutoFillWidth -> (view.width / maxSpanCount - dividerDrawable.intrinsicWidth).coerceAtLeast(0)
            AlwaysFillWidth -> 0
        }
        val maxCandidateCount = when (fillStyle) {
            NeverFillWidth -> Int.MAX_VALUE
            AutoFillWidth, AlwaysFillWidth -> maxSpanCount
        }
        val topPinyinWidths = nativeCandidates.filter { it.topPinyinAnnotation != null }
            .associate { it.textWithComment() to measuredCandidateWidth(it, layoutMinWidth) }
        val placement = placeAiCandidatesInRow(
            nativeCandidates = nativeCandidates.map { it.textWithComment() }.toTypedArray(),
            aiSuggestions = suggestions,
            availableWidth = availableWidth,
            dividerWidth = dividerDrawable.intrinsicWidth,
            maxCandidateCount = maxCandidateCount,
            measureAiCandidateWidth = { measuredCandidateTextWidth(it, layoutMinWidth) },
        ) {
            topPinyinWidths[it] ?: measuredCandidateTextWidth(it, layoutMinWidth)
        }
        return CandidateWordRowPlacement(
            candidates = nativeCandidates + aiSuggestions.take(placement.visibleAiCount)
                .map(::aiSuggestionCandidate)
                .toTypedArray(),
            visibleAiCount = placement.visibleAiCount,
        )
    }

    private suspend fun captureForwardRowShiftSnapshot(): ForwardRowShiftSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            if (viewportApplying || !hasRowSwipeCandidates()) return@withContext null
            val currentStart = nativeCandidateSnapshot.indexOffset
            val currentCandidates = nativeCandidateSnapshot.candidates.copyOf()
            ForwardRowShiftSnapshot(
                currentStart = currentStart,
                currentCandidates = currentCandidates,
                currentExcludedIndices = nativeCandidateSnapshot.excludedIndices,
                nextStart = nextNativeCandidateStart,
                nextLimit = candidateFetchBatchSize(),
                nextExcludedIndices = excludedNativeCandidateIndices,
            )
        }

    suspend fun shiftDisplayedCandidateRow(delta: Int): Boolean = when {
        delta > 0 -> {
            val snapshot = captureForwardRowShiftSnapshot() ?: return false
            val nextCandidates = fcitx.runOnReady {
                getCandidates(snapshot.nextStart, snapshot.nextLimit)
            }
            if (nextCandidates.isEmpty()) return false
            withContext(Dispatchers.Main.immediate) {
                rowWindowHistory.addLast(
                    RowWindow(snapshot.currentStart, snapshot.currentCandidates, snapshot.currentExcludedIndices)
                )
                renderCandidateWindow(
                    nextCandidates,
                    -1,
                    snapshot.nextStart,
                    activeCandidateIndex(0, normalizedSingleRowCandidates(nextCandidates).size),
                    snapshot.nextExcludedIndices,
                )
                true
            }
        }
        delta < 0 -> withContext(Dispatchers.Main.immediate) {
            if (rowWindowHistory.isEmpty()) {
                false
            } else {
                val previous = rowWindowHistory.removeLast()
                renderCandidateWindow(
                    previous.candidates,
                    -1,
                    previous.start,
                    activeCandidateIndex(0, normalizedSingleRowCandidates(previous.candidates).size),
                    previous.excludedIndices,
                )
                true
            }
        }
        else -> false
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val availableWidth = view.width - view.paddingLeft - view.paddingRight
                val protectedNativeRow = candidates.take(displayedNativeCount).toTypedArray()
                val recoveryWidthLimit = if (protectedNativeRow.any { it.isBestAbbreviation }) {
                    protectedCandidateRowWidthLimits(
                        protectedNativeRow, availableWidth, dividerDrawable.intrinsicWidth,
                    ) { measuredCandidateWidth(it, layoutMinWidth) }.getOrElse(position) { Int.MAX_VALUE }
                } else if (position <= 1) {
                    fallbackCandidateRowWidthLimit(
                        candidates, availableWidth, dividerDrawable.intrinsicWidth,
                    ) { measuredCandidateWidth(it, layoutMinWidth) }
                } else Int.MAX_VALUE
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    maxWidth = if (holder.candidate.isFallback) {
                        minOf(recoveryWidthLimit, (availableWidth - dividerDrawable.intrinsicWidth).coerceAtLeast(1))
                    } else recoveryWidthLimit
                    minWidth = layoutMinWidth.coerceIn(0, maxWidth)
                    flexGrow = layoutFlexGrow
                }
                if (position == activeIndex) {
                    holder.ui.applyFirstCandidateStyle(
                        bgColor = theme.genericActiveBackgroundColor,
                        strokeColor = theme.dividerColor,
                        pressColor = theme.keyPressHighlightColor
                    )
                } else {
                    holder.ui.resetToDefaultBackground(theme.keyPressHighlightColor)
                }
                holder.ui.setFontScale(
                    if (inlineMode) INLINE_CANDIDATE_FONT_SCALE else 1f
                )
                holder.ui.setIndexLabel(indexLabelForPosition(position, indexLabelsEnabled))
                val isCalculatorCandidate = isCalculatorCandidatePosition(position)
                val isAiCandidate = isAiCandidatePosition(position)
                holder.itemView.setOnClickListener {
                    if (viewportApplying) return@setOnClickListener
                    if (isCalculatorCandidate) {
                        calculatorSuggestion?.let(inputView::commitCalculatorSuggestionFromUi)
                    } else if (isAiCandidate) {
                        val currentPosition = holder.bindingAdapterPosition
                        if (!hasVisiblePredictionCandidate(currentPosition + 1) ||
                            !isAiCandidatePosition(currentPosition) ||
                            adapter.candidates[currentPosition].text != holder.text
                        ) return@setOnClickListener
                        inputView.commitAiSuggestionFromUi(holder.text)
                    } else {
                        service.postFcitxJob { select(holder.idx) }
                    }
                }
                // The long-press action menu, decompose ("拆字") and reset-frequency ("重置词频")
                // gestures are all owned by bindCandidateGesture. Calculator/AI candidates have no
                // such actions, so their gesture (and long-press listener) is unbound instead.
                if (viewportApplying || isCalculatorCandidate || isAiCandidate) {
                    inputView.unbindCandidateGesture(holder.ui.root)
                } else {
                    inputView.bindCandidateGesture(holder.ui.root, holder.idx, holder.text)
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                inputView.unbindCandidateGesture(holder.ui.root)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = false
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.candidates.size) {
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshExpanded()
                recordFirstPageCandidates()
                view.post { recordFirstPageCandidates() }
                view.post { requestCandidateViewportProbe() }
            }
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w != oldw) {
                    firstPageCandidates.clear()
                    post { recordFirstPageCandidates() }
                }
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                }
                if (w != oldw) {
                    post {
                        renderCurrentCandidates()
                        requestCandidateViewportProbe()
                    }
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        if (pagedCandidateFlowActive && data.total == -1) {
            pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
            pendingLegacyCandidateUpdate = null
            if (data.candidates.isEmpty()) {
                firstPageCandidates.clear()
                updateNativeCandidateSnapshot(emptyArray(), 0, 0, -1)
                renderCurrentCandidates()
            }
            return
        }
        pagedCandidateFlowActive = false
        viewportPolicyActive = data.candidates.any { it.nativeIndex >= 0 }
        resetRowWindowState()
        lastPagedData = null
        val candidates = data.candidates
        val total = data.total
        if (!nativeCandidateSnapshot.candidates.contentEquals(candidates) ||
            nativeCandidateSnapshot.indexOffset != 0
        ) {
            activeCandidateOverride = null
            firstPageCandidates.clear()
        }
        val activeIndex = activeCandidateIndex(0, normalizedSingleRowCandidates(candidates).size)
        updateNativeCandidateSnapshot(
            candidates = candidates,
            total = total,
            indexOffset = 0,
            activeIndex = activeIndex,
        )
        engineNativeCursorIndex = activeIndex
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = Runnable {
            pendingLegacyCandidateUpdate = null
            renderCurrentCandidates()
        }.also(view::post)
    }

    override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) {
        pagedCandidateFlowActive = true
        viewportPolicyActive = data.candidates.any { it.nativeIndex >= 0 }
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = null
        if (data == lastPagedData) {
            lastPagedData = data
            requestCandidateViewportProbe(force = true)
            return
        }
        lastPagedData = data
        val candidates = data.candidates
        if (!data.hasPrev && !nativeCandidateSnapshot.candidates.contentEquals(candidates)) {
            firstPageCandidates.clear()
        }
        if (!nativeCandidateSnapshot.candidates.contentEquals(candidates) ||
            nativeCandidateSnapshot.indexOffset != 0
        ) {
            activeCandidateOverride = null
        }
        resetRowWindowState()
        val nativeCursorIndex = candidates.getOrNull(data.cursorIndex)?.nativeIndex?.takeIf { it >= 0 }
            ?: data.cursorIndex
        val normalizedLaterPage = data.hasPrev && candidates.isNotEmpty() && candidates.all { it.nativeIndex >= 0 }
        val indexOffset = if (normalizedLaterPage) candidates.minOf { it.nativeIndex } else 0
        val excludedIndices = if (normalizedLaterPage) {
            firstPageCandidates.recordedIndices().filterTo(HashSet()) { it >= indexOffset }
        } else emptySet()
        renderCandidateWindow(candidates, -1, indexOffset, nativeCursorIndex - indexOffset, excludedIndices)
        engineNativeCursorIndex = nativeCursorIndex
    }

    // memo of the last rendered inputs; identical inputs produce no new render work
    private var lastRenderedCandidates: Array<CandidateWord>? = null
    private var lastRenderedTotal = Int.MIN_VALUE
    private var lastRenderedActiveIndex = Int.MIN_VALUE
    private var lastRenderedIndexOffset = Int.MIN_VALUE
    private var lastRenderedAvailableWidth = Int.MIN_VALUE
    private var lastRenderedMaxSpanCount = Int.MIN_VALUE
    private var lastRenderedFillStyle: HorizontalCandidateMode? = null

    private fun updateCandidates(
        candidates: Array<CandidateWord>,
        total: Int,
        activeIndex: Int,
        indexOffset: Int,
    ) {
        val maxSpanCount = maxSpanCountPref.getValue()
        if (
            lastRenderedTotal == total &&
            lastRenderedActiveIndex == activeIndex &&
            lastRenderedIndexOffset == indexOffset &&
            lastRenderedAvailableWidth == view.width &&
            lastRenderedMaxSpanCount == maxSpanCount &&
            lastRenderedFillStyle == fillStyle &&
            lastRenderedCandidates.contentEquals(candidates)
        ) {
            // A deferred-only engine list can change while the visible row stays empty.
            if (candidates.isEmpty()) {
                bar.syncCandidateBarState(candidateEmpty = true)
                refreshExpanded()
            }
            return
        }
        lastRenderedTotal = total
        lastRenderedActiveIndex = activeIndex
        lastRenderedIndexOffset = indexOffset
        lastRenderedAvailableWidth = view.width
        lastRenderedMaxSpanCount = maxSpanCount
        lastRenderedFillStyle = fillStyle
        lastRenderedCandidates = candidates
        val sizing = resolveHorizontalCandidateLayoutSizing(
            fillStyle = fillStyle,
            candidateCount = candidates.size,
            availableWidth = view.width,
            maxSpanCount = maxSpanCount,
            dividerWidth = dividerDrawable.intrinsicWidth,
        )
        layoutMinWidth = sizing.minWidth
        layoutFlexGrow = sizing.flexGrow
        secondLayoutPassNeeded = sizing.secondLayoutPassNeeded
        secondLayoutPassDone = false
        adapter.updateCandidates(
            candidates,
            total,
            effectiveActiveCandidateIndex(activeIndex),
            indexOffset,
        )
        bar.syncCandidateBarState(candidateEmpty = !hasVisibleCandidateContent(candidates))
        syncDynamicBarHeight(candidates)
        if (candidates.isEmpty()) {
            refreshExpanded()
        }
    }

    fun showCandidateActionMenu(holder: CandidateViewHolder) {
        inputView.showCandidateActionMenu(holder.idx, holder.text, holder.ui.root)
    }

    fun updateAiSuggestions(
        mode: LlmPrefs.PredictionDisplayMode,
        suggestions: List<String>,
    ) {
        aiDisplayMode = mode
        aiSuggestions = suggestions.filter { it.isNotBlank() }
        renderCurrentCandidates()
    }

    internal fun updateCalculatorSuggestion(suggestion: String?) {
        if (calculatorSuggestion == suggestion) return
        calculatorSuggestion = suggestion
        renderCurrentCandidates()
    }

    private fun renderCandidateWindow(
        candidates: Array<CandidateWord>,
        total: Int,
        indexOffset: Int,
        activeIndex: Int,
        excludedIndices: Set<Int> = emptySet(),
    ) {
        updateNativeCandidateSnapshot(candidates, total, indexOffset, activeIndex, excludedIndices)
        renderCurrentCandidates()
    }

    private fun updateNativeCandidateSnapshot(
        candidates: Array<CandidateWord>,
        total: Int,
        indexOffset: Int,
        activeIndex: Int,
        excludedIndices: Set<Int> = emptySet(),
    ) {
        nativeCandidateSnapshot = NativeCandidateSnapshot(
            candidates = candidates,
            total = total,
            indexOffset = indexOffset,
            activeIndex = activeIndex,
            excludedIndices = excludedIndices,
        )
    }

    private fun clearNativeCandidateFlow() {
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = null
        pagedCandidateFlowActive = false
        lastPagedData = null
    }

    private fun renderCurrentCandidates() {
        expandedAiSuggestions = emptyList()
        displayedAiStartIndex = -1
        displayedCalculatorIndex = -1

        val normalizedNative = nativeRowCandidates()
        val continuation = nativeCandidateContinuation(
            nativeCandidateSnapshot.indexOffset, normalizedNative, nativeCandidateSnapshot.excludedIndices,
        )
        nextNativeCandidateStart = continuation.nextOffset
        excludedNativeCandidateIndices = continuation.excludedIndices
        val nativeActiveIndex = nativeCandidateSnapshot.indexOffset + nativeCandidateSnapshot.activeIndex
        val displayedActiveIndex = displayedNativeCandidateIndex(
            nativeActiveIndex, normalizedNative, nativeCandidateSnapshot.indexOffset,
        )
        displayedNativeCount = normalizedNative.size
        hasExpandedNativeCandidates = hasMoreNativeCandidates(continuation)

        val extraSuggestions = buildList {
            calculatorSuggestion?.let(::add)
            addAll(aiSuggestions)
        }
        showingAiSuggestions = false
        if (aiDisplayMode == LlmPrefs.PredictionDisplayMode.CandidateExpanded &&
            extraSuggestions.isNotEmpty()
        ) {
            // Calculator results are mandatory row content. Only AI suggestions are
            // width-limited and moved to the expanded area when they do not fit.
            val calculatorCandidate = calculatorSuggestion?.let(::aiSuggestionCandidate)
            val rowNativeCandidates = if (calculatorCandidate != null) {
                normalizedNative + calculatorCandidate
            } else {
                normalizedNative
            }
            val placement = placeAiSuggestionsAfterNative(rowNativeCandidates, aiSuggestions)
            val visibleAiCount = placement.visibleAiCount
            displayedCalculatorIndex =
                normalizedNative.size.takeIf { calculatorCandidate != null } ?: -1
            displayedAiStartIndex = normalizedNative.size
                .plus(if (calculatorCandidate != null) 1 else 0)
                .takeIf { visibleAiCount > 0 }
                ?: -1
            expandedAiSuggestions = aiSuggestions.drop(visibleAiCount)
            hasExpandableCandidates = hasExpandedNativeCandidates || expandedAiSuggestions.isNotEmpty()
            updateCandidates(
                placement.candidates,
                nativeCandidateSnapshot.total,
                displayedActiveIndex.takeIf {
                    it >= 0 || normalizedNative.isNotEmpty()
                } ?: activeCandidateIndex(0, placement.candidates.size),
                nativeCandidateSnapshot.indexOffset,
            )
            requestCandidateViewportProbe()
            return
        }

        hasExpandableCandidates = hasExpandedNativeCandidates
        displayedCalculatorIndex = calculatorSuggestion?.let { normalizedNative.size } ?: -1
        val calculatorCandidates = calculatorSuggestion
            ?.let { arrayOf(aiSuggestionCandidate(it)) }
            ?: emptyArray()
        val renderedCandidates = normalizedNative + calculatorCandidates
        updateCandidates(
            renderedCandidates,
            nativeCandidateSnapshot.total,
            displayedActiveIndex.takeIf {
                it >= 0 || normalizedNative.isNotEmpty()
            } ?: activeCandidateIndex(0, renderedCandidates.size),
            nativeCandidateSnapshot.indexOffset,
        )
        requestCandidateViewportProbe()
    }

    private fun renderAiOnlyCandidates(
        candidates: Array<String>,
        calculatorIncluded: Boolean,
    ) {
        showingAiSuggestions = aiSuggestions.isNotEmpty()
        displayedNativeCount = 0
        displayedCalculatorIndex = if (calculatorIncluded) 0 else -1
        displayedAiStartIndex = if (aiSuggestions.isNotEmpty()) {
            if (calculatorIncluded) 1 else 0
        } else {
            -1
        }
        hasExpandedNativeCandidates = false
        hasExpandableCandidates = false
        val singleRowCandidates = normalizedSingleRowCandidates(
            candidates.map(::aiSuggestionCandidate).toTypedArray()
        )
        updateCandidates(
            singleRowCandidates,
            candidates.size,
            activeCandidateIndex(0, singleRowCandidates.size),
            0,
        )
    }

    private fun hasMoreNativeCandidates(continuation: NativeCandidateContinuation): Boolean {
        if (nativeCandidateSnapshot.candidates.isEmpty()) return false
        return nativeCandidateSnapshot.total == -1 || nativeCandidateSnapshot.total >
            continuation.nextOffset + continuation.excludedIndices.size
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean) {
        firstPageCandidates.clear()
        clearNativeCandidateFlow()
        resetRowWindowState()
        updateNativeCandidateSnapshot(emptyArray(), 0, 0, -1)
        aiSuggestions = emptyList()
        calculatorSuggestion = null
        renderCurrentCandidates()
    }

    private companion object {
        const val INLINE_CANDIDATE_FONT_SCALE = 0.9f
    }
}
