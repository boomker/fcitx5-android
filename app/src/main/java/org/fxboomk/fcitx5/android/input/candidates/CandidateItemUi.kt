/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

@file:Suppress("SetTextI18n")
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.firstCandidateDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class CandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    private val font: Typeface? = null
) : Ui {

    private data class RenderState(
        val candidate: CandidateWord,
        val active: Boolean,
        val firstCandidateStyled: Boolean,
        val firstCandidateBackgroundColor: Int?,
        val foreground: Int,
        val commentForeground: Int,
        val bodyTypeface: Typeface,
        val bodyTextSize: Float,
        val fontScale: Float,
        val fontGeneration: Long,
    )

    // Re-resolve on access (FontProviders caches) so dynamic bar-height measurement
    // tracks fontset edits without recreating this Ui
    private val configuredFontSize: Float
        get() = FontProviders.getFontSize("cand_font", 20f)

    val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = configuredFontSize
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    val pinyinText = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        isSingleLine = true
        gravity = gravityCenter
        visibility = View.GONE
        setTextColor(theme.candidateCommentColor)
    }

    private val highlightRadius: Float
        get() = ctx.dp(AppPrefs.getInstance().candidates.candidateHighlightRadius.getValue().toFloat())

    private val pressedBackground = GradientDrawable().apply {
        setColor(theme.keyPressHighlightColor)
        cornerRadius = highlightRadius
    }

    private val normalBackground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressedBackground)
    }

    private val activeBackground = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
    }

    private var currentCandidate = CandidateWord.Empty
    private var isActive = false
    private var hasFirstCandidateStyle = false
    private var firstCandidateBackgroundColor: Int? = null
    private var indexLabelNumber: Int? = null
    private var fontScale = 1f
    private var lastRenderState: RenderState? = null
    private var resolvedBodyFontGeneration = -1L
    private var resolvedBodyTypeface: Typeface? = null
    private var horizontalHighlightPadding: Int? = null
    private val horizontalNormalBackground by lazy { bodyHighlightBackground(normalBackground) }
    private val horizontalActiveBackground by lazy { bodyHighlightBackground(activeBackground) }

    private fun bodyTextSizePx(scale: Float = fontScale): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        (configuredFontSize * scale).coerceAtLeast(1f),
        ctx.resources.displayMetrics,
    )

    private val activeForegroundColor: Int
        get() = if (theme.isDark) Color.BLACK else Color.WHITE

    /**
     * Small selection-key number drawn at the top-right corner; only meaningful when a
     * physical keyboard drives the candidate bar. `null` hides the label.
     */
    fun setIndexLabel(number: Int?) {
        if (indexLabelNumber == number) return
        indexLabelNumber = number
        if (number == null) {
            indexLabel.visibility = View.GONE
        } else {
            indexLabel.text = number.toString()
            indexLabel.visibility = View.VISIBLE
        }
        renderIndexLabelColor()
    }

    private fun renderIndexLabelColor() {
        val highlighted = isActive || hasFirstCandidateStyle
        indexLabel.setTextColor(
            if (highlighted && horizontalHighlightPadding == null) {
                ColorUtils.setAlphaComponent(activeForegroundColor, 190)
            } else {
                theme.candidateCommentColor
            }
        )
    }

    private fun updateHighlightRadius() {
        val radius = highlightRadius
        activeBackground.cornerRadius = radius
        pressedBackground.cornerRadius = radius
    }

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        configureTypography(fontOverride)
        renderCandidate()
    }

    fun setFontScale(scale: Float) {
        fontScale = scale
        configureTypography(font)
        renderCandidate()
    }

    internal fun updateForMeasurement(candidate: CandidateWord, scale: Float) {
        if (currentCandidate != candidate) content.followTextUpdate()
        currentCandidate = candidate
        fontScale = scale
        configureTypography(font)
        renderCandidate()
    }

    private fun configureTypography(fontOverride: Typeface?) {
        val resolved = fontOverride ?: run {
            val generation = FontProviders.fontGeneration
            resolvedBodyTypeface?.takeIf { resolvedBodyFontGeneration == generation }
                ?: FontProviders.resolveTypeface("cand_font", text.typeface).also {
                    resolvedBodyTypeface = it
                    resolvedBodyFontGeneration = generation
                }
        }
        if (text.typeface !== resolved) text.typeface = resolved
        textColumn.setTextSizePx(bodyTextSizePx(), annotationScale = fontScale)
    }

    fun applyFirstCandidateStyle(
        @ColorInt bgColor: Int,
        @ColorInt strokeColor: Int,
        @ColorInt pressColor: Int
    ) {
        updateHighlightRadius()
        pressedBackground.setColor(pressColor)
        root.background = candidateBackground()
        content.background = bodyHighlightBackground(firstCandidateDrawable(
            bgColor = bgColor,
            strokeColor = strokeColor,
            cornerRadius = highlightRadius,
            strokeWidth = 1,
            pressColor = pressColor,
        ), inContent = true)
        hasFirstCandidateStyle = true
        firstCandidateBackgroundColor = bgColor
        renderCandidate()
    }

    fun resetToDefaultBackground(@ColorInt pressColor: Int) {
        updateHighlightRadius()
        pressedBackground.setColor(pressColor)
        root.background = candidateBackground()
        content.background = null
        hasFirstCandidateStyle = false
        firstCandidateBackgroundColor = null
        renderCandidate()
    }

    fun configureHorizontalHighlightSpacing(
        outerPadding: Int,
        highlightPadding: Int,
        verticalPadding: Int,
    ) {
        horizontalHighlightPadding = highlightPadding
        root.setPadding(outerPadding, verticalPadding, outerPadding, verticalPadding)
        content.setPadding(highlightPadding, 0, highlightPadding, 0)
        // Detach the old drawable before its callback is owned by the wrapper.
        root.background = null
        root.background = candidateBackground(isActive)
        lastRenderState = null
        renderCandidate()
    }

    private fun candidateBackground(active: Boolean = false): Drawable =
        if (horizontalHighlightPadding != null) {
            if (active) horizontalActiveBackground else horizontalNormalBackground
        } else {
            if (active) activeBackground else normalBackground
        }

    /** Bound only the painted background; candidate measurement and hit targets stay full-height. */
    private fun bodyHighlightBackground(background: Drawable, inContent: Boolean = false): Drawable {
        if (horizontalHighlightPadding == null) return background
        return object : DrawableWrapper(background) {
            override fun draw(canvas: Canvas) {
                val ink = text.renderedInkBounds()
                if (ink.isEmpty) return
                ink.offset(text.x + textColumn.x, text.y + textColumn.y)
                // Use the same padding on every side, reducing it together when the
                // row is short or a top annotation needs the space immediately above.
                var padding = minOf(horizontalHighlightPadding!!.toFloat(),
                    ink.top, content.height - ink.bottom).coerceAtLeast(0f)
                if (pinyinText.visibility == View.VISIBLE) {
                    val pinyinBottom = pinyinText.renderedGlyphBounds().bottom + pinyinText.y + textColumn.y
                    padding = minOf(padding, (ink.top - pinyinBottom - ctx.dp(1)).coerceAtLeast(0f))
                }
                ink.inset(-padding, -padding)
                if (!ink.intersect(0f, 0f, content.width.toFloat(), content.height.toFloat())) return
                if (!inContent) ink.offset(content.x, content.y)
                background.setBounds(floor(ink.left).toInt(), floor(ink.top).toInt(),
                    ceil(ink.right).toInt(), ceil(ink.bottom).toInt())
                background.draw(canvas)
            }

            override fun getPadding(padding: Rect): Boolean {
                padding.setEmpty()
                return false
            }

            override fun getCurrent(): Drawable = background.current

            @Suppress("DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    /** Preserve full-width text; the viewport follows updates and supports horizontal dragging. */
    fun enableHorizontalOverflow() {
        text.scaleMode = AutoScaleTextView.Mode.None
        content.enableOverflow()
        candidateRoot.scrollableViewport = content
    }

    fun setActive(active: Boolean) {
        isActive = active
        updateHighlightRadius()
        renderCandidate()
        text.background = null
        content.background = null
        root.background = candidateBackground(active)
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
        renderIndexLabelColor()
        val renderState = RenderState(
            currentCandidate, isActive, hasFirstCandidateStyle, firstCandidateBackgroundColor,
            fg, altFg, text.typeface, text.textSize, fontScale, FontProviders.fontGeneration,
        )
        if (lastRenderState == renderState) return
        lastRenderState = renderState
        text.setTextColor(fg)
        val comment = currentCandidate.displayComment ?: currentCandidate.comment
        val topPinyin = currentCandidate.topPinyinAnnotation
        pinyinText.text = topPinyin.orEmpty()
        pinyinText.visibility = if (topPinyin == null) View.GONE else View.VISIBLE
        pinyinText.setTextColor(if (horizontalHighlightPadding != null) {
            theme.candidateCommentColor
        } else if (highlighted) {
            val background = if (isActive) theme.genericActiveBackgroundColor else {
                firstCandidateBackgroundColor ?: theme.genericActiveBackgroundColor
            }
            CandidateAnnotationLayout.highlightTextColor(background, theme.keyboardColor)
        } else altFg)
        text.text = buildSpannedString {
            color(fg) {
                append(currentCandidate.text)
            }
            if (topPinyin == null && comment.isNotBlank()) {
                if (currentCandidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    inSpans(CandidateAnnotationStyle.span(ctx, text.textSize, fontScale)) {
                        append(comment)
                    }
                }
            }
        }
        content.invalidate()
        root.invalidate()
    }

    private val textColumn = CandidateAnnotationLayout(ctx, text, pinyinText)

    private val content = view(::CandidateOverflowViewport) {
        isDuplicateParentStateEnabled = true
        add(textColumn, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    private val indexLabel = view(::TextView) {
        // Selection-key number for physical keyboard; measured size is kept out of the
        // candidate text metrics on purpose (overlay only, so bar-height math is unchanged)
        textSize = 10f
        includeFontPadding = false
        visibility = View.GONE
    }

    private val candidateRoot = view(::ScrollableCandidateGestureView) {
        baselineProvider = {
            // At a natural height, FrameLayout can keep a MATCH_PARENT child
            // smaller than its minimum height. Account for both centered
            // ancestors before layout, when Flexbox asks for the body baseline.
            val contentTop = paddingTop +
                (measuredHeight - paddingTop - paddingBottom - content.measuredHeight) / 2
            val columnTop = content.paddingTop +
                (content.measuredHeight - content.paddingTop - content.paddingBottom - textColumn.measuredHeight) / 2
            contentTop + columnTop + textColumn.baseline
        }
        background = normalBackground
        longPressFeedbackEnabled = false
        add(content, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
        add(indexLabel, lParams(wrapContent, wrapContent) {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = ctx.dp(2)
        })
    }

    override val root: CustomGestureView = candidateRoot

    init {
        applyConfiguredTypeface()
    }
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
        invalidate()
        (parent as? View)?.invalidate()
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
        invalidate()
        (parent as? View)?.invalidate()
    }
}

private class ScrollableCandidateGestureView(context: Context) : CustomGestureView(context) {

    var scrollableViewport: CandidateOverflowViewport? = null
    var baselineProvider: (() -> Int)? = null

    override fun getBaseline(): Int = baselineProvider?.invoke() ?: super.getBaseline()

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
