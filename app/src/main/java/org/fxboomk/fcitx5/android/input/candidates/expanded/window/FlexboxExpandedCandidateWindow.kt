/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.expanded.window

import android.util.DisplayMetrics
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fxboomk.fcitx5.android.input.candidates.expanded.PagingCandidateViewAdapter
import org.fxboomk.fcitx5.android.input.candidates.expanded.decoration.FlexboxHorizontalDecoration
import splitties.dimensions.dp
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

class FlexboxExpandedCandidateWindow :
    BaseExpandedCandidateWindow<FlexboxExpandedCandidateWindow>() {

    private var candidateRecyclerView: RecyclerView? = null
    private var hasTopPinyinAnnotations = false

    private fun refreshCandidateAlignment() {
        val recycler = candidateRecyclerView ?: return
        // Page updates can arrive while RecyclerView is dispatching layout.
        // Use the latest snapshot after that pass rather than changing its
        // LayoutManager from an item bind.
        recycler.post {
            if (candidateRecyclerView !== recycler) return@post
            val hasTopPinyin = adapter.snapshot().items.any { it.topPinyinAnnotation != null }
            val annotationStateChanged = hasTopPinyinAnnotations != hasTopPinyin
            hasTopPinyinAnnotations = hasTopPinyin
            val alignment = if (hasTopPinyin) {
                AlignItems.STRETCH
            } else AlignItems.FLEX_START
            if (layoutManager.alignItems != alignment) layoutManager.alignItems = alignment
            if (annotationStateChanged) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    override val adapter by lazy {
        object : PagingCandidateViewAdapter(theme) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
                return super.onCreateViewHolder(parent, viewType).apply {
                    itemView.apply {
                        minimumWidth = dp(40)
                        minimumHeight = dp(40)
                        setPaddingDp(10, 0, 10, 0)
                        layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, dp(40))
                            .apply { flexGrow = 1f }
                    }
                }
            }

            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val height = if (hasTopPinyinAnnotations || holder.candidate.topPinyinAnnotation != null) {
                    holder.itemView.wrapContent
                } else {
                    holder.itemView.dp(40)
                }
                val params = holder.itemView.layoutParams
                if (params.height != height) {
                    params.height = height
                    holder.itemView.layoutParams = params
                }
                bindCandidateUiViewHolder(holder)
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                recycleCandidateViewHolder(holder)
                super.onViewRecycled(holder)
            }
        }.also { it.addOnPagesUpdatedListener(::refreshCandidateAlignment) }
    }

    override val layoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            justifyContent = JustifyContent.SPACE_AROUND
            alignItems = AlignItems.FLEX_START
        }
    }

    override fun onCreateCandidateLayout(): ExpandedCandidateLayout =
        ExpandedCandidateLayout(
            context = context,
            theme = theme,
            onSuggestionClick = { suggestion ->
                inputView.commitAiSuggestionFromUi(suggestion, fromExpandedArea = true)
            },
            onQuestionAnswerClick = { inputView.toggleAiQuestionAnswerMode() },
            onPersonaClick = { anchor -> inputView.showAiPersonaPicker(anchor) },
            onTranslateClick = { inputView.toggleAiTranslateMode() },
            onLongFormClick = { inputView.toggleAiLongFormMode() },
        ).apply {
            recyclerView.apply {
                candidateRecyclerView = this
                adapter = this@FlexboxExpandedCandidateWindow.adapter
                layoutManager = this@FlexboxExpandedCandidateWindow.layoutManager
                addItemDecoration(FlexboxHorizontalDecoration(dividerDrawable))
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        this@FlexboxExpandedCandidateWindow.layoutManager.apply {
                            pageUpBtn?.isEnabled = findFirstCompletelyVisibleItemPosition() > 0
                            pageDnBtn?.isEnabled =
                                findLastCompletelyVisibleItemPosition() < itemCount - 1
                        }
                    }
                })
                refreshCandidateAlignment()
            }
        }

    override fun prevPage() {
        val itemCount = minOf(layoutManager.itemCount, adapter.itemCount)
        if (itemCount <= 0) return
        layoutManager.apply {
            var prev = findFirstCompletelyVisibleItemPosition() - 1
            if (prev < 0) prev = 0
            if (prev !in 0 until itemCount) return
            startSmoothScroll(object : LinearSmoothScroller(context) {
                override fun getVerticalSnapPreference() = SNAP_TO_END
                override fun calculateSpeedPerPixel(dm: DisplayMetrics?) =
                    if (disableAnimation) Float.MIN_VALUE else super.calculateSpeedPerPixel(dm)
            }.apply { targetPosition = prev })
        }
    }

    override fun nextPage() {
        val itemCount = minOf(layoutManager.itemCount, adapter.itemCount)
        if (itemCount <= 0) return
        layoutManager.apply {
            var next = findLastCompletelyVisibleItemPosition() + 1
            if (next >= itemCount) next = itemCount - 1
            if (next !in 0 until itemCount) return
            startSmoothScroll(object : LinearSmoothScroller(context) {
                override fun getVerticalSnapPreference() = SNAP_TO_START
                override fun calculateSpeedPerPixel(dm: DisplayMetrics?) =
                    if (disableAnimation) Float.MIN_VALUE else super.calculateSpeedPerPixel(dm)
            }.apply { targetPosition = next })
        }
    }

}
