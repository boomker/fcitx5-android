/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.constraintlayout.widget.ConstraintLayout

/** Clips the keyboard surface, including its children, without clipping the surrounding popups. */
class KeyboardSurfaceLayout(context: Context) : ConstraintLayout(context) {
    private val shape = Path()
    private val bounds = RectF()
    private var topRadius = 0f
    private var bottomRadius = 0f
    private val radii = FloatArray(8)

    init {
        // The surface background is an ImageView child. Without this, ViewGroup's
        // SKIP_DRAW fast path bypasses draw() and its clip on a hardware canvas.
        setWillNotDraw(false)
    }

    fun setCornerRadii(top: Float, bottom: Float = 0f) {
        val safeTop = top.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val safeBottom = bottom.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        if (safeTop == topRadius && safeBottom == bottomRadius) return
        topRadius = safeTop
        bottomRadius = safeBottom
        updateShape()
        invalidate()
    }

    fun cornerRadii(): FloatArray = radii.copyOf()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateShape()
    }

    private fun updateShape() {
        val limit = minOf(width, height).coerceAtLeast(0) / 2f
        val top = topRadius.coerceAtMost(limit)
        val bottom = bottomRadius.coerceAtMost(limit)
        for (i in radii.indices) radii[i] = if (i < 4) top else bottom
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        shape.rewind()
        shape.addRoundRect(bounds, radii, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        if (topRadius == 0f && bottomRadius == 0f) {
            super.draw(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipPath(shape)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}
