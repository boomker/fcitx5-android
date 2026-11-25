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
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
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

    private data class ForegroundSpan(val start: Int, val end: Int, val color: Int, val flags: Int)
    private data class MetricSpan(val start: Int, val end: Int, val span: MetricAffectingSpan, val flags: Int)

    private lateinit var foregroundSpans: List<ForegroundSpan>
    private lateinit var metricSpans: List<MetricSpan>
    private var styledLayout: StaticLayout? = null
    private var styledInkBounds: RectF? = null

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
        val previousSize = textSize
        super.setTextSize(unit, size)
        needsMeasureText = true
        needsCalculateTransform = true
        styledLayout = null
        styledInkBounds = null
        if (textSize != previousSize) {
            requestLayout()
            invalidate()
        }
    }

    override fun setTypeface(tf: Typeface?) {
        val previousTypeface = typeface
        super.setTypeface(tf)
        needsMeasureText = true
        needsCalculateTransform = true
        styledLayout = null
        styledInkBounds = null
        if (typeface !== previousTypeface) {
            requestLayout()
            invalidate()
        }
    }

    internal fun renderedGlyphBounds(): RectF {
        calculateTransform(width, height)
        val ink = Rect()
        if (metricSpans.isEmpty()) {
            paint.getTextBounds(text, 0, text.length, ink)
        } else {
            ink.set(textBounds)
        }
        return RectF(
            baselineX + ink.left * textScaleX + scrollX,
            baselineY + ink.top * textScaleY + scrollY,
            baselineX + ink.right * textScaleX + scrollX,
            baselineY + ink.bottom * textScaleY + scrollY
        )
    }

    /** Painted ink only, including styled runs, without changing the view's layout metrics. */
    internal fun renderedInkBounds(): RectF {
        if (metricSpans.isEmpty()) return renderedGlyphBounds()
        calculateTransform(width, height)
        val ink = styledInkBounds ?: run {
            val layout = foregroundLayout(paint)
            val boundaries = (listOf(0, text.length) + metricSpans.flatMap { listOf(it.start, it.end) })
                .distinct().sorted()
            val runPaint = TextPaint(paint)
            val runBounds = Rect()
            val result = RectF()
            boundaries.zipWithNext().forEach { (start, end) ->
                runPaint.set(paint)
                runPaint.baselineShift = 0
                metricSpans.filter { it.start < end && it.end > start }.forEach {
                    it.span.updateMeasureState(runPaint)
                }
                runPaint.getTextBounds(text, start, end, runBounds)
                if (!runBounds.isEmpty) {
                    val x = min(layout.getPrimaryHorizontal(start), layout.getPrimaryHorizontal(end)) -
                        layout.getLineLeft(0)
                    result.union(RectF(runBounds).apply { offset(x, runPaint.baselineShift.toFloat()) })
                }
            }
            result.also { styledInkBounds = it }
        }
        return RectF(
            baselineX + ink.left * textScaleX + scrollX,
            baselineY + ink.top * textScaleY + scrollY,
            baselineX + ink.right * textScaleX + scrollX,
            baselineY + ink.bottom * textScaleY + scrollY,
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
        val textChanged = !::text.isInitialized || charSequence == null || !text.contentEquals(charSequence)
        val colors = (charSequence as? Spanned)?.let { source ->
            source.getSpans(0, source.length, ForegroundColorSpan::class.java).mapNotNull { span ->
                val start = source.getSpanStart(span).coerceAtLeast(0)
                val end = source.getSpanEnd(span).coerceAtMost(source.length)
                if (start < end) {
                    ForegroundSpan(start, end, span.foregroundColor, source.getSpanFlags(span))
                } else {
                    null
                }
            }
        }.orEmpty()
        val metrics = (charSequence as? Spanned)?.let { source ->
            source.getSpans(0, source.length, MetricAffectingSpan::class.java).mapNotNull { span ->
                val start = source.getSpanStart(span).coerceAtLeast(0)
                val end = source.getSpanEnd(span).coerceAtMost(source.length)
                if (start < end) MetricSpan(start, end, span, source.getSpanFlags(span)) else null
            }
        }.orEmpty()
        val colorsChanged = !::foregroundSpans.isInitialized || foregroundSpans != colors
        // Metric spans can be mutable. Re-setting them must refresh measurements even
        // when the characters and span instances are unchanged.
        val metricsChanged = !::metricSpans.isInitialized || metrics.isNotEmpty() || metricSpans != metrics
        if (textChanged || metricsChanged) {
            styledInkBounds = null
            needsMeasureText = true
            needsCalculateTransform = true
            text = charSequence?.toString() ?: ""
            requestLayout()
        }
        if (textChanged || colorsChanged || metricsChanged) {
            foregroundSpans = colors
            metricSpans = metrics
            styledLayout = null
            invalidate()
        }
    }

    private fun foregroundLayout(paint: Paint): StaticLayout = styledLayout ?: run {
        val formatted = SpannableString(text).apply {
            foregroundSpans.forEach { span ->
                setSpan(ForegroundColorSpan(span.color), span.start, span.end, span.flags)
            }
            metricSpans.forEach { span ->
                setSpan(span.span, span.start, span.end, span.flags)
            }
        }
        val width = if (metricSpans.isEmpty()) paint.measureText(text) else {
            Layout.getDesiredWidth(formatted, TextPaint(paint))
        }
        StaticLayout.Builder.obtain(
            formatted, 0, formatted.length, TextPaint(paint),
            ceil(width).toInt().coerceAtLeast(1)
        ).setIncludePad(false).setMaxLines(1).build().also { styledLayout = it }
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
            if (metricSpans.isNotEmpty()) {
                measureStyledText(paint)
            } else if (codePointCount == 1 || useGlyphBounds) {
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

    private fun measureStyledText(paint: Paint) {
        val layout = foregroundLayout(paint)
        val boundaries = (listOf(0, text.length) + metricSpans.flatMap { listOf(it.start, it.end) })
            .distinct().sorted()
        val runPaint = TextPaint(paint)
        val runMetrics = Paint.FontMetrics()
        val runBounds = Rect()
        val glyphBounds = RectF()
        boundaries.zipWithNext().forEachIndexed { index, (start, end) ->
            runPaint.set(paint)
            runPaint.baselineShift = 0
            metricSpans.filter { it.start < end && it.end > start }.forEach {
                it.span.updateMeasureState(runPaint)
            }
            runPaint.getFontMetrics(runMetrics)
            val shift = runPaint.baselineShift.toFloat()
            if (index == 0) {
                fontMetrics.top = runMetrics.top + shift
                fontMetrics.bottom = runMetrics.bottom + shift
                fontMetrics.ascent = runMetrics.ascent + shift
                fontMetrics.descent = runMetrics.descent + shift
            } else {
                fontMetrics.top = min(fontMetrics.top, runMetrics.top + shift)
                fontMetrics.bottom = max(fontMetrics.bottom, runMetrics.bottom + shift)
                fontMetrics.ascent = min(fontMetrics.ascent, runMetrics.ascent + shift)
                fontMetrics.descent = max(fontMetrics.descent, runMetrics.descent + shift)
            }
            if (useGlyphBounds) {
                runPaint.getTextBounds(text, start, end, runBounds)
                val x = min(layout.getPrimaryHorizontal(start), layout.getPrimaryHorizontal(end)) -
                    layout.getLineLeft(0)
                glyphBounds.union(RectF(runBounds).apply { offset(x, shift) })
            }
        }
        if (useGlyphBounds) {
            textBounds.set(floor(glyphBounds.left).toInt(), floor(glyphBounds.top).toInt(),
                ceil(glyphBounds.right).toInt(), ceil(glyphBounds.bottom).toInt())
        } else {
            textBounds.set(0, floor(fontMetrics.top).toInt(), ceil(layout.getLineWidth(0)).toInt(),
                ceil(fontMetrics.bottom).toInt())
        }
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
        val wholeForeground = foregroundSpans.singleOrNull()?.takeIf {
            it.start == 0 && it.end == text.length
        }
        wholeForeground?.let { paint.color = it.color }
        canvas.withSave {
            translate(scrollX.toFloat(), scrollY.toFloat())
            translate(baselineX, baselineY)
            scale(textScaleX, textScaleY)
            if (metricSpans.isEmpty() && (foregroundSpans.isEmpty() || wholeForeground != null)) {
                drawText(text, 0f, 0f, paint)
            } else {
                val layout = foregroundLayout(paint)
                layout.paint.set(paint)
                // Match the custom view's baseline and physical-left origin, including RTL text.
                translate(-layout.getLineLeft(0), -layout.getLineBaseline(0).toFloat())
                layout.draw(this)
            }
        }
    }

    override fun getTextScaleX(): Float {
        return textScaleX
    }

    override fun getBaseline(): Int {
        return glyphPlacement?.baseline?.roundToInt() ?: paddingTop + baselineY.roundToInt()
    }

    /** Baseline for a parent aligning candidate cells during measurement, before layout. */
    internal fun measuredBaseline(): Int {
        if (::text.isInitialized && measuredHeight > 0) {
            calculateTransform(measuredWidth, measuredHeight)
        }
        return baselineY.roundToInt()
    }
}
