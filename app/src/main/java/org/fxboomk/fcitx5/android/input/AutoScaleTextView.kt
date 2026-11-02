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
import android.graphics.RectF
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

    /** Measure and align visible glyphs, without font leading, for labels at key edges. */
    internal var useGlyphBounds = false
        set(value) {
            if (field == value) return
            field = value
            needsMeasureText = true
            needsCalculateTransform = true
            requestLayout()
            invalidate()
        }

    /** Center the x-height of a single Latin lowercase label in its drawing box. */
    internal var centerLowercaseXHeight = false
        set(value) {
            if (field == value) return
            field = value
            needsCalculateTransform = true
            invalidate()
        }

    /** Optional placement supplied after a keyboard row has measured all its labels. */
    internal data class GlyphPlacement(
        val baseline: Float,
        val scale: Float,
        val horizontalScale: Float? = null,
    )

    internal var glyphPlacement: GlyphPlacement? = null
        set(value) {
            if (field == value) return
            field = value
            needsCalculateTransform = true
            invalidate()
        }

    private lateinit var text: String

    private var needsMeasureText = true
    private val fontMetrics = Paint.FontMetrics()
    private val textBounds = Rect()
    private val lowercaseReferenceBounds = Rect()

    private var needsCalculateTransform = true
    private var baselineX = 0.0f
    private var baselineY = 0.0f
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
        val resolvedTypeface = FontProviders.resolveTypeface(key, typeface)
        if (key == "key_alt_font") {
            // All secondary labels stay non-bold, including after custom font reloads.
            val style = if (resolvedTypeface.isItalic) Typeface.ITALIC else Typeface.NORMAL
            setTypeface(Typeface.create(resolvedTypeface, style), style)
        } else {
            setTypeface(resolvedTypeface)
        }
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

    override fun setTypeface(tf: Typeface?) {
        super.setTypeface(tf)
        needsMeasureText = true
        needsCalculateTransform = true
    }

    internal fun renderedGlyphBounds(): RectF {
        calculateTransform(width, height)
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        return RectF(
            baselineX + ink.left * textScaleX + scrollX,
            baselineY + ink.top * textScaleY + scrollY,
            baselineX + ink.right * textScaleX + scrollX,
            baselineY + ink.bottom * textScaleY + scrollY
        )
    }

    /** Vertical centre of the x-height band in this view's coordinate space. */
    internal fun renderedXHeightCenter(): Float {
        calculateTransform(width, height)
        paint.getTextBounds("x", 0, 1, lowercaseReferenceBounds)
        return baselineY +
            (lowercaseReferenceBounds.top + lowercaseReferenceBounds.bottom) * textScaleY / 2f
    }

    /** Keep the current glyph inside a vertical safe area without changing its scale. */
    internal fun constrainGlyphsVertically(top: Int, bottom: Int) {
        if (width <= 0 || height <= 0) return
        calculateTransform(width, height)
        val ink = renderedGlyphBounds()
        val delta = max(top - ink.top, ink.bottom - bottom)
        if (delta <= 0f) return
        glyphPlacement = GlyphPlacement(
            baselineY + delta,
            textScaleY,
            glyphPlacement?.horizontalScale,
        )
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
        val textHeight = if (useGlyphBounds) textBounds.height().toFloat() else fontMetrics.bottom - fontMetrics.top
        val height = ceil(textHeight + paddingTop + paddingBottom).toInt()
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
            if (codePointCount == 1 || useGlyphBounds) {
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
        val contentWidth: Int = viewWidth - paddingLeft - paddingRight
        val contentHeight: Int = viewHeight - paddingTop - paddingBottom
        measureTextBounds()
        val textLeft: Float = textBounds.left.toFloat()
        val textWidth: Float = textBounds.width().toFloat()
        val textTop = if (useGlyphBounds) textBounds.top.toFloat() else fontMetrics.top
        val textHeight = if (useGlyphBounds) textBounds.height().toFloat() else fontMetrics.bottom - fontMetrics.top
        val centeredTextBounds = if (centerLowercaseXHeight &&
            text.length == 1 && text[0] in 'a'..'z'
        ) {
            lowercaseReferenceBounds.also { paint.getTextBounds("x", 0, 1, it) }
        } else {
            null
        }

        val widthScaleLimit = if (textWidth > 0f && contentWidth > 0) {
            contentWidth.toFloat() / textWidth
        } else {
            1.0f
        }
        val heightScaleLimit = if (textHeight > 0f && contentHeight > 0) {
            contentHeight.toFloat() / textHeight
        } else {
            1.0f
        }
        val shouldScaleByWidth = textWidth > contentWidth
        val shouldScaleByHeight = textHeight > contentHeight

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
        glyphPlacement?.let {
            textScaleX = it.horizontalScale ?: it.scale
            textScaleY = it.scale
        }
        baselineX = calculateBaselineX(paddingLeft, contentWidth, textLeft, textWidth, textScaleX)
        // Keep shared row labels on the same rasterization grid. Fractional
        // baselines make otherwise identical strokes look heavier on some rows.
        baselineY = glyphPlacement?.baseline?.roundToInt()?.toFloat()
            ?: calculateBaselineY(
                paddingTop,
                contentHeight,
                centeredTextBounds?.top?.toFloat() ?: textTop,
                centeredTextBounds?.height()?.toFloat() ?: textHeight,
                textScaleY
            )
    }

    private fun calculateBaselineX(
        contentLeft: Int,
        contentWidth: Int,
        textLeft: Float,
        textWidth: Float,
        scaleX: Float
    ): Float {
        val scaledTextLeft = textLeft * scaleX
        val scaledTextWidth = textWidth * scaleX
        val horizontalGravity =
            Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        val targetLeft: Float = @SuppressLint("RtlHardcoded") when (horizontalGravity) {
            Gravity.LEFT -> contentLeft.toFloat()
            Gravity.RIGHT -> contentLeft + contentWidth - scaledTextWidth
            else -> contentLeft + (contentWidth - scaledTextWidth) / 2.0f
        }
        return targetLeft - scaledTextLeft
    }

    private fun calculateBaselineY(
        contentTop: Int,
        contentHeight: Int,
        textTop: Float,
        textHeight: Float,
        scaleY: Float
    ): Float {
        val scaledTextTop = textTop * scaleY
        val scaledTextHeight = textHeight * scaleY
        val verticalGravity = gravity and Gravity.VERTICAL_GRAVITY_MASK
        val targetTop: Float = when (verticalGravity) {
            Gravity.TOP -> contentTop.toFloat()
            Gravity.BOTTOM -> contentTop + contentHeight - scaledTextHeight
            else -> contentTop + (contentHeight - scaledTextHeight) / 2.0f
        }
        return targetTop - scaledTextTop
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
            translate(baselineX, baselineY)
            scale(textScaleX, textScaleY)
            drawText(text, 0f, 0f, paint)
        }
    }

    override fun getTextScaleX(): Float {
        return textScaleX
    }

    override fun getBaseline(): Int {
        return glyphPlacement?.baseline?.roundToInt() ?: paddingTop + baselineY.roundToInt()
    }
}
