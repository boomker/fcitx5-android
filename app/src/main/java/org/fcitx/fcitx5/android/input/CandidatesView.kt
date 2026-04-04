/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import android.widget.TextView
import androidx.annotation.Size
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesVirtualKeyboardPosition
import org.fcitx.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.padding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val ctx = context.withTheme(R.style.Theme_InputViewTheme)

    private val candidatesPrefs = AppPrefs.getInstance().candidates
    private val orientation by candidatesPrefs.orientation
    private val windowMinWidth by candidatesPrefs.windowMinWidth
    private val windowPadding by candidatesPrefs.windowPadding
    private val windowRadius by candidatesPrefs.windowRadius
    private val fontSize by candidatesPrefs.fontSize
    private val itemPaddingVertical by candidatesPrefs.itemPaddingVertical
    private val itemPaddingHorizontal by candidatesPrefs.itemPaddingHorizontal
    private val floatingPosition by candidatesPrefs.virtualKeyboardPosition
    private val highlightRadius by candidatesPrefs.candidateHighlightRadius

    /**
     * Gap between candidates window and screen edges/keyboard
     */
    private val candidatesGap: Float
        get() = dp(8).toFloat()

    /**
     * Minimum keyboard height to consider it visible.
     * If keyboard top is within this distance from screen bottom, keyboard is considered hidden.
     */
    private val keyboardVisibleThreshold: Float
        get() = dp(100).toFloat()

    private var inputPanel = FcitxEvent.InputPanelEvent.Data()
    private var paged = FcitxEvent.PagedCandidateEvent.Data.Empty

    /**
     * horizontal, bottom, top
     */
    private val anchorPosition = floatArrayOf(0f, 0f, 0f)
    private val parentSize = floatArrayOf(0f, 0f)
    
    /**
     * Keyboard boundaries: left, top, right, bottom
     * Used for positioning floating candidates when using virtual keyboard
     */
    private val keyboardBounds = floatArrayOf(0f, 0f, 0f, 0f)
    private var useKeyboardPosition = false

    /**
     * Cursor Y positions for floating candidates positioning
     */
    private var cursorTop = 0f
    private var cursorBottom = 0f

    /**
     * Track whether virtual keyboard is visible
     */
    private var isVirtualKeyboardVisible = true

    private var shouldUpdatePosition = false

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener = OnGlobalLayoutListener {
        shouldUpdatePosition = true
    }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener = OnPreDrawListener {
        if (shouldUpdatePosition) {
            updatePosition()
        }
        true
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private val setupTextView: TextView.() -> Unit = {
        textSize = fontSize.toFloat()
        typeface = org.fcitx.fcitx5.android.input.font.FontProviders.resolveTypeface("cand_font", typeface)
        val v = dp(itemPaddingVertical)
        val h = dp(itemPaddingHorizontal)
        setPadding(h, v, h, v)
    }

    private val preeditUi = PreeditUi(ctx, theme, setupTextView)

    private val candidatesUi = PagedCandidatesUi(
        ctx, theme, setupTextView,
        onCandidateClick = { index -> fcitx.launchOnReady { it.select(index) } },
        onPrevPage = { fcitx.launchOnReady { it.offsetCandidatePage(-1) } },
        onNextPage = { fcitx.launchOnReady { it.offsetCandidatePage(1) } },
        highlightRadius = dp(highlightRadius).toFloat()
    )

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        handleFcitxEvent(FcitxEvent.InputPanelEvent(inputPanelData))
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.InputPanelEvent -> {
                inputPanel = it.data
                updateUi()
            }
            is FcitxEvent.PagedCandidateEvent -> {
                paged = it.data
                updateUi()
            }
            else -> {}
        }
    }

    private fun evaluateVisibility(): Boolean {
        return inputPanel.preedit.isNotEmpty() ||
                paged.candidates.isNotEmpty() ||
                inputPanel.auxUp.isNotEmpty() ||
                inputPanel.auxDown.isNotEmpty()
    }

    private fun updateUi() {
        preeditUi.update(inputPanel)
        preeditUi.root.visibility = if (preeditUi.visible) VISIBLE else GONE
        val parentWidth = parentSize[0]
            .takeIf { it > 0f }
            ?.roundToInt()
            ?: resources.displayMetrics.widthPixels
        val maxCandidateRowWidth = (parentWidth - dp(windowPadding) * 2 - candidatesGap * 2)
            .roundToInt()
            .coerceAtLeast(0)
        candidatesUi.update(
            data = paged,
            orientation = orientation,
            maxRowWidthPx = maxCandidateRowWidth
        )
        if (evaluateVisibility()) {
            visibility = VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            visibility = INVISIBLE
        }
    }

    private var bottomInsets = 0

    private fun updatePosition() {
        if (visibility != VISIBLE) {
            // skip unnecessary updates
            return
        }
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            // panic, bail
            translationX = 0f
            translationY = 0f
            return
        }
        
        val w: Int = width
        val h: Int = height
        val selfWidth = w.toFloat()
        val selfHeight = h.toFloat()
        
        val (tX, tY) = if (useKeyboardPosition) {
            calculatePositionByKeyboardBounds(parentWidth, parentHeight, selfWidth, selfHeight)
        } else {
            calculatePositionByCursorAnchor(parentWidth, parentHeight, selfWidth, selfHeight)
        }
        
        translationX = tX
        translationY = tY
        // update touchEventReceiverWindow's position after CandidatesView's
        touchEventReceiverWindow.showAt(tX.roundToInt(), tY.roundToInt(), w, h)
        shouldUpdatePosition = false
    }

    private fun calculatePositionByCursorAnchor(
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float
    ): Pair<Float, Float> {
        val (horizontal, bottom, top) = anchorPosition
        val gap = candidatesGap

        val floatingMode = AppPrefs.getInstance().candidates.mode.getValue()
        val useFloatingAlways = floatingMode == FloatingCandidatesMode.Always

        if (useFloatingAlways) {
            return calculatePositionForAlwaysMode(
                parentWidth = parentWidth,
                parentHeight = parentHeight,
                selfWidth = selfWidth,
                selfHeight = selfHeight,
                cursorTop = cursorTop,
                cursorBottom = cursorBottom,
                gap = gap,
                isKeyboardVisible = isVirtualKeyboardVisible
            )
        }

        return calculatePositionForPhysicalKeyboard(
            parentWidth = parentWidth,
            parentHeight = parentHeight,
            selfWidth = selfWidth,
            selfHeight = selfHeight,
            horizontal = horizontal,
            bottom = bottom,
            top = top,
            gap = gap
        )
    }

    /**
     * Calculate position for "Always" floating mode
     */
    private fun calculatePositionForAlwaysMode(
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        gap: Float,
        isKeyboardVisible: Boolean
    ): Pair<Float, Float> {
        val keyboardTop = keyboardBounds[1].takeIf { it > 0f } ?: cursorBottom
        this.isVirtualKeyboardVisible = isKeyboardVisible
        val bottomReference = if (isKeyboardVisible) keyboardTop else parentHeight

        val tX = calculateHorizontalPosition(parentWidth, selfWidth, gap)
        val tY = calculateVerticalPositionForAlwaysMode(
            parentHeight = parentHeight,
            selfHeight = selfHeight,
            cursorTop = cursorTop,
            cursorBottom = cursorBottom,
            bottomReference = bottomReference,
            gap = gap
        )

        return Pair(tX, tY)
    }

    /**
     * Calculate horizontal position based on floatingPosition
     */
    private fun calculateHorizontalPosition(
        parentWidth: Float,
        selfWidth: Float,
        gap: Float
    ): Float {
        return when (floatingPosition) {
            FloatingCandidatesVirtualKeyboardPosition.TopLeft,
            FloatingCandidatesVirtualKeyboardPosition.BottomLeft -> {
                gap
            }
            FloatingCandidatesVirtualKeyboardPosition.TopRight,
            FloatingCandidatesVirtualKeyboardPosition.BottomRight -> {
                (parentWidth - selfWidth - gap).coerceAtLeast(gap)
            }
        }
    }

    /**
     * Calculate vertical position for "Always" mode with hysteresis.
     * Requires 2x candidate height to switch position, preventing flicker.
     */
    private fun calculateVerticalPositionForAlwaysMode(
        parentHeight: Float,
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        bottomReference: Float,
        gap: Float
    ): Float {
        val switchThreshold = selfHeight * 2f

        return when (floatingPosition) {
            FloatingCandidatesVirtualKeyboardPosition.TopLeft,
            FloatingCandidatesVirtualKeyboardPosition.TopRight -> {
                val spaceAbove = cursorTop - gap
                if (spaceAbove < switchThreshold) {
                    val belowCursorY = cursorBottom + gap
                    val spaceBelow = bottomReference - (belowCursorY + selfHeight)
                    if (spaceBelow >= 0f) belowCursorY else gap
                } else {
                    gap
                }
            }
            FloatingCandidatesVirtualKeyboardPosition.BottomLeft,
            FloatingCandidatesVirtualKeyboardPosition.BottomRight -> {
                val belowCursorY = cursorBottom + gap
                val spaceBelow = bottomReference - (belowCursorY + selfHeight)
                if (spaceBelow < switchThreshold) {
                    val spaceAbove = cursorTop - gap
                    if (spaceAbove >= selfHeight) {
                        cursorTop - gap - selfHeight
                    } else {
                        (bottomReference - gap - selfHeight).coerceAtLeast(gap)
                    }
                } else {
                    belowCursorY
                }
            }
        }
    }

    @Deprecated("Use inline logic in calculateVerticalPositionForAlwaysMode")
    private fun calculateTopPosition(
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        bottomReference: Float,
        gap: Float
    ): Float {
        val targetY = gap
        val candidatesBottom = targetY + selfHeight
        val wouldOverlap = candidatesBottom > cursorTop

        if (wouldOverlap) {
            val belowCursorY = cursorBottom + gap
            val spaceBelow = bottomReference - (belowCursorY + selfHeight)
            return if (spaceBelow >= 0f) belowCursorY else gap
        }
        return targetY
    }

    @Deprecated("Use inline logic in calculateVerticalPositionForAlwaysMode")
    private fun calculateBottomPosition(
        selfHeight: Float,
        cursorTop: Float,
        cursorBottom: Float,
        bottomReference: Float,
        gap: Float,
        parentHeight: Float
    ): Float {
        val targetY = bottomReference - gap - selfHeight
        val candidatesBottom = targetY + selfHeight
        val candidatesTop = targetY
        val wouldOverlap = candidatesBottom > cursorTop && candidatesTop < cursorBottom

        if (wouldOverlap) {
            val aboveCursorY = cursorTop - gap - selfHeight
            val spaceAbove = cursorTop - gap
            return if (spaceAbove >= selfHeight) aboveCursorY.coerceAtLeast(gap) else targetY.coerceAtLeast(gap)
        }
        return targetY.coerceAtLeast(gap)
    }

    /**
     * Calculate position for physical keyboard mode
     */
    private fun calculatePositionForPhysicalKeyboard(
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float,
        horizontal: Float,
        bottom: Float,
        top: Float,
        gap: Float
    ): Pair<Float, Float> {
        val tX: Float = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            val rtlOffset = parentWidth - horizontal
            if (rtlOffset + selfWidth > parentWidth) selfWidth - parentWidth else -rtlOffset
        } else {
            if (horizontal + selfWidth > parentWidth) parentWidth - selfWidth else horizontal
        }
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - bottom
        // move CandidatesView above cursor anchor, only when
        val tY: Float = if (
            bottom + selfHeight > bottomLimit   // bottom space is not enough
            && top > bottomSpace                // top space is larger than bottom
        ) top - selfHeight else bottom
        return Pair(tX, tY)
    }

    private fun calculatePositionByKeyboardBounds(
        parentWidth: Float,
        parentHeight: Float,
        selfWidth: Float,
        selfHeight: Float
    ): Pair<Float, Float> {
        val gap = dp(8).toFloat() // Gap between keyboard and candidates window

        // cursorBottom is the keyboard top Y position (input field starts here)
        // For BottomLeft/BottomRight positions:
        // - Default: place candidates just above keyboard top
        //   (candidates bottom edge at cursorBottom - gap)
        // - If not enough space (cursorBottom is too low), place candidates as high as possible
        //   while staying within screen bounds

        android.util.Log.d("CandidatesPos", "cursorBottom=$cursorBottom, selfSize: ${selfWidth}x${selfHeight}, position=$floatingPosition")

        // Calculate X position based on floatingPosition
        val tX: Float = when (floatingPosition) {
            FloatingCandidatesVirtualKeyboardPosition.TopLeft,
            FloatingCandidatesVirtualKeyboardPosition.BottomLeft -> {
                gap
            }
            FloatingCandidatesVirtualKeyboardPosition.TopRight,
            FloatingCandidatesVirtualKeyboardPosition.BottomRight -> {
                (parentWidth - selfWidth - gap).coerceAtLeast(gap)
            }
        }

        // Calculate Y position based on floatingPosition
        val tY: Float = when (floatingPosition) {
            FloatingCandidatesVirtualKeyboardPosition.TopLeft,
            FloatingCandidatesVirtualKeyboardPosition.TopRight -> {
                // Top of screen
                gap
            }
            FloatingCandidatesVirtualKeyboardPosition.BottomLeft,
            FloatingCandidatesVirtualKeyboardPosition.BottomRight -> {
                // Bottom positions: place candidates near keyboard top
                // cursorBottom = keyboard top Y
                // Target: candidates bottom edge at (cursorBottom - gap)
                // Formula: tY + selfHeight = cursorBottom - gap  =>  tY = cursorBottom - gap - selfHeight

                val targetY = cursorBottom - gap - selfHeight

                // Ensure candidates stay within screen bounds
                // If targetY < gap, there's not enough space between keyboard and screen top
                targetY.coerceAtLeast(gap)
            }
        }

        android.util.Log.d("CandidatesPos", "Calculated position: tX=$tX, tY=$tY")

        return Pair(tX, tY)
    }

    fun updateCursorAnchor(@Size(4) anchor: FloatArray, @Size(2) parent: FloatArray) {
        val (horizontal, bottom, _, top) = anchor
        val (parentWidth, parentHeight) = parent
        anchorPosition[0] = horizontal
        anchorPosition[1] = bottom
        anchorPosition[2] = top
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        useKeyboardPosition = false
        updatePosition()
    }

    fun updateCursorAnchorForFloating(
        @Size(3) anchor: FloatArray,
        @Size(2) parent: FloatArray,
        keyboardTop: Float = 0f,
        isKeyboardVisible: Boolean = true
    ) {
        val (horizontal, bottom, top) = anchor
        val (parentWidth, parentHeight) = parent
        anchorPosition[0] = horizontal
        anchorPosition[1] = bottom
        anchorPosition[2] = top
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight

        cursorTop = top
        cursorBottom = bottom
        this.keyboardBounds[1] = keyboardTop

        useKeyboardPosition = false
        this.isVirtualKeyboardVisible = isKeyboardVisible
        updatePosition()
    }

    fun updateKeyboardBounds(
        @Size(4) bounds: FloatArray,
        @Size(2) parent: FloatArray,
        cursorY: Float = 0f,
        isKeyboardVisible: Boolean = true
    ) {
        val (left, top, right, bottom) = bounds
        val (parentWidth, parentHeight) = parent

        anchorPosition[0] = right
        anchorPosition[1] = cursorY
        anchorPosition[2] = cursorY
        anchorPosition[3] = parentHeight

        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        useKeyboardPosition = false
        this.isVirtualKeyboardVisible = isKeyboardVisible
        updatePosition()
    }

    init {
        // invisible by default
        visibility = INVISIBLE

        minWidth = dp(windowMinWidth)
        padding = dp(windowPadding)
        background = GradientDrawable().apply {
            setColor(theme.backgroundColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(windowRadius).toFloat()
        }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        add(preeditUi.root, lParams(wrapContent, wrapContent) {
            topOfParent()
            startOfParent()
        })
        add(candidatesUi.root, lParams(matchConstraints, wrapContent) {
            matchConstraintMinWidth = wrapContent
            below(preeditUi.root)
            centerHorizontally()
            bottomOfParent()
        })

        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bottomInsets = getNavBarBottomInset(insets)
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Re-position when size changes (e.g., when content changes)
        if (useKeyboardPosition && visibility == VISIBLE) {
            shouldUpdatePosition = true
        }
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            touchEventReceiverWindow.dismiss()
        }
        super.setVisibility(visibility)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }
}
