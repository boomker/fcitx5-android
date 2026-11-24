/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.floor

/** One candidate column: an optional smaller pinyin line above its normal text. */
@SuppressLint("ViewConstructor") // Existing renderer-owned text views are supplied; this is never inflated from XML.
internal class CandidateAnnotationLayout(
    context: Context,
    private val mainText: TextView,
    private val pinyinText: TextView,
) : FrameLayout(context) {
    private var configuredTextSize = mainText.textSize
    private val pinyinGap = context.resources.displayMetrics.density

    init {
        addView(mainText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(pinyinText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun setTextSizePx(size: Float) {
        configuredTextSize = size
        applyTextSizes()
        requestLayout()
    }

    private fun applyTextSizes() {
        val mainSize = configuredTextSize
        val pinyinSize = mainSize * PINYIN_SCALE
        if (mainText.textSize != mainSize) {
            mainText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainSize)
            mainText.requestLayout()
        }
        if (pinyinText.textSize != pinyinSize) {
            pinyinText.setTextSize(TypedValue.COMPLEX_UNIT_PX, pinyinSize)
            pinyinText.requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        applyTextSizes()
        if (pinyinText.visibility == View.GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        mainText.measure(unspecified, unspecified)
        pinyinText.measure(unspecified, unspecified)
        val mainNaturalHeight = mainText.measuredHeight
        val pinyinNaturalHeight = pinyinText.measuredHeight
        val desiredWidth = max(mainText.measuredWidth, pinyinText.measuredWidth) + paddingLeft + paddingRight
        val width = resolveSize(max(desiredWidth, minimumWidth), widthMeasureSpec)
        // Keep the main line centered exactly like its peers. Symmetric space
        // around it lets the pinyin sit above, without lowering or shrinking it.
        val desiredHeight = mainNaturalHeight + 2 * ceil(pinyinNaturalHeight + pinyinGap).toInt() +
            paddingTop + paddingBottom
        val height = resolveSize(max(desiredHeight, minimumHeight), heightMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(
            (width - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.AT_MOST,
        )
        mainText.measure(childWidth, MeasureSpec.makeMeasureSpec(
            (height - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY,
        ))
        val roomAbove = floor(mainBaseline() + mainText.paint.fontMetrics.top - pinyinGap)
            .toInt().coerceAtLeast(0)
        pinyinText.measure(childWidth, MeasureSpec.makeMeasureSpec(
            minOf(pinyinNaturalHeight, roomAbove), MeasureSpec.EXACTLY,
        ))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (pinyinText.visibility == View.GONE) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val contentWidth = width - paddingLeft - paddingRight
        val mainX = paddingLeft + (contentWidth - mainText.measuredWidth) / 2
        val mainY = paddingTop
        mainText.layout(mainX, mainY, mainX + mainText.measuredWidth, mainY + mainText.measuredHeight)
        val pinyinBottom = floor(mainY + mainBaseline() + mainText.paint.fontMetrics.top - pinyinGap).toInt()
        val pinyinY = (pinyinBottom - pinyinText.measuredHeight).coerceAtLeast(paddingTop)
        val pinyinX = paddingLeft + (contentWidth - pinyinText.measuredWidth) / 2
        pinyinText.layout(pinyinX, pinyinY, pinyinX + pinyinText.measuredWidth, pinyinY + pinyinText.measuredHeight)
    }

    private fun mainBaseline(): Int =
        (mainText as? AutoScaleTextView)?.measuredBaseline() ?: mainText.baseline

    override fun getBaseline(): Int {
        val mainTop = paddingTop + if (pinyinText.visibility == View.GONE) {
            (measuredHeight - paddingTop - paddingBottom - mainText.measuredHeight) / 2
        } else 0
        return mainTop + mainBaseline()
    }

    companion object {
        const val PINYIN_SCALE = 0.55f

        fun highlightTextColor(background: Int, surface: Int): Int {
            val opaque = ColorUtils.compositeColors(background, ColorUtils.setAlphaComponent(surface, 255))
            return if (ColorUtils.calculateContrast(Color.BLACK, opaque) >=
                ColorUtils.calculateContrast(Color.WHITE, opaque)) Color.BLACK else Color.WHITE
        }
    }
}
