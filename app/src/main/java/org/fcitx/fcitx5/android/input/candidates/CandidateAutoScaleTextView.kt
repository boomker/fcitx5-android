/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.core.graphics.withSave
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.input.AutoScaleTextView

@SuppressLint("AppCompatCustomView")
class CandidateAutoScaleTextView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null
) : TextView(context, attributeSet) {

    companion object {
        private val attachedViews = mutableListOf<WeakReference<CandidateAutoScaleTextView>>()

        @Synchronized
        private fun registerView(view: CandidateAutoScaleTextView) {
            attachedViews.removeAll { it.get() == null || it.get() === view }
            attachedViews.add(WeakReference(view))
        }

        @Synchronized
        private fun unregisterView(view: CandidateAutoScaleTextView) {
            attachedViews.removeAll { it.get() == null || it.get() === view }
        }

        @Synchronized
        fun refreshAllFontTypeFaces() {
            val living = attachedViews.mapNotNull { it.get() }
            attachedViews.removeAll { it.get() == null }
            living.forEach { view ->
                view.setFontTypeFace(view.fontTypeFaceKey)
                view.requestLayout()
                view.invalidate()
            }
        }
    }

    var scaleMode = AutoScaleTextView.Mode.None

    private lateinit var text: String
    private var fontTypeFaceKey: String = "font"

    private var needsMeasureText = true
    private val fontMetrics = Paint.FontMetrics()
    private val textBounds = Rect()

    private var needsCalculateTransform = true
    private var translateY = 0.0f
    private var translateX = 0.0f
    private var textScaleX = 1.0f
    private var textScaleY = 1.0f

    private var minReadableScale: Float = 0.85f
    private var minReadablePx: Float = 8f

    private var isScrollMode = false
    private var lastTouchX = 0f
    private var isDragging = false
    private var touchSlop = 0
    private var maxScrollX = 0f

    init {
        setFontTypeFace("font")
        touchSlop = ViewConfiguration.get(this.context).scaledTouchSlop
    }

    fun setFontTypeFace(key: String) {
        fontTypeFaceKey = key
        setTypeface(
            AutoScaleTextView.fontTypefaceMap[key]
                ?: AutoScaleTextView.fontTypefaceMap["font"]
                ?: Typeface.DEFAULT
        )
    }

    override fun getText(): CharSequence {
        return if (::text.isInitialized) text else super.getText()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerView(this)
    }

    override fun onDetachedFromWindow() {
        unregisterView(this)
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
                paint.getTextBounds(text, 0, text.length, textBounds)
            } else {
                textBounds.set(
                    0,
                    floor(fontMetrics.top).toInt(),
                    ceil(paint.measureText(text)).toInt(),
                    ceil(fontMetrics.bottom).toInt()
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
        val leftAlignOffset = (paddingLeft - textBounds.left).toFloat()
        val centerAlignOffset =
            paddingLeft.toFloat() + (contentWidth - textWidth) / 2.0f - textBounds.left.toFloat()

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

        @SuppressLint("RtlHardcoded")
        val shouldAlignLeft = gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.LEFT
        val requiredScaleFromPx = if (rawFontHeight > 0f) minReadablePx / rawFontHeight else 0f
        val requiredScale = max(minReadableScale, requiredScaleFromPx)

        if (shouldScaleByWidth || (scaleMode == AutoScaleTextView.Mode.Proportional && shouldScaleByHeight)) {
            when (scaleMode) {
                AutoScaleTextView.Mode.None -> {
                    textScaleX = 1.0f
                    textScaleY = 1.0f
                    translateX = if (shouldAlignLeft) leftAlignOffset else centerAlignOffset
                    isScrollMode = false
                }
                AutoScaleTextView.Mode.Horizontal -> {
                    if (widthScaleLimit < requiredScale) {
                        isScrollMode = true
                        textScaleX = requiredScale
                    } else {
                        isScrollMode = false
                        textScaleX = widthScaleLimit
                    }
                    textScaleY = 1.0f
                    translateX = leftAlignOffset
                }
                AutoScaleTextView.Mode.Proportional -> {
                    val desiredScale = min(1.0f, min(widthScaleLimit, heightScaleLimit))
                    if (desiredScale < requiredScale) {
                        isScrollMode = true
                        textScaleX = requiredScale
                        textScaleY = requiredScale
                    } else {
                        isScrollMode = false
                        textScaleX = desiredScale
                        textScaleY = desiredScale
                    }
                    translateX = leftAlignOffset
                }
            }
        } else {
            textScaleX = 1.0f
            textScaleY = 1.0f
            translateX = if (shouldAlignLeft) leftAlignOffset else centerAlignOffset
            isScrollMode = false
        }

        maxScrollX = max(0f, textBounds.width() * textScaleX - contentWidth)
        if (isScrollMode && maxScrollX > 0f) {
            if (scrollX > maxScrollX.roundToInt()) scrollTo(maxScrollX.roundToInt(), scrollY)
        } else {
            if (!isScrollMode) scrollTo(0, scrollY)
        }

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

        val hasLeftOverflow = isScrollMode && maxScrollX > 0f && scrollX > 0
        val hasRightOverflow = isScrollMode && maxScrollX > 0f && scrollX < maxScrollX.roundToInt()
        val leftIndicator = "◀"
        val rightIndicator = "▶"
        val hintPaint = Paint(paint).apply {
            textScaleX = 1.0f
            alpha = 220
        }
        val leftIndicatorWidth = hintPaint.measureText(leftIndicator)
        val rightIndicatorWidth = hintPaint.measureText(rightIndicator)
        val indicatorGap = this.context.resources.displayMetrics.density * 4f
        val reserveLeft = if (hasLeftOverflow) (leftIndicatorWidth + indicatorGap).roundToInt() else 0
        val reserveRight = if (hasRightOverflow) (rightIndicatorWidth + indicatorGap).roundToInt() else 0

        canvas.withSave {
            if (reserveLeft > 0 || reserveRight > 0) {
                clipRect(
                    scrollX + paddingLeft + reserveLeft,
                    0,
                    scrollX + width - paddingRight - reserveRight,
                    height
                )
            }
            scale(textScaleX, textScaleY, 0f, translateY)
            translate(translateX, translateY)
            drawText(text, 0f, 0f, paint)
        }

        val fm = hintPaint.fontMetrics
        val contentHeight = height - paddingTop - paddingBottom
        val y = (contentHeight - (fm.bottom - fm.top)) / 2.0f - fm.top + paddingTop

        if (hasLeftOverflow) {
            val x = (scrollX + paddingLeft).toFloat()
            canvas.drawText(leftIndicator, x, y, hintPaint)
        }

        if (hasRightOverflow) {
            val x = scrollX + width - paddingRight - rightIndicatorWidth
            canvas.drawText(rightIndicator, x, y, hintPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isScrollMode || maxScrollX <= 0f) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (!isDragging && kotlin.math.abs(dx) > touchSlop) {
                    isDragging = true
                }
                if (isDragging) {
                    val newScroll = (scrollX - dx).toInt()
                    val clamped = newScroll.coerceIn(0, maxScrollX.roundToInt())
                    scrollTo(clamped, scrollY)
                    lastTouchX = event.x
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isDragging
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && !wasDragging) {
                    if (performClickOnClickableParent()) {
                        return true
                    }
                    return performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun performClickOnClickableParent(): Boolean {
        var current = parent
        while (current is View) {
            if (current.isClickable) {
                return current.performClick()
            }
            current = current.parent
        }
        return false
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun computeHorizontalScrollRange(): Int {
        val contentRange = (textBounds.width() * textScaleX + paddingLeft + paddingRight).roundToInt()
        return max(width, contentRange)
    }

    override fun computeHorizontalScrollExtent(): Int {
        return width
    }

    override fun computeHorizontalScrollOffset(): Int {
        return scrollX
    }

    override fun getTextScaleX(): Float {
        return textScaleX
    }

    override fun getBaseline(): Int {
        return (-fontMetrics.top * textScaleY).roundToInt()
    }
}
