/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import android.content.Context
import android.view.View.MeasureSpec
import android.view.ViewGroup
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.candidates.CandidateItemUi
import org.fxboomk.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import kotlin.math.ceil

/** Measures configured text metrics, independent of the candidates currently visible. */
internal class AdaptiveCandidateBarHeight(context: Context, theme: Theme) {
    private val metrics = context.resources.displayMetrics
    private val candidateUi by lazy {
        CandidateItemUi(context, theme).apply {
            configureHorizontalHighlightSpacing(
                outerPadding = context.dp(HORIZONTAL_CANDIDATE_OUTER_PADDING_DP),
                highlightPadding = context.dp(HORIZONTAL_CANDIDATE_HIGHLIGHT_PADDING_DP),
                verticalPadding = context.dp(HORIZONTAL_CANDIDATE_VERTICAL_PADDING_DP),
            )
            root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private data class Configuration(
        val fonts: Long,
        val density: Float,
        val scaledDensity: Float,
        val annotationOnTop: Boolean,
        val inlineMode: Boolean,
    )

    private var configuration: Configuration? = null
    private var measuredHeightDp = 0

    fun heightDp(annotationOnTop: Boolean, inlineMode: Boolean): Int {
        val current = Configuration(
            FontProviders.fontGeneration, metrics.density, metrics.scaledDensity,
            annotationOnTop, inlineMode
        )
        if (current == configuration) return measuredHeightDp
        candidateUi.apply {
            // Always reserve the configured annotation layout, including for Rare/Auto
            // modes and an empty candidate list. The actual text must not resize the bar.
            updateForMeasurement(
                CandidateWord(
                    "", "测", "(cè)",
                    isPinyinAnnotationOnTop = annotationOnTop
                ),
                if (inlineMode) 0.9f else 1f
            )
            applyConfiguredTypeface()
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        measuredHeightDp = ceil(candidateUi.root.measuredHeight / metrics.density).toInt()
            .coerceAtLeast(KawaiiBarComponent.MIN_TOOLBAR_HEIGHT)
        configuration = current
        return measuredHeightDp
    }
}
