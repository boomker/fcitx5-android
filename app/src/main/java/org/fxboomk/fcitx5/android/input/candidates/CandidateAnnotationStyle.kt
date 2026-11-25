/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import org.fxboomk.fcitx5.android.input.font.FontProviders

/** Shared typography for candidate annotations, above or beside the candidate. */
internal object CandidateAnnotationStyle {
    const val FONT_KEY = "cand_comment_font"
    const val DEFAULT_FONT_SIZE = 11f
    const val MAX_SIZE_TO_BODY_RATIO = 0.5f

    @Volatile
    private var cachedTypefaceGeneration = -1L
    @Volatile
    private var cachedTypeface: Typeface? = null

    fun typeface(): Typeface {
        val generation = FontProviders.fontGeneration
        cachedTypeface?.takeIf { cachedTypefaceGeneration == generation }?.let { return it }
        return synchronized(this) {
            cachedTypeface?.takeIf { cachedTypefaceGeneration == generation }
                ?: FontProviders.resolveTypeface(FONT_KEY).also {
                    cachedTypeface = it
                    cachedTypefaceGeneration = generation
                }
        }
    }

    fun maxFontSizeSp(bodyFontSizeSp: Float): Float =
        bodyFontSizeSp.coerceAtLeast(0f) * MAX_SIZE_TO_BODY_RATIO

    fun capFontSizeSp(fontSizeSp: Float, bodyFontSizeSp: Float): Float =
        fontSizeSp.coerceAtLeast(0f).coerceAtMost(maxFontSizeSp(bodyFontSizeSp))

    fun fontSizeSp(bodyFontSizeSp: Float, scale: Float = 1f): Float = capFontSizeSp(
        FontProviders.getFontSize(FONT_KEY, DEFAULT_FONT_SIZE) * scale,
        bodyFontSizeSp * scale,
    )

    fun textSizePx(context: Context, bodyTextSizePx: Float, scale: Float = 1f): Float {
        val metrics = context.resources.displayMetrics
        val requested = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            (FontProviders.getFontSize(FONT_KEY, DEFAULT_FONT_SIZE) * scale).coerceAtLeast(0f),
            metrics,
        )
        return requested.coerceAtMost(bodyTextSizePx.coerceAtLeast(0f) * MAX_SIZE_TO_BODY_RATIO)
    }

    fun span(context: Context, bodyTextSizePx: Float, scale: Float = 1f): MetricAffectingSpan =
        AnnotationSpan(typeface(), textSizePx(context, bodyTextSizePx, scale))

    private class AnnotationSpan(private val font: Typeface, private val sizePx: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)

        override fun updateDrawState(textPaint: TextPaint) = apply(textPaint)

        private fun apply(textPaint: TextPaint) {
            textPaint.typeface = font
            textPaint.textSize = sizePx
        }
    }
}
