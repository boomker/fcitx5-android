/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

/** Short compositions share a row; longer ones expose syllables in a separate panel above it. */
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
    private var expanded = false
    private var canExpand = false
    private var currentPanel = FcitxEvent.InputPanelEvent.Data()

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_less_24, theme).apply {
        image.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        isVisible = false
        isFocusable = true
        setOnClickListener {
            if (canExpand) {
                expanded = !expanded
                updateSyllablePresentation()
            }
        }
    }

    val expandedSyllables = FrameLayout(ctx).apply { isVisible = false }

    val visible: Boolean get() = code.visible
    val hasInteractiveContent: Boolean get() = syllables.isVisible || expandButton.isVisible

    val codeRow = object : ViewGroup(ctx) {
        init {
            addView(codeScroll)
            addView(syllables)
            addView(expandButton)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthLimit = MeasureSpec.getSize(widthMeasureSpec)
            val contentWidth = (widthLimit - paddingLeft - paddingRight).coerceAtLeast(0)
            val heightSpec = getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, LayoutParams.WRAP_CONTENT)
            val inlineSyllables = syllables.parent === this && syllables.isVisible
            val stripWidth = if (inlineSyllables) {
                syllables.measure(MeasureSpec.makeMeasureSpec((contentWidth * MAX_SYLLABLE_SHARE).toInt(), MeasureSpec.AT_MOST), heightSpec)
                syllables.measuredWidth
            } else 0
            val buttonWidth = if (expandButton.isVisible) minOf(ctx.dp(32), contentWidth) else 0
            codeScroll.measure(MeasureSpec.makeMeasureSpec(contentWidth - stripWidth - buttonWidth, MeasureSpec.AT_MOST), heightSpec)
            val contentHeight = maxOf(codeScroll.measuredHeight,
                if (inlineSyllables) syllables.measuredHeight else 0,
                if (expandButton.isVisible) ctx.dp(32) else 0)
            if (expandButton.isVisible) {
                expandButton.measure(MeasureSpec.makeMeasureSpec(buttonWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY))
            }
            val desiredWidth = codeScroll.measuredWidth + stripWidth + buttonWidth + paddingLeft + paddingRight
            val desiredHeight = contentHeight + paddingTop + paddingBottom
            setMeasuredDimension(desiredWidth.coerceAtMost(widthLimit), resolveSize(desiredHeight, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val contentHeight = height - paddingTop - paddingBottom
            val codeY = paddingTop + (contentHeight - codeScroll.measuredHeight) / 2
            val trailing = if (expandButton.isVisible) expandButton else syllables
            val showTrailing = trailing.parent === this && trailing.isVisible
            val trailingY = paddingTop + (contentHeight - trailing.measuredHeight) / 2
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                val codeRight = width - paddingRight
                codeScroll.layout(codeRight - codeScroll.measuredWidth, codeY, codeRight, codeY + codeScroll.measuredHeight)
                if (showTrailing) trailing.layout(paddingLeft, trailingY, paddingLeft + trailing.measuredWidth, trailingY + trailing.measuredHeight)
            } else {
                codeScroll.layout(paddingLeft, codeY, paddingLeft + codeScroll.measuredWidth, codeY + codeScroll.measuredHeight)
                if (showTrailing) {
                    val start = paddingLeft + codeScroll.measuredWidth
                    trailing.layout(start, trailingY, start + trailing.measuredWidth, trailingY + trailing.measuredHeight)
                }
            }
        }
    }

    override val root = object : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
            gravity = Gravity.START
            addView(expandedSyllables, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ctx.dp(4)
            })
            addView(codeRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
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
            // Constrain both surfaces to the actual (possibly floating) keyboard width.
            super.onMeasure(MeasureSpec.makeMeasureSpec(widthLimit, MeasureSpec.AT_MOST), heightMeasureSpec)
        }
    }

    fun update(panel: FcitxEvent.InputPanelEvent.Data, showSyllables: Boolean) {
        currentPanel = panel
        code.update(panel)
        val hasSyllables = panel.preedit.isNotEmpty() &&
            preeditSyllableActions(panel.tabs, showSyllables).isNotEmpty()
        canExpand = hasSyllables && preeditSyllableCount(panel.preedit.toString()) >= 3
        if (!canExpand) expanded = false
        updateSyllablePresentation(hasSyllables)
        codeScroll.isVisible = code.visible
        root.requestLayout()
    }

    private fun updateSyllablePresentation(hasSyllables: Boolean = true) {
        val parent = if (expanded) expandedSyllables else codeRow
        if (syllables.parent !== parent) {
            (syllables.parent as? ViewGroup)?.removeView(syllables)
            parent.addView(syllables, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Updating the strip also invalidates any pending clicks when it is hidden.
        syllables.update(currentPanel, hasSyllables && (!canExpand || expanded))
        expandedSyllables.isVisible = expanded
        expandButton.isVisible = canExpand
        expandButton.setIcon(if (expanded) R.drawable.ic_baseline_expand_more_24
            else R.drawable.ic_baseline_expand_less_24)
        expandButton.contentDescription = ctx.getString(if (expanded) R.string.hide_syllables
            else R.string.expand_syllables)
        root.requestLayout()
    }

    companion object {
        const val MAX_SYLLABLE_SHARE = 0.6f
    }
}
