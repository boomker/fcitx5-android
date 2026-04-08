/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.core.graphics.withSave
import org.fxboomk.fcitx5.android.input.font.FontProviders
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Typeface

@SuppressLint("AppCompatCustomView")
class AutoScaleTextView @JvmOverloads constructor(
    context: Context?,
    attributeSet: AttributeSet? = null
) : TextView(context, attributeSet) {

    enum class Mode {
        /**
         * do not scale or ellipse text, overflow when cannot fit width
         */
        None,
        /**
         * only scale in X axis, makes text looks "condensed" or "slim"
         */
        Horizontal,
        /**
         * scale both in X and Y axis, align center vertically
         */
        Proportional
    }

    var scaleMode = Mode.None

    private lateinit var text: String

    private var needsMeasureText = true
    private val fontMetrics = Paint.FontMetrics()
    private val textBounds = Rect()

    private var needsCalculateTransform = true
    private var translateY = 0.0f
    private var translateX = 0.0f
    private var textScaleX = 1.0f
    private var textScaleY = 1.0f

    companion object {
        fun clearFontCache() {
            FontProviders.clearCache()
        }

        val fontTypefaceMap: MutableMap<String, Typeface?>
            get() = FontProviders.fontTypefaceMap
    }

    fun setFontTypeFace(key: String) {
        fontTypeFaceKey = key
        setTypeface(FontProviders.resolveTypeface(key, typeface))
    }

    /**
     * Internal property for BaseKeyboard to set font key without immediately applying font.
     * Font will be applied in batch by BaseKeyboard.reloadLayout().
     */
    internal var fontKey: String
        get() = fontTypeFaceKey
        set(value) { fontTypeFaceKey = value }

    private var fontTypeFaceKey: String = "font"

    init {
        // Defer font setup to parent view (BaseKeyboard.reloadLayout()) for better performance
        // Font will be set in batch when keyboard layout is loaded/reloaded
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        needsMeasureText = true
        needsCalculateTransform = true
    }

    override fun setText(charSequence: CharSequence?, bufferType: BufferType) {
        // setText can be called in super constructor
        if (!::text.isInitialized || charSequence == null || !text.contentEquals(charSequence)) {
            needsMeasureText = true
            needsCalculateTransform = true
            text = charSequence?.toString() ?: ""
            requestLayout()
            invalidate()
        }
    }

    override fun getText(): CharSequence {
        return text
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val width = measureTextBounds().width() + paddingLeft + paddingRight
        val height = ceil(fontMetrics.bottom - fontMetrics.top + paddingTop + paddingBottom).toInt()
        val maxHeight = if (maxHeight >= 0) maxHeight else Int.MAX_VALUE
        val maxWidth = if (maxWidth >= 0) maxWidth else Int.MAX_VALUE
        setMeasuredDimension(
            measure(widthMode, widthSize, min(max(width, minimumWidth), maxWidth)),
            measure(heightMode, heightSize, min(max(height, minimumHeight), maxHeight))
        )
    }

    private fun measure(specMode: Int, specSize: Int, calculatedSize: Int): Int = when (specMode) {
        MeasureSpec.EXACTLY -> specSize
        MeasureSpec.AT_MOST -> min(calculatedSize, specSize)
        else -> calculatedSize
    }

    private fun measureTextBounds(): Rect {
        if (needsMeasureText) {
            val paint = paint
            paint.getFontMetrics(fontMetrics)
            val codePointCount = Character.codePointCount(text, 0, text.length)
            if (codePointCount == 1) {
                // use actual text bounds when there is only one "character",
                // eg. full-width punctuation
                paint.getTextBounds(text, 0, text.length, textBounds)
            } else {
                textBounds.set(
                    /* left = */ 0,
                    /* top = */ floor(fontMetrics.top).toInt(),
                    /* right = */ ceil(paint.measureText(text)).toInt(),
                    /* bottom = */ ceil(fontMetrics.bottom).toInt()
                )
            }
            needsMeasureText = false
        }
        return textBounds
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (needsCalculateTransform || changed) {
            calculateTransform(right - left, bottom - top)
            needsCalculateTransform = false
        }
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) {
        val contentWidth = viewWidth - paddingLeft - paddingRight
        val contentHeight = viewHeight - paddingTop - paddingBottom
        measureTextBounds()
        val textWidth = textBounds.width()
        val rawFontHeight = fontMetrics.bottom - fontMetrics.top

        val widthScaleLimit = if (textWidth > 0 && contentWidth > 0) {
            contentWidth.toFloat() / textWidth.toFloat()
        } else {
            1.0f
        }
        val heightScaleLimit = if (rawFontHeight > 0f && contentHeight > 0) {
            contentHeight.toFloat() / rawFontHeight
        } else {
            1.0f
        }
        val shouldScaleByWidth = textWidth > contentWidth
        val shouldScaleByHeight = rawFontHeight > contentHeight

        if (shouldScaleByWidth || (scaleMode == Mode.Proportional && shouldScaleByHeight)) {
            when (scaleMode) {
                Mode.None -> {
                    textScaleX = 1.0f
                    textScaleY = 1.0f
                }
                Mode.Horizontal -> {
                    textScaleX = widthScaleLimit
                    textScaleY = 1.0f
                }
                Mode.Proportional -> {
                    val textScale = min(1.0f, min(widthScaleLimit, heightScaleLimit))
                    textScaleX = textScale
                    textScaleY = textScale
                }
            }
        } else {
            textScaleX = 1.0f
            textScaleY = 1.0f
        }
        val scaledTextWidth = textWidth.toFloat() * textScaleX
        val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
        val desiredLeft = when (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT ->
                paddingLeft.toFloat() + contentWidth.toFloat() - scaledTextWidth
            Gravity.CENTER_HORIZONTAL ->
                paddingLeft.toFloat() + (contentWidth.toFloat() - scaledTextWidth) / 2.0f
            else -> paddingLeft.toFloat()
        }
        val safeScaleX = if (textScaleX == 0.0f) 1.0f else textScaleX
        translateX = desiredLeft / safeScaleX - textBounds.left.toFloat()
        val fontHeight = (fontMetrics.bottom - fontMetrics.top) * textScaleY
        val fontOffsetY = fontMetrics.top * textScaleY
        translateY = (contentHeight.toFloat() - fontHeight) / 2.0f - fontOffsetY + paddingTop
    }

    override fun onDraw(canvas: Canvas) {
        if (needsCalculateTransform) {
            calculateTransform(width, height)
            needsCalculateTransform = false
        }
        val paint = paint
        paint.color = currentTextColor
        canvas.withSave {
            translate(scrollX.toFloat(), scrollY.toFloat())
            scale(textScaleX, textScaleY, 0f, translateY)
            translate(translateX, translateY)
            drawText(text, 0f, 0f, paint)
        }
    }

    override fun getTextScaleX(): Float {
        return textScaleX
    }

    override fun getBaseline(): Int {
        return (-fontMetrics.top * textScaleY).roundToInt()
    }
}
