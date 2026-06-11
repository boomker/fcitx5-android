/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.hasVisibleCandidateContent
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.candidates.CandidateItemUi
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
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
import kotlin.math.max

internal fun activeCandidateIndex(cursorIndex: Int, candidateCount: Int): Int {
    if (candidateCount <= 0) return -1
    return cursorIndex.coerceIn(0, candidateCount - 1)
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
        val occupiedWidth = measureCandidateWidth(suggestion) + safeDividerWidth
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

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
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
    private var expandedAiSuggestions: List<String> = emptyList()
    private val rowWindowHistory = ArrayDeque<RowWindow>()

    private data class NativeCandidateSnapshot(
        val candidates: Array<CandidateWord> = emptyArray(),
        val total: Int = 0,
        val indexOffset: Int = 0,
        val activeIndex: Int = -1,
    )

    private var nativeCandidateSnapshot = NativeCandidateSnapshot()

    private data class RowWindow(
        val start: Int,
        val candidates: Array<CandidateWord>,
    )

    private data class ForwardRowShiftSnapshot(
        val currentStart: Int,
        val currentCandidates: Array<CandidateWord>,
        val nextStart: Int,
        val nextLimit: Int,
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
            ui.root.setPadding(context.dp(8), 0, context.dp(8), 0)
            ui.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded() {
        val consumedCount = adapter.indexOffset + displayedNativeCount
        _expandedCandidateOffset.tryEmit(consumedCount)
        bar.syncExpandedCandidateState(hasExpandableCandidates = hasExpandableCandidates)
    }

    private fun resetRowWindowState() {
        rowWindowHistory.clear()
    }

    fun hasRowSwipeCandidates(): Boolean = pagedCandidateFlowActive && adapter.candidates.isNotEmpty()

    fun hasCandidates(): Boolean = adapter.candidates.isNotEmpty()

    fun hasNativeCandidates(): Boolean = nativeCandidateSnapshot.candidates.isNotEmpty()

    fun isShowingAiSuggestions(): Boolean = showingAiSuggestions

    fun currentExpandedAiSuggestions(): List<String> = expandedAiSuggestions

    fun hasExpandedNativeCandidates(): Boolean = hasExpandedNativeCandidates

    fun clearPredictionCandidates() {
        clearNativeCandidateFlow()
        resetRowWindowState()
        updateNativeCandidateSnapshot(emptyArray(), 0, 0, -1)
        aiSuggestions = emptyList()
        renderCurrentCandidates()
    }

    fun moveActiveCandidate(delta: Int): Boolean {
        if (delta == 0 || adapter.candidates.isEmpty()) return false
        val nextIndex = moveActiveCandidateIndex(adapter.activeIndex, delta, adapter.candidates.size)
        if (nextIndex == adapter.activeIndex) return false
        adapter.updateActiveIndex(nextIndex)
        return true
    }

    fun selectActiveCandidate(): Boolean {
        val idx = adapter.activeIndex
        if (idx !in adapter.candidates.indices) return false
        if (isAiCandidatePosition(idx)) {
            inputView.commitAiSuggestionFromUi(adapter.candidates[idx].text)
            return true
        }
        fcitx.launchOnReady { it.select(idx + adapter.indexOffset) }
        return true
    }

    private fun isAiCandidatePosition(position: Int): Boolean =
        showingAiSuggestions || (displayedAiStartIndex >= 0 && position >= displayedAiStartIndex)

    private fun candidateFetchBatchSize(): Int = max(maxSpanCountPref.getValue() * 3, 24)

    private fun measuredCandidateTextWidth(candidate: String, layoutMinWidth: Int): Int {
        measurementCandidateUi.apply {
            root.minimumWidth = max(context.dp(40), layoutMinWidth)
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
        return measuredCandidateTextWidth(candidate.textWithComment(), layoutMinWidth)
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

    private fun aiSuggestionCandidate(text: String) = CandidateWord("", text, "", false)

    private fun placeAiSuggestionsAfterNative(nativeCandidates: Array<CandidateWord>): CandidateWordRowPlacement {
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
        val placement = placeAiCandidatesInRow(
            nativeCandidates = nativeCandidates.map { it.textWithComment() }.toTypedArray(),
            aiSuggestions = aiSuggestions,
            availableWidth = availableWidth,
            dividerWidth = dividerDrawable.intrinsicWidth,
            maxCandidateCount = maxCandidateCount,
        ) {
            measuredCandidateTextWidth(it, layoutMinWidth)
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
            if (!hasRowSwipeCandidates()) return@withContext null
            val currentStart = nativeCandidateSnapshot.indexOffset
            val currentCandidates = nativeCandidateSnapshot.candidates.copyOf()
            val nextStart = currentStart + currentCandidates.size
            ForwardRowShiftSnapshot(
                currentStart = currentStart,
                currentCandidates = currentCandidates,
                nextStart = nextStart,
                nextLimit = candidateFetchBatchSize()
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
                    RowWindow(snapshot.currentStart, snapshot.currentCandidates)
                )
                renderCandidateWindow(
                    nextCandidates,
                    -1,
                    snapshot.nextStart,
                    activeCandidateIndex(0, normalizedSingleRowCandidates(nextCandidates).size)
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
                    activeCandidateIndex(0, normalizedSingleRowCandidates(previous.candidates).size)
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
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
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
                val isAiCandidate = isAiCandidatePosition(position)
                holder.itemView.setOnClickListener {
                    if (isAiCandidate) {
                        inputView.commitAiSuggestionFromUi(holder.text)
                    } else {
                        fcitx.launchOnReady { it.select(holder.idx) }
                    }
                }
                holder.itemView.setOnLongClickListener {
                    if (isAiCandidate) {
                        false
                    } else {
                        inputView.showCandidateActionMenu(holder.idx, holder.text, holder.ui.root)
                        true
                    }
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
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
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
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
                updateNativeCandidateSnapshot(emptyArray(), 0, 0, -1)
                renderCurrentCandidates()
            }
            return
        }
        pagedCandidateFlowActive = false
        resetRowWindowState()
        lastPagedData = null
        val candidates = data.candidates
        val total = data.total
        val activeIndex = activeCandidateIndex(0, normalizedSingleRowCandidates(candidates).size)
        updateNativeCandidateSnapshot(
            candidates = candidates,
            total = total,
            indexOffset = 0,
            activeIndex = activeIndex,
        )
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = Runnable {
            pendingLegacyCandidateUpdate = null
            renderCurrentCandidates()
        }.also(view::post)
    }

    override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) {
        pagedCandidateFlowActive = true
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = null
        if (data == lastPagedData) {
            return
        }
        lastPagedData = data
        val candidates = data.candidates
        resetRowWindowState()
        renderCandidateWindow(
            candidates,
            -1,
            0,
            activeCandidateIndex(data.cursorIndex, normalizedSingleRowCandidates(candidates).size)
        )
    }

    private fun updateCandidates(
        candidates: Array<CandidateWord>,
        total: Int,
        activeIndex: Int,
        indexOffset: Int,
    ) {
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
        adapter.updateCandidates(candidates, total, activeIndex, indexOffset)
        bar.syncCandidateBarState(candidateEmpty = !hasVisibleCandidateContent(candidates))
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

    private fun renderCandidateWindow(
        candidates: Array<CandidateWord>,
        total: Int,
        indexOffset: Int,
        activeIndex: Int,
    ) {
        updateNativeCandidateSnapshot(candidates, total, indexOffset, activeIndex)
        renderCurrentCandidates()
    }

    private fun updateNativeCandidateSnapshot(
        candidates: Array<CandidateWord>,
        total: Int,
        indexOffset: Int,
        activeIndex: Int,
    ) {
        nativeCandidateSnapshot = NativeCandidateSnapshot(
            candidates = candidates,
            total = total,
            indexOffset = indexOffset,
            activeIndex = activeIndex,
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

        val normalizedNative = normalizedSingleRowCandidates(nativeCandidateSnapshot.candidates)
        displayedNativeCount = normalizedNative.size
        hasExpandedNativeCandidates = hasMoreNativeCandidates(displayedNativeCount)

        if (aiDisplayMode == LlmPrefs.PredictionDisplayMode.CandidateBar && aiSuggestions.isNotEmpty()) {
            renderAiOnlyCandidates(aiSuggestions.toTypedArray())
            return
        }

        showingAiSuggestions = false
        if (aiDisplayMode == LlmPrefs.PredictionDisplayMode.CandidateExpanded && aiSuggestions.isNotEmpty()) {
            val placement = placeAiSuggestionsAfterNative(normalizedNative)
            val visibleAiCount = placement.visibleAiCount
            displayedAiStartIndex = normalizedNative.size.takeIf { visibleAiCount > 0 } ?: -1
            expandedAiSuggestions = aiSuggestions.drop(visibleAiCount)
            hasExpandableCandidates = hasExpandedNativeCandidates || expandedAiSuggestions.isNotEmpty()
            updateCandidates(
                placement.candidates,
                nativeCandidateSnapshot.total,
                nativeCandidateSnapshot.activeIndex,
                nativeCandidateSnapshot.indexOffset,
            )
            return
        }

        hasExpandableCandidates = hasExpandedNativeCandidates
        updateCandidates(
            normalizedNative,
            nativeCandidateSnapshot.total,
            nativeCandidateSnapshot.activeIndex,
            nativeCandidateSnapshot.indexOffset,
        )
    }

    private fun renderAiOnlyCandidates(candidates: Array<String>) {
        showingAiSuggestions = candidates.isNotEmpty()
        displayedNativeCount = 0
        displayedAiStartIndex = 0
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

    private fun hasMoreNativeCandidates(visibleNativeCount: Int): Boolean {
        if (nativeCandidateSnapshot.candidates.isEmpty()) return false
        val consumedCount = nativeCandidateSnapshot.indexOffset + visibleNativeCount
        return nativeCandidateSnapshot.total == -1 || nativeCandidateSnapshot.total > consumedCount
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        clearNativeCandidateFlow()
        resetRowWindowState()
        updateNativeCandidateSnapshot(emptyArray(), 0, 0, -1)
        aiSuggestions = emptyList()
        renderCurrentCandidates()
    }
}
