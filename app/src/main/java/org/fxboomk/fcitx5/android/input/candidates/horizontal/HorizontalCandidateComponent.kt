/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.widget.PopupMenu
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.data.InputFeedbacks
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
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
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.utils.item
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import java.util.ArrayDeque
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val fcitx by manager.fcitx()
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
    private val rowWindowHistory = ArrayDeque<RowWindow>()

    private data class RowWindow(
        val start: Int,
        val candidates: Array<String>,
    )

    private data class ForwardRowShiftSnapshot(
        val currentStart: Int,
        val currentCandidates: Array<String>,
        val nextStart: Int,
        val nextLimit: Int,
    )

    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val measurementCandidateUi by lazy {
        CandidateItemUi(context, theme).also { ui ->
            ui.root.minimumWidth = context.dp(40)
            ui.root.setPadding(context.dp(10), 0, context.dp(10), 0)
            ui.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded(childCount: Int) {
        val consumedCount = adapter.indexOffset + childCount
        _expandedCandidateOffset.tryEmit(consumedCount)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (adapter.total == consumedCount)
        )
    }

    private fun resetRowWindowState() {
        rowWindowHistory.clear()
    }

    fun hasRowSwipeCandidates(): Boolean = pagedCandidateFlowActive && adapter.candidates.isNotEmpty()

    fun isRowShifted(): Boolean = rowWindowHistory.isNotEmpty()

    private fun candidateFetchBatchSize(): Int = max(maxSpanCountPref.getValue() * 3, 24)

    private fun activeIndexFor(candidates: Array<String>): Int = if (candidates.isNotEmpty()) 0 else -1

    private fun measuredCandidateWidth(candidate: String, layoutMinWidth: Int): Int {
        measurementCandidateUi.apply {
            root.minimumWidth = max(context.dp(40), layoutMinWidth)
            text.text = candidate
            applyConfiguredTypeface()
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        return measurementCandidateUi.root.measuredWidth
    }

    private fun singleRowCount(candidates: Array<String>): Int {
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

    private fun normalizedSingleRowCandidates(candidates: Array<String>): Array<String> {
        val count = singleRowCount(candidates).coerceAtMost(candidates.size)
        return if (count == candidates.size) candidates else candidates.copyOfRange(0, count)
    }

    private fun renderCandidateWindow(
        candidates: Array<String>,
        total: Int,
        indexOffset: Int,
    ) {
        val singleRowCandidates = normalizedSingleRowCandidates(candidates)
        updateCandidates(
            singleRowCandidates,
            total,
            activeIndexFor(singleRowCandidates),
            indexOffset
        )
    }

    private suspend fun captureForwardRowShiftSnapshot(): ForwardRowShiftSnapshot? =
        withContext(Dispatchers.Main.immediate) {
            if (!hasRowSwipeCandidates()) return@withContext null
            val currentStart = adapter.indexOffset
            val currentCandidates = adapter.candidates.copyOf()
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
                renderCandidateWindow(nextCandidates, -1, snapshot.nextStart)
                true
            }
        }
        delta < 0 -> withContext(Dispatchers.Main.immediate) {
            if (rowWindowHistory.isEmpty()) {
                false
            } else {
                val previous = rowWindowHistory.removeLast()
                renderCandidateWindow(previous.candidates, -1, previous.start)
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
                if (position == 0) {
                    holder.ui.applyFirstCandidateStyle(
                        bgColor = theme.genericActiveBackgroundColor,
                        strokeColor = theme.dividerColor,
                        pressColor = theme.keyPressHighlightColor
                    )
                } else if (position != activeIndex) {
                    holder.ui.resetToDefaultBackground(theme.keyPressHighlightColor)
                }
                holder.itemView.setOnClickListener {
                    fcitx.launchOnReady { it.select(holder.idx) }
                }
                holder.itemView.setOnLongClickListener {
                    showCandidateActionMenu(holder)
                    true
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
                refreshExpanded(cnt)
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
            return
        }
        pagedCandidateFlowActive = false
        resetRowWindowState()
        lastPagedData = null
        val candidates = data.candidates
        val total = data.total
        pendingLegacyCandidateUpdate?.let(view::removeCallbacks)
        pendingLegacyCandidateUpdate = Runnable {
            pendingLegacyCandidateUpdate = null
            renderCandidateWindow(candidates, total, 0)
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
        val candidates = data.candidates.map { candidate ->
            buildString {
                append(candidate.text)
                if (candidate.comment.isNotBlank()) {
                    append(' ')
                    append(candidate.comment)
                }
            }
        }.toTypedArray()
        resetRowWindowState()
        renderCandidateWindow(candidates, -1, 0)
    }

    private fun updateCandidates(
        candidates: Array<String>,
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
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx) }
    }

    private var candidateActionMenu: PopupMenu? = null

    fun showCandidateActionMenu(holder: CandidateViewHolder) {
        val idx = holder.idx
        val text = holder.text
        val view = holder.ui.root
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActions(idx) }
            if (actions.isEmpty()) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            candidateActionMenu = PopupMenu(context, view).apply {
                menu.add(buildSpannedString {
                    bold {
                        color(context.styledColor(android.R.attr.colorAccent)) {
                            append(text)
                        }
                    }
                }).apply {
                    isEnabled = false
                }
                actions.forEach { action ->
                    menu.item(action.text) {
                        triggerCandidateAction(idx, action.id)
                    }
                }
                setOnDismissListener {
                    candidateActionMenu = null
                }
                show()
            }
        }
    }
}
