/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input

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
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.isCandidateVisibleToUser
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesVirtualKeyboardPosition
import org.fxboomk.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fxboomk.fcitx5.android.input.candidates.floating.calculateSmartFloatingCandidatesPosition
import org.fxboomk.fcitx5.android.input.candidates.floating.nextFloatingCandidateIndex
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.preedit.PreeditUi
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
    private var activeCandidateOverride: Int? = null
    /** Last engine cursor position; the UI highlight may temporarily differ. */
    private var engineCandidateCursorIndex = -1

    private fun effectiveCandidateCursorIndex(data: FcitxEvent.PagedCandidateEvent.Data): Int =
        if (data.candidates.isEmpty()) -1
        else data.cursorIndex.coerceIn(0, data.candidates.lastIndex)

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
    private var preeditAtBottom = false

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
        // The observer is shared with the whole IME window. A hidden candidate
        // view may keep a pending layout forever because GONE views are skipped.
        if (!isShown) return@OnPreDrawListener true
        if (shouldUpdatePosition) {
            updatePosition()
        }
        // A side change can reorder the content. Lay it out before showing either
        // the visual window or its touch receiver at the new position.
        !isLayoutRequested
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private val setupTextView: TextView.() -> Unit = {
        textSize = FontProviders.getFontSize("cand_font", 20f)
        typeface = FontProviders.resolveTypeface("cand_font", typeface)
        val v = dp(itemPaddingVertical)
        val h = dp(itemPaddingHorizontal)
        setPadding(h, v, h, v)
    }

    private val preeditUi = PreeditUi(ctx, theme, setupTextView)

    private val candidatesUi = PagedCandidatesUi(
        ctx, theme, setupTextView,
        onCandidateClick = { index -> service.postFcitxJob { select(index) } },
        onCandidateAction = { index, text, view -> showCandidateActionMenu(index, text, view) },
        onBindCandidateGesture = ::bindCandidateGesture,
        onUnbindCandidateGesture = ::unbindCandidateGesture,
        onPrevPage = { fcitx.launchOnReady { it.offsetCandidatePage(-1) } },
        onNextPage = { fcitx.launchOnReady { it.offsetCandidatePage(1) } },
        highlightRadius = dp(highlightRadius).toFloat()
    )

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        handleFcitxEvent(FcitxEvent.InputPanelEvent(inputPanelData))
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        if (service.shouldSuppressHardwarePredictionCandidates()) {
            clearPredictionCandidates()
            return
        }
        when (it) {
            is FcitxEvent.InputPanelEvent -> {
                inputPanel = it.data
                updateUi()
            }
            is FcitxEvent.PagedCandidateEvent -> {
                if (paged != it.data) {
                    activeCandidateOverride = null
                }
                paged = it.data
                engineCandidateCursorIndex = effectiveCandidateCursorIndex(it.data)
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
            maxRowWidthPx = maxCandidateRowWidth,
            activeIndexOverride = activeCandidateOverride ?: effectiveCandidateCursorIndex(paged)
        )
        if (evaluateVisibility()) {
            visibility = VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            visibility = INVISIBLE
        }
        shouldUpdatePosition = true
    }

    fun hasCandidates(): Boolean = paged.candidates.isNotEmpty()

    internal fun clearPredictionCandidates() {
        inputPanel = FcitxEvent.InputPanelEvent.Data()
        paged = FcitxEvent.PagedCandidateEvent.Data.Empty
        activeCandidateOverride = null
        engineCandidateCursorIndex = -1
        updateUi()
    }

    internal fun hasVisiblePredictionCandidate(digit: Int? = null): Boolean {
        val index = digit?.minus(1) ?: (activeCandidateOverride ?: effectiveCandidateCursorIndex(paged))
        return index in paged.candidates.indices &&
            candidatesUi.root.getChildAt(index)?.isCandidateVisibleToUser() == true
    }

    internal fun selectVisiblePredictionCandidate(digit: Int? = null): Boolean {
        if (!hasVisiblePredictionCandidate(digit)) return false
        val index = digit?.minus(1) ?: (activeCandidateOverride ?: effectiveCandidateCursorIndex(paged))
        val expected = paged
        service.postFcitxJob {
            if (!service.hardwarePredictionSession.isSuppressed) selectPrediction(index, expected)
        }
        return true
    }

    fun moveActiveCandidate(delta: Int, syncEngine: Boolean = false): Boolean {
        val next = nextFloatingCandidateIndex(
            currentIndex = activeCandidateOverride ?: effectiveCandidateCursorIndex(paged),
            delta = delta,
            candidateCount = paged.candidates.size,
            reversed = candidatesUi.isReversed
        ) ?: return false
        activeCandidateOverride = next
        // Keep fcitx's cursor in step with the Android-side highlight without
        // depending on the user's Up/Down bindings.
        if (syncEngine) {
            val indexDelta = next - engineCandidateCursorIndex
            if (indexDelta != 0) {
                service.postCandidateCursorNavigation(indexDelta)
                engineCandidateCursorIndex = next
            }
        }
        updateUi()
        return true
    }

    fun selectActiveCandidate(): Boolean {
        if (paged.candidates.isEmpty()) return false
        val index = activeCandidateOverride ?: effectiveCandidateCursorIndex(paged)
        if (index !in paged.candidates.indices) return false
        service.postFcitxJob { select(index) }
        return true
    }

    internal fun highlightedNativeCandidateIndex(): Int? =
        (activeCandidateOverride ?: effectiveCandidateCursorIndex(paged)).takeIf {
            hasCandidates() && it in paged.candidates.indices
        }

    internal fun highlightedCandidateText(): String? =
        highlightedNativeCandidateIndex()?.let { paged.candidates.getOrNull(it)?.text }

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
        
        val smartPosition = if (floatingPosition == FloatingCandidatesVirtualKeyboardPosition.Smart) {
            val screenBottom = parentHeight - bottomInsets
            val bottomLimit = if (isVirtualKeyboardVisible &&
                candidatesPrefs.mode.getValue() == FloatingCandidatesMode.Always
            ) {
                keyboardBounds[1].takeIf { it.isFinite() && it > 0f }
                    ?.coerceAtMost(screenBottom) ?: screenBottom
            } else {
                screenBottom
            }
            calculateSmartFloatingCandidatesPosition(
                parentWidth = parentWidth,
                bottomLimit = bottomLimit,
                selfWidth = selfWidth,
                selfHeight = selfHeight,
                cursorX = anchorPosition[0],
                cursorTop = anchorPosition[2],
                cursorBottom = anchorPosition[1],
                gap = dp(4).toFloat(),
                isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
            )
        } else null

        if (updateContentOrder(smartPosition?.isAboveCursor == true)) {
            shouldUpdatePosition = true
            return
        }

        val (tX, tY) = if (smartPosition != null) {
            // FrameLayout places an RTL child at the right edge before translation.
            Pair(smartPosition.x - left, smartPosition.y - top)
        } else if (useKeyboardPosition) {
            calculatePositionByKeyboardBounds(parentWidth, parentHeight, selfWidth, selfHeight)
        } else {
            calculatePositionByCursorAnchor(parentWidth, parentHeight, selfWidth, selfHeight)
        }
        if (tX.isNaN() || tY.isNaN()) {
            // Keep the previous position; rounding NaN would crash. The next valid
            // cursor anchor update repositions the window.
            shouldUpdatePosition = false
            return
        }

        translationX = tX
        translationY = tY
        // update touchEventReceiverWindow's position after CandidatesView's
        touchEventReceiverWindow.showAt(
            (smartPosition?.x ?: tX).roundToInt(),
            (smartPosition?.y ?: tY).roundToInt(), w, h
        )
        shouldUpdatePosition = false
    }

    private fun updateContentOrder(aboveCursor: Boolean): Boolean {
        candidatesUi.setWindowAboveCursor(aboveCursor)
        val reverse = candidatesUi.isReversed
        if (preeditAtBottom == reverse) return false
        preeditAtBottom = reverse
        preeditUi.root.layoutParams = lParams(wrapContent, wrapContent) {
            startOfParent()
            if (reverse) {
                below(candidatesUi.root)
                bottomOfParent()
            } else {
                topOfParent()
            }
        }
        candidatesUi.root.layoutParams = lParams(matchConstraints, wrapContent) {
            matchConstraintMinWidth = wrapContent
            centerHorizontally()
            if (reverse) {
                topOfParent()
            } else {
                below(preeditUi.root)
                bottomOfParent()
            }
        }
        return true
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
            FloatingCandidatesVirtualKeyboardPosition.Smart,
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
            FloatingCandidatesVirtualKeyboardPosition.Smart,
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
            FloatingCandidatesVirtualKeyboardPosition.Smart,
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
            FloatingCandidatesVirtualKeyboardPosition.Smart,
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

    /**
     * Anchor candidates view to bottom-left corner and respect bottom insets.
     * Used when CursorAnchorInfo is invalid.
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition[0] = 0f
        anchorPosition[1] = bottom
        anchorPosition[2] = bottom
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
