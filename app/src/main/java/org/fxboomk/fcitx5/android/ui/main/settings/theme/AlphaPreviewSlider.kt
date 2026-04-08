/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import android.view.View

/**
 * Alpha preview slider with checkerboard background
 * Slide left/right to adjust alpha value
 * Shows the current color (RGB) with adjustable alpha over checkerboard
 */
class AlphaPreviewSlider(context: Context) : View(context) {
    enum class Orientation {
        Horizontal,
        Vertical
    }

    var onAlphaChanged: ((Int) -> Unit)? = null
    var orientation: Orientation = Orientation.Horizontal
        set(value) {
            field = value
            invalidate()
        }

    private var alpha = 255
    private var colorRgb = Color.BLACK  // RGB color (alpha ignored)
    private var checkerboardBitmap: Bitmap? = null
    private val colorPaint = Paint()
    private val indicatorPaint = Paint()

    private val dp: (Float) -> Int = { (it * resources.displayMetrics.density + 0.5f).toInt() }

    init {
        indicatorPaint.color = Color.WHITE
        indicatorPaint.style = Paint.Style.STROKE
        indicatorPaint.strokeWidth = dp(2f).toFloat()
    }

    fun setAlpha(alpha: Int) {
        this.alpha = alpha.coerceIn(0, 255)
        invalidate()
    }

    fun setColor(color: Int) {
        // Extract RGB only (ignore alpha)
        this.colorRgb = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        createCheckerboardBitmap(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        // Draw checkerboard background
        checkerboardBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // Draw current color (RGB) with current alpha over checkerboard
        colorPaint.color = Color.argb(alpha, Color.red(colorRgb), Color.green(colorRgb), Color.blue(colorRgb))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), colorPaint)

        // Draw alpha indicator line
        if (orientation == Orientation.Horizontal) {
            val indicatorX = alpha / 255f * width
            canvas.drawLine(indicatorX, 0f, indicatorX, height.toFloat(), indicatorPaint)
        } else {
            val indicatorY = (1f - alpha / 255f) * height
            canvas.drawLine(0f, indicatorY, width.toFloat(), indicatorY, indicatorPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateAlphaFromTouch(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun updateAlphaFromTouch(rawX: Float, rawY: Float) {
        val newAlpha = if (orientation == Orientation.Horizontal) {
            val w = width
            val x = rawX.coerceIn(0f, w.toFloat())
            // Left slide = lower alpha, Right slide = higher alpha
            if (w == 0) 0 else (x / w * 255).toInt().coerceIn(0, 255)
        } else {
            val h = height
            val y = rawY.coerceIn(0f, h.toFloat())
            // Top slide = higher alpha, Bottom slide = lower alpha
            if (h == 0) 0 else ((1f - y / h) * 255).toInt().coerceIn(0, 255)
        }
        if (alpha != newAlpha) {
            alpha = newAlpha
            invalidate()
            onAlphaChanged?.invoke(alpha)
        }
    }

    private fun createCheckerboardBitmap(w: Int, h: Int) {
        val tileSize = dp(8f)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        val rows = (h / tileSize) + 1
        val cols = (w / tileSize) + 1

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                paint.color = if ((row + col) % 2 == 0) 0xFFCCCCCC.toInt() else Color.WHITE
                canvas.drawRect(
                    col * tileSize.toFloat(),
                    row * tileSize.toFloat(),
                    (col + 1) * tileSize.toFloat(),
                    (row + 1) * tileSize.toFloat(),
                    paint
                )
            }
        }

        checkerboardBitmap = bitmap
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        checkerboardBitmap?.recycle()
        checkerboardBitmap = null
    }
}
