/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.firstCandidateDrawable
import org.fxboomk.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.dimensions.dp
import kotlin.math.abs

class CandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    private val font: Typeface? = null
) : Ui {

    private val configuredFontSize = FontProviders.getFontSize("cand_font", 20f)

    val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = configuredFontSize
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    init {
        applyConfiguredTypeface()
    }

    private val normalBackground = pressHighlightDrawable(theme.keyPressHighlightColor)

    private val activeBackground = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        cornerRadius = 8f
    }

    private var currentCandidate = CandidateWord.Empty
    private var isActive = false
    private var hasFirstCandidateStyle = false

    private val activeForegroundColor: Int
        get() = if (theme.isDark) Color.BLACK else Color.WHITE

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        val resolved = fontOverride ?: FontProviders.resolveTypeface("cand_font", text.typeface)
        if (text.typeface !== resolved) {
            text.typeface = resolved
        }
    }

    fun setFontScale(scale: Float) {
        text.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            (configuredFontSize * scale).coerceAtLeast(1f)
        )
    }

    fun applyFirstCandidateStyle(
        @ColorInt bgColor: Int,
        @ColorInt strokeColor: Int,
        @ColorInt pressColor: Int,
        cornerRadius: Float = ctx.dp(6f)
    ) {
        root.background = normalBackground
        content.background = firstCandidateDrawable(
            bgColor = bgColor,
            strokeColor = strokeColor,
            cornerRadius = cornerRadius,
            strokeWidth = 1,
            pressColor = pressColor,
        )
        hasFirstCandidateStyle = true
        renderCandidate()
    }

    fun resetToDefaultBackground(@ColorInt pressColor: Int) {
        root.background = pressHighlightDrawable(pressColor)
        content.background = null
        hasFirstCandidateStyle = false
        renderCandidate()
    }

    fun configureHorizontalHighlightSpacing(
        outerPadding: Int,
        highlightPadding: Int,
        verticalPadding: Int,
    ) {
        root.setPadding(outerPadding, verticalPadding, outerPadding, verticalPadding)
        content.setPadding(highlightPadding, 0, highlightPadding, 0)
    }

    /** Preserve full-width text; the viewport follows updates and supports horizontal dragging. */
    fun enableHorizontalOverflow() {
        text.scaleMode = AutoScaleTextView.Mode.None
        content.enableOverflow()
        candidateRoot.scrollableViewport = content
    }

    fun setActive(active: Boolean) {
        isActive = active
        renderCandidate()
        text.background = null
        content.background = null
        root.background = if (active) activeBackground else normalBackground
    }

    fun updateCandidate(candidate: CandidateWord) {
        if (currentCandidate != candidate) {
            content.followTextUpdate()
        }
        currentCandidate = candidate
        renderCandidate()
    }

    private fun renderCandidate() {
        val highlighted = isActive || hasFirstCandidateStyle
        val fg = if (highlighted) activeForegroundColor else theme.candidateTextColor
        val altFg = if (highlighted) activeForegroundColor else theme.candidateCommentColor
        text.setTextColor(fg)
        text.text = buildSpannedString {
            color(fg) {
                append(currentCandidate.text)
            }
            if (currentCandidate.comment.isNotBlank()) {
                if (currentCandidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(currentCandidate.comment)
                }
            }
        }
    }

    private val content = view(::CandidateOverflowViewport) {
        isDuplicateParentStateEnabled = true
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    private val candidateRoot = view(::ScrollableCandidateGestureView) {
        background = normalBackground
        longPressFeedbackEnabled = false
        add(content, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    override val root: CustomGestureView = candidateRoot
}

private class CandidateOverflowViewport(context: Context) : FrameLayout(context) {

    private var overflowEnabled = false
    private var followEnd = true
    private var previousContentWidth = -1
    private var previousTextWidth = -1

    private val contentWidth: Int
        get() = (width - paddingLeft - paddingRight).coerceAtLeast(0)

    private val maxScroll: Float
        get() = ((getChildAt(0)?.width ?: 0) - contentWidth).coerceAtLeast(0).toFloat()

    val hasOverflow: Boolean
        get() = overflowEnabled && maxScroll > 0f

    fun enableOverflow() {
        if (overflowEnabled) return
        overflowEnabled = true
        followTextUpdate()
    }

    fun followTextUpdate() {
        if (!overflowEnabled) return
        followEnd = true
        requestLayout()
    }

    fun scrollByDistance(distance: Float) {
        val child = getChildAt(0) ?: return
        followEnd = false
        child.translationX = -(-child.translationX + distance).coerceIn(0f, maxScroll)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!overflowEnabled) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Measure the entire text, then bound only the viewport to the candidate's slot.
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            heightMeasureSpec,
        )
        setMeasuredDimension(resolveSize(measuredWidth, widthMeasureSpec), measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!overflowEnabled) return
        val child = getChildAt(0) ?: return
        // Anchor overflowing text at the physical left edge before applying the scroll offset.
        // Short text retains FrameLayout's existing centering behavior.
        if (hasOverflow) {
            child.layout(paddingLeft, child.top, paddingLeft + child.measuredWidth, child.bottom)
        }
        val resized = previousContentWidth != contentWidth || previousTextWidth != child.width
        child.translationX = if (followEnd || resized) {
            -maxScroll
        } else {
            child.translationX.coerceIn(-maxScroll, 0f)
        }
        followEnd = false
        previousContentWidth = contentWidth
        previousTextWidth = child.width
    }
}

private class ScrollableCandidateGestureView(context: Context) : CustomGestureView(context) {

    var scrollableViewport: CandidateOverflowViewport? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var horizontalScrolling = false
    private var longPressHandled = false
    private var verticalGesture = false

    override fun performLongClick(): Boolean {
        val handled = super.performLongClick()
        longPressHandled = handled
        return handled
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val viewport = scrollableViewport ?: return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                horizontalScrolling = false
                longPressHandled = false
                verticalGesture = false
                if (isEnabled && viewport.hasOverflow) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(remainingIndex)
                    downX = event.getX(remainingIndex)
                    downY = event.getY(remainingIndex)
                    lastX = downX
                }
                if (horizontalScrolling) return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return horizontalScrolling
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val deltaX = x - downX
                val deltaY = y - downY
                if (isEnabled && !horizontalScrolling && !longPressHandled && !verticalGesture &&
                    viewport.hasOverflow
                ) {
                    if (abs(deltaY) > touchSlop && abs(deltaY) >= abs(deltaX)) {
                        verticalGesture = true
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (abs(deltaX) > touchSlop && abs(deltaX) > abs(deltaY)) {
                        // Cancel both the external long-press listener and CustomGestureView's
                        // pending click/timer before owning the rest of this touch stream.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        horizontalScrolling = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (horizontalScrolling) {
                    viewport.scrollByDistance(lastX - x)
                    lastX = x
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                if (horizontalScrolling) {
                    horizontalScrolling = false
                    cancelGestures()
                    return true
                }
            }
        }
        return horizontalScrolling || super.dispatchTouchEvent(event)
    }
}
