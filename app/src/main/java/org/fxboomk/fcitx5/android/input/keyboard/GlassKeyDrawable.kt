/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable

/** Adds a light-catching edge without changing the key's content or press foreground. */
internal class GlassKeyDrawable(
    background: Drawable,
    horizontalInset: Int,
    verticalInset: Int,
    radius: Float,
    density: Float,
    dark: Boolean,
    oval: Boolean = false
) : LayerDrawable(
    arrayOf(
        background,
        GlassKeyEdgeDrawable(horizontalInset, verticalInset, radius, density, dark, oval)
    )
) {
    init {
        // InsetDrawable's content padding must not inset the edge a second time.
        paddingMode = PADDING_MODE_STACK
    }
}

private class GlassKeyEdgeDrawable(
    private val horizontalInset: Int,
    private val verticalInset: Int,
    private val radius: Float,
    density: Float,
    private val dark: Boolean,
    private val oval: Boolean
) : Drawable() {
    private val normalStroke = density * 0.75f
    private val pressedStroke = normalStroke * 1.6f
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = normalStroke
    }
    private var pressed = false

    override fun onBoundsChange(bounds: Rect) {
        val inset = pressedStroke / 2f
        frame.set(
            bounds.left + horizontalInset + inset,
            bounds.top + verticalInset + inset,
            bounds.right - horizontalInset - inset,
            bounds.bottom - verticalInset - inset
        )
        paint.shader = if (frame.isEmpty) null else LinearGradient(
            frame.left, frame.top, frame.right, frame.bottom,
            intArrayOf(
                Color.argb(if (dark) 140 else 180, 255, 255, 255),
                Color.TRANSPARENT,
                Color.argb(if (dark) 55 else 80, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (frame.isEmpty) return
        if (oval) {
            canvas.drawOval(frame, paint)
        } else {
            val edgeRadius = (radius - pressedStroke / 2f).coerceAtLeast(0f)
            canvas.drawRoundRect(frame, edgeRadius, edgeRadius, paint)
        }
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val nextPressed = android.R.attr.state_pressed in state
        if (pressed == nextPressed) return false
        pressed = nextPressed
        paint.strokeWidth = if (pressed) pressedStroke else normalStroke
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
