/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.font

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders a glyph from an icon font as a drawable.
 *
 * Glyph metrics vary across icon fonts (uneven ascent/descent, ink smaller than
 * the em box), which makes [android.widget.TextView]-based font icons render
 * smaller and higher than the 24dp vector/SVG icons beside them. This drawable
 * measures the glyph's painted bounds and scales them to a fixed square icon
 * box, centered, so font icons align with drawable icons sharing the same
 * intrinsic size.
 *
 * Vector drawables keep a padding inside their 24dp viewport (the "live area"),
 * so the ink is fitted to [fillRatio] of the box instead of filling it.
 */
class TextIconDrawable(
    private val glyph: String,
    typeface: Typeface,
    density: Float,
    iconSizeDp: Float = DEFAULT_ICON_SIZE_DP,
    private val fillRatio: Float = DEFAULT_FILL_RATIO
) : Drawable() {

    private val iconSize = (iconSizeDp * density + 0.5f).roundToInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        color = Color.BLACK
        textSize = REFERENCE_TEXT_SIZE
    }

    private val glyphBounds = Rect()

    private var tintList: ColorStateList? = null

    init {
        paint.getTextBounds(glyph, 0, glyph.length, glyphBounds)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || glyphBounds.isEmpty) return
        val inkWidth = glyphBounds.width().toFloat()
        val inkHeight = glyphBounds.height().toFloat()
        if (inkWidth <= 0f || inkHeight <= 0f) return
        val scale = min(
            b.width() * fillRatio / inkWidth,
            b.height() * fillRatio / inkHeight
        )
        // Draw with left-aligned metrics only: place the measured ink center on the
        // bounds center explicitly. Mixing getTextBounds with Align.CENTER drawText
        // shifts the glyph when the platform measures bounds without applying the
        // alignment (observed on ColorOS).
        canvas.save()
        canvas.translate(b.exactCenterX(), b.exactCenterY())
        canvas.scale(scale, scale)
        canvas.drawText(glyph, -glyphBounds.exactCenterX(), -glyphBounds.exactCenterY(), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun setTint(tintColor: Int) {
        tintList = null
        paint.color = tintColor
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        tintList = tint
        paint.color = tint?.getColorForState(state, tint.defaultColor) ?: Color.BLACK
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = iconSize
    override fun getIntrinsicHeight(): Int = iconSize

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val DEFAULT_ICON_SIZE_DP = 24f

        /**
         * Ink coverage of Material-style icons: 20dp live area inside a 24dp viewport.
         */
        const val DEFAULT_FILL_RATIO = 20f / 24f

        /**
         * Glyphs are measured at a large reference size so bounds rounding does not
         * distort the normalized scale.
         */
        private const val REFERENCE_TEXT_SIZE = 100f
    }
}
