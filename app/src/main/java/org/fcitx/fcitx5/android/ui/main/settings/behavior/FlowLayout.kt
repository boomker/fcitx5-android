/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

open class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (widthMode == MeasureSpec.EXACTLY) widthSize else Int.MAX_VALUE

        val paddingLeft = paddingLeft
        val paddingRight = paddingRight
        val paddingTop = paddingTop
        val paddingBottom = paddingBottom

        val availableWidth = width - paddingLeft - paddingRight

        var height = 0
        var currentWidth = 0
        var lineHeight = 0

        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin

            if (currentWidth + childWidth > availableWidth) {
                height += lineHeight
                currentWidth = 0
                lineHeight = 0
            }

            currentWidth += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
        height += lineHeight

        setMeasuredDimension(
            if (widthMode == MeasureSpec.EXACTLY) widthSize else minOf(width, currentWidth + paddingLeft + paddingRight),
            height + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val paddingLeft = paddingLeft
        val paddingRight = paddingRight
        val paddingTop = paddingTop

        val availableWidth = width - paddingLeft - paddingRight

        var x = 0
        var y = 0
        var lineHeight = 0

        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            if (x + childWidth + lp.leftMargin + lp.rightMargin > availableWidth) {
                x = 0
                y += lineHeight
                lineHeight = 0
            }

            val left = x + lp.leftMargin + paddingLeft
            val top = y + lp.topMargin + paddingTop
            val right = left + childWidth
            val bottom = top + childHeight

            child.layout(left, top, right, bottom)

            x += childWidth + lp.leftMargin + lp.rightMargin
            lineHeight = maxOf(lineHeight, childHeight + lp.topMargin + lp.bottomMargin)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateLayoutParams(p: LayoutParams): LayoutParams {
        return MarginLayoutParams(p)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )
    }

    override fun checkLayoutParams(p: LayoutParams): Boolean {
        return p is MarginLayoutParams
    }
}
