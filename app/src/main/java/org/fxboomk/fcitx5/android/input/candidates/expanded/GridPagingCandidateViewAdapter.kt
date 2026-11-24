/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.expanded

import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.candidates.CandidateAnnotationLayout
import kotlin.math.max
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

abstract class GridPagingCandidateViewAdapter(theme: Theme) : PagingCandidateViewAdapter(theme) {

    companion object {
        // 20f here is chosen randomly, since we only care about the ratio
        private const val TEXT_SIZE = 20f
    }

    // cache measureWidth
    private val measuredWidths = object : LruCache<String, Float>(200) {
        private val cachedPaint = Paint().apply { textSize = TEXT_SIZE }
        private val cachedRect = Rect()
        override fun create(key: String): Float {
            cachedPaint.getTextBounds(key, 0, key.length, cachedRect)
            return cachedRect.width() / TEXT_SIZE
        }
    }

    fun measureWidth(position: Int): Float {
        val candidate = getItem(position) ?: return 0f
        val pinyin = candidate.topPinyinAnnotation
        return if (pinyin == null) measuredWidths[candidate.textWithComment()] else {
            max(measuredWidths[candidate.text], measuredWidths[pinyin] * CandidateAnnotationLayout.PINYIN_SCALE)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return super.onCreateViewHolder(parent, viewType).apply {
            itemView.apply {
                minimumHeight = dp(40)
                layoutParams = GridLayoutManager.LayoutParams(matchParent, dp(40))
            }
        }
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val height = if (holder.candidate.topPinyinAnnotation != null) holder.itemView.wrapContent else holder.itemView.dp(40)
        val params = holder.itemView.layoutParams
        if (params.height != height) {
            params.height = height
            holder.itemView.layoutParams = params
        }
    }
}
