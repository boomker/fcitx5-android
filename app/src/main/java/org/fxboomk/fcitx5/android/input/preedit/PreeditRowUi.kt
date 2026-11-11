/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

/** Keep the original preedit rendering on the left and native syllable filters on the right. */
internal class PreeditRowUi(
    override val ctx: Context,
    theme: Theme,
    setupTextView: TextView.() -> Unit,
    private val availableWidth: () -> Int = { 0 },
) : Ui {
    private val code = PreeditUi(ctx, theme, setupTextView)
    private val codeScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(code.root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val syllables = SyllableStrip(ctx, theme)

    val visible: Boolean get() = code.visible

    override val root = object : ViewGroup(ctx) {
        init {
            addView(codeScroll)
            addView(syllables)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val incoming = MeasureSpec.getSize(widthMeasureSpec)
            val keyboardWidth = availableWidth()
            val widthLimit = when {
                keyboardWidth > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED ->
                    minOf(keyboardWidth, incoming)
                keyboardWidth > 0 -> keyboardWidth
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED -> incoming
                else -> resources.displayMetrics.widthPixels
            }
            val contentWidth = (widthLimit - paddingLeft - paddingRight).coerceAtLeast(0)
            val heightSpec = getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, LayoutParams.WRAP_CONTENT)
            val stripWidth = if (syllables.isVisible) {
                syllables.measure(MeasureSpec.makeMeasureSpec((contentWidth * MAX_SYLLABLE_SHARE).toInt(), MeasureSpec.AT_MOST), heightSpec)
                syllables.measuredWidth
            } else 0
            codeScroll.measure(MeasureSpec.makeMeasureSpec(contentWidth - stripWidth, MeasureSpec.AT_MOST), heightSpec)
            val desiredWidth = codeScroll.measuredWidth + stripWidth + paddingLeft + paddingRight
            val desiredHeight = maxOf(codeScroll.measuredHeight, if (syllables.isVisible) syllables.measuredHeight else 0) + paddingTop + paddingBottom
            // Even a floating keyboard's wrap-content code row must stay within its keyboard.
            setMeasuredDimension(desiredWidth.coerceAtMost(widthLimit), resolveSize(desiredHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val contentHeight = height - paddingTop - paddingBottom
            val codeY = paddingTop + (contentHeight - codeScroll.measuredHeight) / 2
            val stripY = paddingTop + (contentHeight - syllables.measuredHeight) / 2
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                val codeRight = width - paddingRight
                codeScroll.layout(codeRight - codeScroll.measuredWidth, codeY, codeRight, codeY + codeScroll.measuredHeight)
                if (syllables.isVisible) syllables.layout(paddingLeft, stripY, paddingLeft + syllables.measuredWidth, stripY + syllables.measuredHeight)
            } else {
                codeScroll.layout(paddingLeft, codeY, paddingLeft + codeScroll.measuredWidth, codeY + codeScroll.measuredHeight)
                if (syllables.isVisible) {
                    val start = paddingLeft + codeScroll.measuredWidth
                    syllables.layout(start, stripY, start + syllables.measuredWidth, stripY + syllables.measuredHeight)
                }
            }
        }
    }

    fun update(panel: FcitxEvent.InputPanelEvent.Data, showSyllables: Boolean) {
        code.update(panel)
        syllables.update(panel, showSyllables)
        codeScroll.isVisible = code.visible
        root.requestLayout()
    }

    companion object {
        const val MAX_SYLLABLE_SHARE = 0.6f
    }
}
