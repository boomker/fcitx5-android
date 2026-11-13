/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.content.Context
import android.graphics.Rect
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

/** Share a row only when the complete code and all syllable choices fit together. */
internal class PreeditRowUi(
    override val ctx: Context,
    theme: Theme,
    setupTextView: TextView.() -> Unit,
    private val availableWidth: () -> Int = { 0 },
) : Ui {
    private val code = PreeditUi(ctx, theme, setupTextView = {
        setupTextView(this)
        setSingleLine(true)
    })
    private var followCursor = true
    private val cursorBounds = Rect()
    private val codeScroll = object : HorizontalScrollView(ctx) {
        private var lastViewportWidth = 0

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(code.root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val widthChanged = lastViewportWidth != right - left
            lastViewportWidth = right - left
            super.onLayout(changed, left, top, right, bottom)
            if (!code.visible) {
                scrollTo(0, 0)
                followCursor = false
            } else if ((followCursor || widthChanged) && width > 0 &&
                code.getCursorBounds(cursorBounds)) {
                // Wait until TextView has laid out the new spans and cursor.
                requestChildRectangleOnScreen(code.root, cursorBounds, true)
                // Replace any old fling with a stationary animation at the new position.
                smoothScrollBy(0, 0)
                followCursor = false
            }
        }
    }
    val syllables = SyllableStrip(ctx, theme)
    private var expanded = false
    private var canExpand = false
    private var hasSyllables = false
    private var currentPanel = FcitxEvent.InputPanelEvent.Data()

    val expandButton: ToolButton = ToolButton(ctx, R.drawable.ic_baseline_expand_less_24, theme).apply {
        image.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        isVisible = false
        isFocusable = true
        setOnClickListener {
            if (canExpand) {
                expanded = !expanded
                updateSyllablePresentation()
                root.requestLayout()
            }
        }
    }

    val expandedSyllables = FrameLayout(ctx).apply { isVisible = false }

    val visible: Boolean get() = code.visible
    val hasInteractiveContent: Boolean get() = syllables.isVisible || expandButton.isVisible ||
        (codeScroll.isVisible &&
            (codeScroll.canScrollHorizontally(-1) || codeScroll.canScrollHorizontally(1)))

    val codeRow: ViewGroup = object : ViewGroup(ctx) {
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
                syllables.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.AT_MOST), heightSpec)
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

    override val root: LinearLayout = object : LinearLayout(ctx) {
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
            val contentWidth = (widthLimit - paddingLeft - paddingRight -
                codeRow.paddingLeft - codeRow.paddingRight).coerceAtLeast(0)
            val needsExpand = if (hasSyllables) {
                // Measure the actual views without clipping either scroll viewport.
                // This includes font metrics, preedit spans, padding and every choice.
                val naturalSize = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                codeScroll.measure(naturalSize, naturalSize)
                syllables.measure(naturalSize, naturalSize)
                // The probe changes child measurements even if the parent's specs
                // stay unchanged. Always restore constrained viewport measurements.
                codeRow.forceLayout()
                expandedSyllables.forceLayout()
                codeScroll.measuredWidth + syllables.measuredWidth > contentWidth
            } else false
            if (canExpand != needsExpand) {
                canExpand = needsExpand
                if (!canExpand) expanded = false
                updateSyllablePresentation()
            }
            // Constrain both surfaces to the actual (possibly floating) keyboard width.
            super.onMeasure(MeasureSpec.makeMeasureSpec(widthLimit, MeasureSpec.AT_MOST), heightMeasureSpec)
        }
    }

    fun update(panel: FcitxEvent.InputPanelEvent.Data, showSyllables: Boolean) {
        if (panel.preedit != currentPanel.preedit || panel.auxUp != currentPanel.auxUp ||
            panel.auxDown != currentPanel.auxDown) {
            followCursor = true
        }
        currentPanel = panel
        code.update(panel)
        hasSyllables = panel.preedit.isNotEmpty() &&
            preeditSyllableActions(panel.tabs, showSyllables).isNotEmpty()
        if (!hasSyllables) {
            canExpand = false
            expanded = false
        }
        updateSyllablePresentation()
        codeScroll.isVisible = code.visible
        root.requestLayout()
    }

    private fun updateSyllablePresentation() {
        val parent = if (expanded) expandedSyllables else codeRow
        if (syllables.parent !== parent) {
            (syllables.parent as? ViewGroup)?.removeView(syllables)
            parent.addView(syllables, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Updating the strip also invalidates any pending clicks when it is hidden.
        syllables.update(currentPanel, hasSyllables, showItems = !canExpand || expanded)
        expandedSyllables.isVisible = expanded
        expandButton.isVisible = canExpand
        expandButton.setIcon(if (expanded) R.drawable.ic_baseline_expand_more_24
            else R.drawable.ic_baseline_expand_less_24)
        expandButton.contentDescription = ctx.getString(if (expanded) R.string.hide_syllables
            else R.string.expand_syllables)
    }
}
