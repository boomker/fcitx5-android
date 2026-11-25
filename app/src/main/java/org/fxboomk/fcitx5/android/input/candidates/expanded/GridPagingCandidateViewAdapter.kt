/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.expanded

import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import android.view.ViewGroup
import android.view.View.MeasureSpec
import androidx.recyclerview.widget.GridLayoutManager
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.candidates.CandidateAnnotationStyle
import org.fxboomk.fcitx5.android.input.font.FontProviders
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
            val annotation = key.startsWith("annotation:")
            val text = key.substringAfter(':')
            cachedPaint.typeface = FontProviders.resolveTypeface(
                if (annotation) CandidateAnnotationStyle.FONT_KEY else "cand_font"
            )
            cachedPaint.getTextBounds(text, 0, text.length, cachedRect)
            return cachedRect.width() / TEXT_SIZE
        }
    }
    private var measuredFontGeneration = -1L

    fun measureWidth(position: Int): Float {
        val candidate = getItem(position) ?: return 0f
        if (measuredFontGeneration != FontProviders.fontGeneration) {
            measuredWidths.evictAll()
            measuredFontGeneration = FontProviders.fontGeneration
        }
        val bodyWidth = measuredWidths["body:${candidate.text}"]
        val bodyFontSize = FontProviders.getFontSize("cand_font", 20f).coerceAtLeast(1f)
        val annotationRatio = CandidateAnnotationStyle.fontSizeSp(bodyFontSize) / bodyFontSize
        val pinyin = candidate.topPinyinAnnotation
        if (pinyin != null) {
            return max(bodyWidth, measuredWidths["annotation:$pinyin"] * annotationRatio)
        }
        val comment = candidate.displayComment ?: candidate.comment
        if (comment.isBlank()) return bodyWidth
        val spaceWidth = if (candidate.spaceBetweenComment) {
            Paint().apply {
                textSize = TEXT_SIZE
                typeface = FontProviders.resolveTypeface("cand_font")
            }.measureText(" ") / TEXT_SIZE
        } else 0f
        return bodyWidth + spaceWidth + measuredWidths["annotation:$comment"] * annotationRatio
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
        val candidate = holder.candidate
        val hasAnnotation = (candidate.displayComment ?: candidate.comment).isNotBlank()
        if (hasAnnotation) {
            val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            holder.itemView.measure(unspecified, unspecified)
        }
        val height = if (candidate.topPinyinAnnotation != null ||
            (hasAnnotation && holder.itemView.measuredHeight > holder.itemView.dp(40))) {
            holder.itemView.wrapContent
        } else holder.itemView.dp(40)
        val params = holder.itemView.layoutParams
        if (params.height != height) {
            params.height = height
            holder.itemView.layoutParams = params
        }
    }
}
