/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.ranges.coerceIn

/**
 * Saturation-Value picker view (square)
 * X-axis: Saturation (0-1, left to right)
 * Y-axis: Value/Brightness (1-0, top to bottom)
 * Background shows the current hue with full saturation/brightness gradient
 */
class SaturationValuePickerView(context: Context) : View(context) {

    var onColorChanged: ((Float, Float) -> Unit)? = null

    private val huePaint = Paint()
    private val saturationPaint = Paint()
    private val valuePaint = Paint()
    private val markerPaint = Paint()

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f

    private val rect = RectF()
    private var markerX = 0f
    private var markerY = 0f

    private val dp: (Float) -> Int = { (it * resources.displayMetrics.density + 0.5f).toInt() }

    init {
        markerPaint.color = Color.WHITE
        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(3f).toFloat()
    }

    fun setHue(hue: Float) {
        this.hue = hue
        invalidate()
    }

    fun setSaturation(saturation: Float) {
        this.saturation = saturation.coerceIn(0f, 1f)
        updateMarkerPosition()
        invalidate()
    }

    fun setValue(value: Float) {
        this.value = value.coerceIn(0f, 1f)
        updateMarkerPosition()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        updateMarkerPosition()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw hue background (current hue with full saturation and value)
        huePaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Color.HSVToColor(floatArrayOf(hue, 0f, 1f)),  // Left: white (saturation 0)
                Color.HSVToColor(floatArrayOf(hue, 1f, 1f))   // Right: full color
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, huePaint)

        // Draw value gradient (transparent to black, top to bottom)
        valuePaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, valuePaint)

        // Draw marker
        canvas.drawCircle(markerX, markerY, dp(10f).toFloat(), markerPaint)
        
        val innerMarkerPaint = Paint().apply {
            color = Color.HSVToColor(floatArrayOf(hue, saturation, value))
            style = Paint.Style.FILL
        }
        canvas.drawCircle(markerX, markerY, dp(8f).toFloat(), innerMarkerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val x = event.x.coerceIn(0f, width.toFloat())
                val y = event.y.coerceIn(0f, height.toFloat())

                saturation = x / width
                value = 1f - (y / height)

                markerX = x
                markerY = y

                invalidate()
                onColorChanged?.invoke(saturation, value)
            }
        }
        return true
    }

    private fun updateMarkerPosition() {
        markerX = saturation * width
        markerY = (1f - value) * height
    }
}
