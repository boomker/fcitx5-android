/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.floating

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.View.MeasureSpec
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FcitxEvent.PagedCandidateEvent.LayoutHint
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.input.font.FontProviders
import splitties.views.dsl.core.Ui

/**
 * Floating candidate page content. A plain [FlexboxLayout] is used instead of a
 * RecyclerView+LayoutManager on purpose: page content is small, and a deterministic
 * single-pass measure keeps the wrapping window from oscillating (growing a blank band
 * between rows, or flickering blank) when the highlight moves or candidates update.
 */
class PagedCandidatesUi(
    override val ctx: Context,
    val theme: Theme,
    private val setupTextView: TextView.() -> Unit,
    private val onCandidateClick: (Int) -> Unit,
    private val onCandidateAction: (Int, String, View) -> Unit,
    private val onBindCandidateGesture: (CustomGestureView, Int, String) -> Unit,
    private val onUnbindCandidateGesture: (CustomGestureView) -> Unit,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
    private val highlightRadius: Float
) : Ui {

    private var data = FcitxEvent.PagedCandidateEvent.Data.Empty
    private var activeIndex = -1
    private var fontGeneration = -1L

    private var isVertical = false
    private var windowAboveCursor = false

    val isReversed: Boolean
        get() = isVertical && windowAboveCursor

    private val measurementCandidateUi by lazy {
        LabeledCandidateItemUi(ctx, theme, setupTextView, highlightRadius).also {
            it.root.layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
    }

    private val measurementPaginationUi by lazy {
        PaginationUi(ctx, theme).also {
            it.root.layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
    }

    override val root = FlexboxLayout(ctx).apply {
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        flexWrap = FlexWrap.WRAP
    }

    fun update(
        data: FcitxEvent.PagedCandidateEvent.Data,
        orientation: FloatingCandidatesOrientation,
        maxRowWidthPx: Int,
        activeIndexOverride: Int? = null,
    ) {
        // Compute new vertical layout decision before any state mutation
        val newIsVertical = when (orientation) {
            FloatingCandidatesOrientation.Automatic -> shouldUseVerticalLayout(data, maxRowWidthPx)
            else -> orientation == FloatingCandidatesOrientation.Vertical
        }
        val newActiveIndex = activeIndexOverride ?: data.cursorIndex
        // Skip update if nothing changed to avoid unnecessary rebind/redraw.
        if (this.data == data && this.isVertical == newIsVertical && this.activeIndex == newActiveIndex &&
            fontGeneration == FontProviders.fontGeneration) return

        this.data = data
        this.isVertical = newIsVertical
        this.activeIndex = newActiveIndex
        fontGeneration = FontProviders.fontGeneration
        updateFlexDirection()
        root.alignItems = if (isVertical) AlignItems.STRETCH else AlignItems.BASELINE
        reconcileChildren()
    }

    fun setWindowAboveCursor(above: Boolean) {
        if (windowAboveCursor == above) return
        windowAboveCursor = above
        updateFlexDirection()
    }

    private fun updateFlexDirection() {
        // Reverse only visual layout: child indices, labels and actions still refer
        // to the engine's original candidate order, including numeric selection.
        root.flexDirection = when {
            isReversed -> FlexDirection.COLUMN_REVERSE
            isVertical -> FlexDirection.COLUMN
            else -> FlexDirection.ROW
        }
    }

    private fun itemCount(): Int =
        data.candidates.size + (if (data.hasPrev || data.hasNext) 1 else 0)

    /**
     * Reconcile the flex children against the current page: reuse existing item views per
     * position (matching their tag type), create or drop views as the page size changes.
     */
    private fun reconcileChildren() {
        val count = itemCount()
        while (root.childCount > count) {
            val last = root.getChildAt(root.childCount - 1)
            detachChild(last)
            root.removeView(last)
        }
        for (position in 0 until count) {
            val existing = root.getChildAt(position)
            val wantedType = if (position < data.candidates.size) {
                LabeledCandidateItemUi::class.java
            } else {
                PaginationUi::class.java
            }
            val child = if (existing != null && existing.tag?.javaClass == wantedType) {
                existing
            } else {
                if (existing != null) {
                    detachChild(existing)
                    root.removeViewAt(position)
                }
                val created = when (wantedType) {
                    LabeledCandidateItemUi::class.java -> createCandidateView()
                    else -> createPaginationView()
                }
                root.addView(created, position)
                created
            }
            bindChild(child, position)
        }
    }

    private fun createCandidateView(): View {
        val ui = LabeledCandidateItemUi(ctx, theme, setupTextView, highlightRadius)
        ui.root.layoutParams = FlexboxLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        ui.root.tag = ui
        return ui.root
    }

    private fun createPaginationView(): View {
        val ui = PaginationUi(ctx, theme)
        ui.prevIcon.setOnClickListener { onPrevPage.invoke() }
        ui.nextIcon.setOnClickListener { onNextPage.invoke() }
        ui.root.layoutParams = FlexboxLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        ui.root.tag = ui
        return ui.root
    }

    private fun bindChild(child: View, position: Int) {
        when (val tag = child.tag) {
            is LabeledCandidateItemUi -> {
                val candidate = data.candidates[position]
                tag.update(candidate, active = position == activeIndex)
                child.setOnClickListener {
                    onCandidateClick.invoke(position)
                }
                child.setOnLongClickListener { v ->
                    onCandidateAction.invoke(position, candidate.text, v)
                    true
                }
                onBindCandidateGesture(child as CustomGestureView, position, candidate.text)
                child.updateLayoutParams<FlexboxLayout.LayoutParams> {
                    width = if (isVertical) MATCH_PARENT else WRAP_CONTENT
                }
            }
            is PaginationUi -> {
                tag.update(data)
                child.updateLayoutParams<FlexboxLayout.LayoutParams> {
                    flexGrow = 1f
                    width = if (isVertical) MATCH_PARENT else WRAP_CONTENT
                    alignSelf = if (isVertical) AlignItems.STRETCH else AlignItems.CENTER
                }
            }
        }
    }

    private fun detachChild(child: View) {
        if (child.tag is LabeledCandidateItemUi) {
            child.setOnClickListener(null)
            child.setOnLongClickListener(null)
            onUnbindCandidateGesture(child as CustomGestureView)
        }
    }

    private fun shouldUseVerticalLayout(
        data: FcitxEvent.PagedCandidateEvent.Data,
        maxRowWidthPx: Int
    ): Boolean {
        if (data.layoutHint == LayoutHint.Vertical) {
            return true
        }
        if (maxRowWidthPx <= 0) {
            return false
        }

        val totalWidth = data.candidates.sumOf { candidate ->
            measurementCandidateUi.update(candidate, active = false)
            measurementCandidateUi.root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            measurementCandidateUi.root.measuredWidth
        } + if (data.hasPrev || data.hasNext) {
            measurementPaginationUi.update(data)
            measurementPaginationUi.root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            measurementPaginationUi.root.measuredWidth
        } else {
            0
        }

        // Keep automatic mode visually regular: only use horizontal when the
        // whole row fits without wrapping, otherwise fall back to one-item-per-line.
        return totalWidth > maxRowWidthPx
    }
}
