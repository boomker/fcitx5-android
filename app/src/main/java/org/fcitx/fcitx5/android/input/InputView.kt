/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.config.ConfigChangeListener
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.status.ButtonsAdjustingWindow
import android.graphics.Rect
import android.graphics.Region
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.backgroundColor
import splitties.views.imageDrawable

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private fun resolveKeyboardBackgroundDrawable(): Drawable {
        if (theme.name == ThemePreset.MinimalRainbow.name) {
            return GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    0xfff29a8f.toInt(),
                    0xfff3be84.toInt(),
                    0xffe9df7a.toInt(),
                    0xff9fcb8b.toInt(),
                    0xff7fc7b2.toInt(),
                    0xff7fb5d2.toInt(),
                    0xffb791c8.toInt()
                )
            ).apply {
                setDither(true)
            }
        }
        val baseDrawable = theme.backgroundDrawable(keyBorder)
        if ((theme as? Theme.Custom)?.backgroundImage != null) {
            return baseDrawable
        }
        val baseColor = if (keyBorder) theme.backgroundColor else theme.keyboardColor
        if (Color.alpha(baseColor) == 0) {
            return baseDrawable
        }
        val monet = if (theme.isDark) ThemeMonet.getDark() else ThemeMonet.getLight()
        val wallpaperTone = if (keyBorder) monet.backgroundColor else monet.keyboardColor
        val blendRatio = (ThemeManager.prefs.wallpaperBlendPercent.getValue().coerceIn(0, 100)) / 100f
        return ColorDrawable(ColorUtils.blendARGB(baseColor, wallpaperTone, blendRatio))
    }

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private fun createHandleDrawable(radius: Float = dp(5).toFloat()) = GradientDrawable().apply {
        setColor(theme.accentKeyBackgroundColor)
        cornerRadius = radius
    }

    private fun updateHandlePosition() {
        if (!isFloating) return

        val kX = keyboardView.translationX
        val kY = keyboardView.translationY
        // Use layout dimensions if available, otherwise estimate
        val kWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        val kHeight = if (keyboardView.height > 0) keyboardView.height else {
            // Default components height estimate
            resolveFloatingHeight() + dp(KawaiiBarComponent.HEIGHT) + keyboardBottomPaddingPx
        }
        // Visual dimensions
        val handleThickness = dp(6)
        val handleLength = dp(48)
        // Total view size including touch padding (24dp total padding, 12dp each side)
        val touchPadding = dp(12)
        val viewThickness = handleThickness + touchPadding * 2
        val viewLength = handleLength + touchPadding * 2

        // Right handle (centered vertically on right edge)
        floatingRightHandle.translationX = kX + kWidth - viewThickness / 2
        floatingRightHandle.translationY = kY + (kHeight - viewLength) / 2
        // Update drawable with insets so the visible part is small but touch area is large
        val rightDrawable = createHandleDrawable()
        floatingRightHandle.background = android.graphics.drawable.InsetDrawable(
            rightDrawable,
            touchPadding, // left
            touchPadding, // top
            touchPadding, // right
            touchPadding  // bottom
        )
        floatingRightHandle.updateLayoutParams {
            width = viewThickness
            height = viewLength
        }
        // Bottom handle (centered horizontally on bottom edge)
        floatingBottomHandle.translationX = kX + (kWidth - viewLength) / 2
        floatingBottomHandle.translationY = kY + kHeight - viewThickness / 2

        val bottomDrawable = createHandleDrawable()
        floatingBottomHandle.background = android.graphics.drawable.InsetDrawable(
            bottomDrawable,
            touchPadding, // left
            touchPadding, // top
            touchPadding, // right
            touchPadding  // bottom
        )
        floatingBottomHandle.updateLayoutParams {
            width = viewLength
            height = viewThickness
        }

        // Move handle (centered horizontally above keyboard)
        val moveHandleSize = dp(24)
        adjustableHandle.translationX = kX + (kWidth - moveHandleSize) / 2
        adjustableHandle.translationY = kY - moveHandleSize - dp(8)

        val moveBgDrawable = createHandleDrawable(moveHandleSize / 2f)
        val moveIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_move_handle_cross)?.mutate()
        val finalDrawable = if (moveIconDrawable != null) {
            moveIconDrawable.setTint(theme.keyTextColor)
            val inset = dp(4)
            val ld = LayerDrawable(arrayOf(moveBgDrawable, moveIconDrawable))
            ld.setLayerInset(1, inset, inset, inset, inset)
            ld
        } else {
            moveBgDrawable
        }
        adjustableHandle.background = finalDrawable
        adjustableHandle.updateLayoutParams {
            width = moveHandleSize
            height = moveHandleSize
        }
    }

    private fun clampFloatingPosition() {
        if (!isEffectiveFloating) return
        val containerWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val containerHeight = if (height > 0) height else resources.displayMetrics.heightPixels
        val keyboardWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        val keyboardHeight = if (keyboardView.height > 0) {
            keyboardView.height
        } else {
            resolveFloatingHeight() + dp(KawaiiBarComponent.HEIGHT) + keyboardBottomPaddingPx
        }

        val maxX = (containerWidth - keyboardWidth).coerceAtLeast(0)
        val maxY = (containerHeight - keyboardHeight).coerceAtLeast(0)
        val clampedX = keyboardView.translationX.coerceIn(0f, maxX.toFloat())
        val clampedY = keyboardView.translationY.coerceIn(0f, maxY.toFloat())

        if (clampedX != keyboardView.translationX || clampedY != keyboardView.translationY) {
            keyboardView.translationX = clampedX
            keyboardView.translationY = clampedY
        }
        preedit.ui.root.translationX = keyboardView.translationX
        preedit.ui.root.translationY = keyboardView.translationY
    }

    private val floatingRightHandle = view(::View) {
        // background set in updateHandlePosition
        visibility = GONE
        // No initial translation needed, controlled by updateHandlePosition
        setOnTouchListener { v, event ->
            if (!isFloating) return@setOnTouchListener false
            // Expand touch area check if needed, but since we're using padding,
            // the view itself is larger.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingResizeStartWidth = resolveFloatingWidth()
                    lastResizeTouchX = event.rawX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawX - lastResizeTouchX).toInt()
                    floatingWidthPx =
                        (floatingResizeStartWidth + delta).coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
                    applyFloatingWidth()
                    // Handle position update is called in applyFloatingWidth
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    // Width is already saved via delegate property set in ACTION_MOVE
                    // Also save position as resizing might have moved handlers
                    saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt()
                    )
                    true
                }

                else -> false
            }
        }
    }

    private val floatingBottomHandle = view(::View) {
        // background set in updateHandlePosition
        visibility = GONE
        // No initial translation needed
        setOnTouchListener { v, event ->
            if (!isEffectiveFloating) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    floatingResizeStartHeight = resolveFloatingHeight()
                    lastResizeTouchY = event.rawY
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - lastResizeTouchY).toInt()
                    floatingHeightPx =
                        (floatingResizeStartHeight + delta).coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
                    applyFloatingHeight()
                    // Handle position update is called in applyFloatingHeight
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    // Height is already saved via delegate property set in ACTION_MOVE
                    // Also save position as resizing might have moved handlers
                    saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt()
                    )
                    true
                }

                else -> false
            }
        }
    }

    private val adjustableHandle = view(::View) {
        visibility = GONE
        setOnTouchListener { v, event ->
            // Check if we're in adjusting mode for bottom padding adjustment
            if (isAdjustingMode) {
                // Handle bottom padding adjustment
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        adjustingResizeStartBottomPadding = resolveKeyboardBottomPadding()
                        lastAdjustingTouchY = event.rawY
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val delta = (lastAdjustingTouchY - event.rawY).toInt()
                        // Scale: 100px drag = 20dp padding change
                        val deltaPadding = (delta * 0.2f).toInt()
                        val newPadding = (adjustingResizeStartBottomPadding + deltaPadding).coerceIn(0, 100)
                        val currentPadding = resolveKeyboardBottomPadding()
                        if (newPadding != currentPadding) {
                            if (isLayoutLandscape) {
                                keyboardPrefs.keyboardBottomPaddingLandscape.setValue(newPadding)
                            } else {
                                keyboardPrefs.keyboardBottomPadding.setValue(newPadding)
                            }
                            updateKeyboardSize()
                            keyboardView.post {
                                updateAdjustingHandlePosition()
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        true
                    }

                    else -> false
                }
            } else if (isFloating) {
                // Handle floating move functionality
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        v.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastTouchX
                        val dy = event.rawY - lastTouchY
                        keyboardView.translationX += dx
                        keyboardView.translationY += dy
                        clampFloatingPosition()
                        keyboardWindow.updateBounds()

                        preedit.ui.root.translationX = keyboardView.translationX
                        preedit.ui.root.translationY = keyboardView.translationY

                        updateHandlePosition()

                        lastTouchX = event.rawX
                        lastTouchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        v.isPressed = false
                        keyboardWindow.updateBounds()
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt()
                        )
                        true
                    }

                    else -> false
                }
            } else {
                // Neither in adjusting mode nor floating mode, return false
                false
            }
        }
    }

    // Adjusting mode handles
    private val adjustingHeightHandle = view(::View) {
        visibility = GONE
        // Background set via updateAdjustingHandleAppearance
        // Larger touch area, visual handle is smaller and centered
        isClickable = true
        isFocusable = true
        setOnTouchListener { v, event ->
            if (!isAdjustingMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Store the current preference value and touch position
                    adjustingResizeStartHeight = resolveKeyboardHeightPercent()
                    lastAdjustingTouchY = event.rawY
                    // Also store the keyboard height at touch start for accurate calculation
                    adjustingStartKeyboardTop = keyboardView.top
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - lastAdjustingTouchY
                    // Calculate percent change based on touch movement
                    // Moving up (negative delta) should increase height
                    // Moving down (positive delta) should decrease height
                    val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                    // Scale factor: 100px drag = 10% keyboard height change (more responsive)
                    val deltaPercent = (delta / screenHeight) * 100f
                    // Match the preference range: 10% to 90%
                    val newPercent = (adjustingResizeStartHeight - deltaPercent).toInt()
                        .coerceIn(10, 90)
                    val currentPercent = resolveKeyboardHeightPercent()
                    // Only update if value changed significantly
                    if (kotlin.math.abs(newPercent - currentPercent) >= 1) {
                        if (isLayoutLandscape) {
                            keyboardPrefs.keyboardHeightPercentLandscape.setValue(newPercent)
                        } else {
                            keyboardPrefs.keyboardHeightPercent.setValue(newPercent)
                        }
                        updateKeyboardSize()
                        keyboardView.post {
                            updateAdjustingHandlePosition()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }

                else -> false
            }
        }
    }

    private val adjustingLeftMarginHandle = view(::View) {
        visibility = GONE
        setOnTouchListener { v, event ->
            if (!isAdjustingMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adjustingResizeStartSidePadding = resolveKeyboardSidePadding()
                    lastAdjustingTouchX = event.rawX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawX - lastAdjustingTouchX).toInt()
                    // Scale: 100px drag = 20dp padding change
                    val deltaPadding = (delta * 0.2f).toInt()
                    val newPadding = (adjustingResizeStartSidePadding + deltaPadding).coerceIn(0, 200)
                    val currentPadding = resolveKeyboardSidePadding()
                    if (newPadding != currentPadding) {
                        if (isLayoutLandscape) {
                            keyboardPrefs.keyboardSidePaddingLandscape.setValue(newPadding)
                        } else {
                            keyboardPrefs.keyboardSidePadding.setValue(newPadding)
                        }
                        updateKeyboardSize()
                        keyboardView.post {
                            updateAdjustingHandlePosition()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }

                else -> false
            }
        }
    }

    private val adjustingRightMarginHandle = view(::View) {
        visibility = GONE
        setOnTouchListener { v, event ->
            if (!isAdjustingMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    adjustingResizeStartSidePadding = resolveKeyboardSidePadding()
                    lastAdjustingTouchX = event.rawX
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = (lastAdjustingTouchX - event.rawX).toInt()
                    // Scale: 100px drag = 20dp padding change
                    val deltaPadding = (delta * 0.2f).toInt()
                    val newPadding = (adjustingResizeStartSidePadding + deltaPadding).coerceIn(0, 200)
                    val currentPadding = resolveKeyboardSidePadding()
                    if (newPadding != currentPadding) {
                        if (isLayoutLandscape) {
                            keyboardPrefs.keyboardSidePaddingLandscape.setValue(newPadding)
                        } else {
                            keyboardPrefs.keyboardSidePadding.setValue(newPadding)
                        }
                        updateKeyboardSize()
                        keyboardView.post {
                            updateAdjustingHandlePosition()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    true
                }

                else -> false
            }
        }
    }


    // Overlay to disable keyboard input during adjusting mode
    private val adjustingOverlay = view(::View) {
        visibility = GONE
        backgroundColor = android.graphics.Color.parseColor("#60000000") // Semi-transparent black
        // Completely disable touch event handling for this view
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        isDuplicateParentStateEnabled = false
        // Important: let touch events pass through to views behind
        filterTouchesWhenObscured = false
        // Make sure this view doesn't consume touch events
        setOnTouchListener { _, _ -> false }
    }

    // Adjusting mode state variables
    private var adjustingResizeStartHeight = 0
    private var adjustingResizeStartSidePadding = 0
    private var adjustingResizeStartBottomPadding = 0
    private var lastAdjustingTouchX = 0f
    private var lastAdjustingTouchY = 0f
    private var adjustingStartKeyboardTop = 0

    private fun resolveKeyboardHeightPercent(): Int {
        return if (isLayoutLandscape) {
            keyboardPrefs.keyboardHeightPercentLandscape.getValue()
        } else {
            keyboardPrefs.keyboardHeightPercent.getValue()
        }
    }

    private fun resolveKeyboardSidePadding(): Int {
        return if (isLayoutLandscape) {
            keyboardPrefs.keyboardSidePaddingLandscape.getValue()
        } else {
            keyboardPrefs.keyboardSidePadding.getValue()
        }
    }

    private fun resolveKeyboardBottomPadding(): Int {
        return if (isLayoutLandscape) {
            keyboardPrefs.keyboardBottomPaddingLandscape.getValue()
        } else {
            keyboardPrefs.keyboardBottomPadding.getValue()
        }
    }

    private fun updateOneHandHandleAppearance() {
        val background = createHandleDrawable(dp(10).toFloat())
        val iconRes = if (oneHandOnRight) {
            R.drawable.ic_baseline_keyboard_arrow_left_24
        } else {
            R.drawable.ic_baseline_keyboard_arrow_right_24
        }
        val icon = ContextCompat.getDrawable(context, iconRes)?.mutate()
        if (icon == null) {
            oneHandHandle.background = background
            return
        }
        icon.setTint(theme.keyTextColor)
        val inset = dp(4)
        val drawable = LayerDrawable(arrayOf(background, icon)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
        oneHandHandle.background = drawable
    }

    private fun updateOneHandHandlePosition() {
        val safeGap = dp(6)
        oneHandHandle.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = windowManager.view.id
            bottomToBottom = windowManager.view.id
            if (oneHandOnRight) {
                startToStart = unset
                endToEnd = unset
                startToEnd = unset
                endToStartOf(windowManager.view)
                marginStart = 0
                marginEnd = safeGap
            } else {
                startToStart = unset
                endToEnd = unset
                endToStart = unset
                startToEndOf(windowManager.view)
                marginStart = safeGap
                marginEnd = 0
            }
        }
    }

    private fun updateOneHandHandleVisibility() {
        oneHandHandle.visibility = if (isOneHanded && !isFloating) VISIBLE else GONE
    }

    private fun syncOneHandHandleUi(bringToFront: Boolean = false) {
        updateOneHandHandleAppearance()
        updateOneHandHandlePosition()
        updateOneHandHandleVisibility()
        if (bringToFront) {
            oneHandHandle.bringToFront()
        }
    }

    private fun syncKeyboardBoundsAfterLayout() {
        keyboardWindow.updateBounds()
        keyboardView.post {
            keyboardWindow.updateBounds()
            keyboardView.post {
                keyboardWindow.updateBounds()
            }
        }
    }

    private fun switchOneHandSide() {
        oneHandOnRight = !oneHandOnRight
        saveOneHandSide(oneHandOnRight)
        if (!isDockedOneHandMode) return
        updateKeyboardSize()
        syncOneHandHandleUi(bringToFront = true)
        syncKeyboardBoundsAfterLayout()
        requestLayout()
    }

    private fun applyOneHandWidth() {
        if (!isDockedOneHandMode) return
        updateKeyboardSize()
        updateOneHandHandlePosition()
        syncKeyboardBoundsAfterLayout()
        keyboardView.invalidateOutline()
        requestLayout()
    }

    private fun updateOneHandGapScale(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOneHandGapRefreshAt < oneHandGapRefreshIntervalMs) {
            return
        }
        lastOneHandGapRefreshAt = now
        val keyboard = windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow ?: return
        if (!isDockedOneHandMode) {
            keyboard.setHorizontalGapScale(1f)
            return
        }
        val containerWidth = keyboardView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val scale = resolveOneHandWidth().toFloat() / containerWidth.toFloat()
        keyboard.setHorizontalGapScale(scale)
    }

    private val oneHandHandle = view(::View) {
        visibility = GONE
        setOnClickListener {
            if (!isDockedOneHandMode) return@setOnClickListener
            switchOneHandSide()
        }
        setOnTouchListener { v, event ->
            if (!isDockedOneHandMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    oneHandResizeStartWidth = resolveOneHandWidth()
                    lastOneHandTouchX = event.rawX
                    oneHandDragging = false
                    lastOneHandGapRefreshAt = 0L
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawX - lastOneHandTouchX
                    if (!oneHandDragging && kotlin.math.abs(delta) > oneHandTouchSlop) {
                        oneHandDragging = true
                    }
                    if (oneHandDragging) {
                        val target = if (oneHandOnRight) {
                            oneHandResizeStartWidth - delta.toInt()
                        } else {
                            oneHandResizeStartWidth + delta.toInt()
                        }
                        oneHandWidthPx = target.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
                        applyOneHandWidth()
                        updateOneHandGapScale()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    if (!oneHandDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    } else if (oneHandDragging) {
                        updateOneHandGapScale(force = true)
                    }
                    oneHandDragging = false
                    true
                }

                else -> false
            }
        }
    }

    private val scope = DynamicScope()
    private val themedContext = context.withTheme(R.style.Theme_InputViewTheme)
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()
    private val buttonsAdjustingOverlayView by lazy {
        ButtonsAdjustingWindow.onCreateView().apply {
            visibility = GONE
        }
    }

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        scope += ButtonsAdjustingWindow
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape
    private val splitKeyboardUseLandscapeLayout = keyboardPrefs.splitKeyboardUseLandscapeLayout

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardPrefs.splitKeyboardEnabled,
        keyboardPrefs.splitKeyboardThreshold,
        keyboardPrefs.splitKeyboardGapPercent,
        splitKeyboardUseLandscapeLayout,
    )

    var isFloating = false
        private set
    var isOneHanded = false
        private set
    var isAdjustingMode = false
        private set

    private var oneHandOnRight = true
    private var oneHandWidthPx = 0
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private var oneHandResizeStartWidth = 0
    private var lastOneHandTouchX = 0f
    private var oneHandDragging = false
    private var lastOneHandGapRefreshAt = 0L
    private val oneHandGapRefreshIntervalMs = 80L
    private val oneHandTouchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private val floatingCornerRadiusPx: Int
        get() = dp(10)

    private val keyboardOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return
            val radius = if (isEffectiveFloating) floatingCornerRadiusPx.toFloat() else 0f
            outline.setRoundRect(0, 0, width, height, radius)
        }
    }

    // Persistent storage for floating state and one-handed mode
    private val internalPrefs = AppPrefs.getInstance().internal

    private var floatingWidthPx by internalPrefs.floatingKeyboardWidth
    private var floatingHeightPx by internalPrefs.floatingKeyboardHeight
    private var floatingXPortrait by internalPrefs.floatingKeyboardXPortrait
    private var floatingYPortrait by internalPrefs.floatingKeyboardYPortrait
    private var floatingXLandscape by internalPrefs.floatingKeyboardXLandscape
    private var floatingYLandscape by internalPrefs.floatingKeyboardYLandscape
    private var oneHandOnRightPortrait by internalPrefs.oneHandOnRightPortrait
    private var oneHandOnRightLandscape by internalPrefs.oneHandOnRightLandscape

    private val isLandscapeOrientation: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Whether layout-related preferences should be treated as landscape.
    // Enabled when device is landscape OR when "use landscape layout when split" is enabled and split keyboard is active.
    // Prefer using the actual keyboard view width when available to decide if split is active.
    private val isLayoutLandscape: Boolean
        get() {
            if (isLandscapeOrientation) return true

            if (!splitKeyboardUseLandscapeLayout.getValue()) return false

            // If split keyboard switch is off, don't treat as split
            if (!keyboardPrefs.splitKeyboardEnabled.getValue()) return false

            // Require manager initialized
            if (!SplitKeyboardStateManager.isInitialized()) return false
            val manager = SplitKeyboardStateManager.getInstance()

            // Try to use actual keyboardView width when available
            val realWidthPx = keyboardView.width.takeIf { it > 0 }
                ?: windowManager.view?.width?.takeIf { it > 0 }
                ?: -1

            val shouldSplit = if (realWidthPx > 0) {
                // Use the width-based API for an accurate decision
                manager.shouldUseSplitKeyboard(realWidthPx)
            } else {
                // Fallback to manager heuristic (based on display metrics)
                manager.shouldUseSplitKeyboard()
            }

            // If we couldn't get real width, schedule a re-evaluation after layout so that
            // once keyboardView has a width we refresh dependent UI.
            if (realWidthPx <= 0) {
                keyboardView.post {
                    // Refresh keyboard layouts if split state might differ
                    (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.refreshAllKeyboards()
                    updateKeyboardSize()
                    requestLayout()
                }
            }

            return shouldSplit
        }

    private val isDockedOneHandMode: Boolean
        get() = isOneHanded && !isFloating

    private val isEffectiveFloating: Boolean
        get() = isFloating

    private fun getStoredFloatingPosition(): Pair<Int, Int> {
        return if (isLandscapeOrientation) {
            floatingXLandscape to floatingYLandscape
        } else {
            floatingXPortrait to floatingYPortrait
        }
    }

    private fun saveFloatingPosition(x: Int, y: Int) {
        if (isLandscapeOrientation) {
            floatingXLandscape = x
            floatingYLandscape = y
        } else {
            floatingXPortrait = x
            floatingYPortrait = y
        }
    }

    private fun getStoredOneHandSide(): Boolean {
        return if (isLandscapeOrientation) {
            oneHandOnRightLandscape
        } else {
            oneHandOnRightPortrait
        }
    }

    private fun saveOneHandSide(onRight: Boolean) {
        if (isLandscapeOrientation) {
            oneHandOnRightLandscape = onRight
        } else {
            oneHandOnRightPortrait = onRight
        }
    }

    private fun applyStoredOneHandSideIfNeeded(forceRefresh: Boolean = false) {
        val stored = getStoredOneHandSide()
        if (!forceRefresh && stored == oneHandOnRight) return
        oneHandOnRight = stored
        if (isDockedOneHandMode) {
            updateKeyboardSize()
            syncOneHandHandleUi(bringToFront = true)
            syncKeyboardBoundsAfterLayout()
        } else {
            syncOneHandHandleUi()
        }
    }

    private var floatingResizeStartWidth = 0
    private var floatingResizeStartHeight = 0
    private var lastResizeTouchX = 0f
    private var lastResizeTouchY = 0f

    private val minFloatingWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private val maxFloatingWidthPx: Int
        get() = resources.displayMetrics.widthPixels.coerceAtLeast(minFloatingWidthPx)

    private val minFloatingHeightPx: Int
        get() = dp(100).coerceAtMost(resources.displayMetrics.heightPixels)

    private val maxFloatingHeightPx: Int
        get() = (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(minFloatingHeightPx)

    private val minOneHandWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private val maxOneHandWidthPx: Int
        get() = resources.displayMetrics.widthPixels.coerceAtLeast(minOneHandWidthPx)

    private fun resolveOneHandWidth(): Int {
        val stored = oneHandWidthPx.takeIf { it > 0 } ?: run {
            val default = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            oneHandWidthPx = default.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
            oneHandWidthPx
        }
        oneHandWidthPx = stored.coerceIn(minOneHandWidthPx, maxOneHandWidthPx)
        return oneHandWidthPx
    }

    private fun resolveFloatingWidth(): Int {
        val stored = floatingWidthPx.takeIf { it > 0 } ?: run {
            val default = (resources.displayMetrics.widthPixels * 0.8).toInt()
            floatingWidthPx = default.coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
            floatingWidthPx
        }
        floatingWidthPx = stored.coerceIn(minFloatingWidthPx, maxFloatingWidthPx)
        return floatingWidthPx
    }

    private fun resolveFloatingHeight(): Int {
        val stored = floatingHeightPx.takeIf { it > 0 } ?: run {
            floatingHeightPx = keyboardHeightPx.coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
            floatingHeightPx
        }
        floatingHeightPx = stored.coerceIn(minFloatingHeightPx, maxFloatingHeightPx)
        return floatingHeightPx
    }

    private fun applyFloatingWidth() {
        keyboardView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = resolveFloatingWidth()
        }
        keyboardWindow.updateBounds()
        keyboardView.invalidateOutline()
        // Force layout pass to update positions
        requestLayout()

        // Sync handles position
        updateHandlePosition()
    }

    private fun applyFloatingHeight() {
        updateKeyboardSize()
        keyboardWindow.updateBounds()
        keyboardView.invalidateOutline()
        // Force layout pass to update positions
        requestLayout()

        // Sync handles position (updateKeyboardSize already calls it, but no harm to ensure)
        updateHandlePosition()
    }

    private fun updateFloatingHandlesVisibility() {
        if (isFloating) {
            floatingRightHandle.visibility = VISIBLE
            floatingBottomHandle.visibility = VISIBLE
            adjustableHandle.visibility = VISIBLE
            return
        }
        floatingRightHandle.visibility = GONE
        floatingBottomHandle.visibility = GONE
        // In adjusting mode, floatingMoveHandle is used as bottom padding adjuster
        if (isAdjustingMode) {
            adjustableHandle.visibility = VISIBLE
        } else {
            adjustableHandle.visibility = GONE
        }
    }

    private fun updateAdjustingHandlesVisibility() {
        if (isAdjustingMode) {
            adjustingHeightHandle.visibility = VISIBLE
            adjustingLeftMarginHandle.visibility = VISIBLE
            adjustingRightMarginHandle.visibility = VISIBLE
            adjustingOverlay.visibility = VISIBLE
            return
        }
        adjustingHeightHandle.visibility = GONE
        adjustingLeftMarginHandle.visibility = GONE
        adjustingRightMarginHandle.visibility = GONE
        adjustingOverlay.visibility = GONE
    }

    private fun updateAdjustingHandlePosition() {
        if (!isAdjustingMode) return
        // Use keyboardView which is always available
        if (keyboardView.width <= 0 || keyboardView.height <= 0) return

        val handleVisualWidth = dp(48)
        val handleVisualHeight = dp(6)
        val touchAreaWidth = dp(60)
        val touchAreaHeight = dp(30)

        // Height handle - horizontal bar at top edge of keyboard
        // Touch area extends above keyboard, visual handle centered in touch area
        adjustingHeightHandle.updateLayoutParams {
            width = touchAreaWidth
            height = touchAreaHeight
        }
        // Position using absolute coordinates relative to parent
        // Place touch area so its center is at keyboard top edge
        // Visual handle (centered in touch area) will appear just above keyboard
        adjustingHeightHandle.translationX = (keyboardView.left + keyboardView.width / 2f - touchAreaWidth / 2f)
        adjustingHeightHandle.translationY = (keyboardView.top - touchAreaHeight / 2f)

        // Left/Right margin handles - positioned at the edges of visible keyboard content
        // The visible content is windowManager.view, constrained between padding spaces
        val marginHandleVisualHeight = dp(48)  // Changed from 80 to 48 to match height handle length
        val marginHandleVisualWidth = dp(6)
        val marginTouchAreaWidth = dp(30)
        val marginTouchAreaHeight = dp(60)  // Changed from 100 to 60 to match height handle touch area

        // Get the actual visible keyboard content bounds
        // windowManager.view is the actual keyboard content area
        // Use keyboardView.top for vertical positioning (same as height handle)
        val contentLeft = windowManager.view.left
        val contentRight = windowManager.view.left + windowManager.view.width

        adjustingLeftMarginHandle.updateLayoutParams {
            width = marginTouchAreaWidth
            height = marginTouchAreaHeight
        }
        // Position at the left edge of visible keyboard content
        adjustingLeftMarginHandle.translationX = (contentLeft - marginTouchAreaWidth / 2f)
        adjustingLeftMarginHandle.translationY =
            (keyboardView.top + keyboardView.height / 2f - marginTouchAreaHeight / 2f)

        adjustingRightMarginHandle.updateLayoutParams {
            width = marginTouchAreaWidth
            height = marginTouchAreaHeight
        }
        // Position at the right edge of visible keyboard content
        adjustingRightMarginHandle.translationX = (contentRight - marginTouchAreaWidth / 2f)
        adjustingRightMarginHandle.translationY =
            (keyboardView.top + keyboardView.height / 2f - marginTouchAreaHeight / 2f)

        // Position floatingMoveHandle (which also serves as bottom padding adjuster in adjusting mode) above kawaii bar
        if (isAdjustingMode) {
            val moveHandleSize = dp(24)
            // Position above kawaii bar, centered horizontally
            // kawaiiBar is inside keyboardView, so we need keyboardView.top + kawaiiBar.top
            val kawaiiTopInParent = keyboardView.top + kawaiiBar.view.top
            // Center the handle horizontally
            adjustableHandle.translationX = (keyboardView.left + keyboardView.width / 2f - moveHandleSize / 2f)
            // Position above kawaii bar with a gap
            adjustableHandle.translationY = (kawaiiTopInParent - moveHandleSize - dp(8)).toFloat()

            // Update layout params for adjusting mode
            adjustableHandle.updateLayoutParams {
                width = moveHandleSize
                height = moveHandleSize
            }
        } else if (isFloating) {
            // In floating mode, position according to original floating logic
            val kX = keyboardView.translationX
            val kY = keyboardView.translationY
            val kWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
            val moveHandleSize = dp(24)
            adjustableHandle.translationX = kX + (kWidth - moveHandleSize) / 2
            adjustableHandle.translationY = kY - moveHandleSize - dp(8)

            // Update layout params for floating mode
            adjustableHandle.updateLayoutParams {
                width = moveHandleSize
                height = moveHandleSize
            }
        }

        // Ensure all handles are brought to front to be above the overlay
        adjustingHeightHandle.bringToFront()
        adjustingLeftMarginHandle.bringToFront()
        adjustingRightMarginHandle.bringToFront()
        // floatingMoveHandle now also serves as the bottom padding adjuster in adjusting mode
        adjustableHandle.bringToFront()
    }

    private fun updateAdjustingOverlayVisibility() {
        if (isAdjustingMode) {
            adjustingOverlay.visibility = VISIBLE
            // Disable keyboard input during adjusting mode
            keyboardView.isEnabled = false
            keyboardView.isClickable = false
            keyboardView.isFocusable = false
            // Keep overlay behind handles - don't call bringToFront on it
            // Handles are added after overlay, so they should be on top by default
        } else {
            adjustingOverlay.visibility = GONE
            keyboardView.isEnabled = true
            keyboardView.isClickable = true
            keyboardView.isFocusable = true
        }
        // Bring floating move handle to front to ensure it's visible above overlay when in adjusting mode
        if (isAdjustingMode) {
            adjustableHandle.bringToFront()
        }
    }

    private fun updateSplitBackgroundVisibility() {
        customBackground.visibility = VISIBLE
    }

    private fun applyDockedKeyboardState() {
        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        params.matchConstraintMaxWidth = params.unset
        params.matchConstraintMinWidth = params.unset
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = params.unset
        params.width = matchParent
        params.startToEnd = params.unset
        params.endToStart = params.unset
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        keyboardView.layoutParams = params
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f

        preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = matchParent
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = keyboardView.id
            topToBottom = unset
            topToTop = unset
            bottomToBottom = unset
        }
        preedit.ui.root.translationX = 0f
        preedit.ui.root.translationY = 0f

        syncOneHandHandleUi()
        syncKeyboardBoundsAfterLayout()
        keyboardView.invalidateOutline()
        requestLayout()
    }

    internal fun toggleFloatingMode() {
        popup.dismissAll()
        if (isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
        }
        if (!isFloating && isOneHanded) {
            isOneHanded = false
        }
        isFloating = !isFloating
        kawaiiBar.setFloatingState(isEffectiveFloating)
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateKeyboardSize() // Add this to refresh padding/height based on new state
        updateOneHandGapScale(force = true)
        service.updateFullscreenMode()
        // Force layout update
        requestLayout()
        // Trigger insets update
        service.window.window?.decorView?.requestLayout()
    }

    internal fun enterAdjustingMode() {
        if (!isAdjustingMode) {
            toggleAdjustingMode()
        }
    }

    internal fun exitAdjustingMode() {
        if (isAdjustingMode) {
            toggleAdjustingMode()
        }
    }

    fun toggleOneHandMode() {
        popup.dismissAll()
        if (isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
            isFloating = false
            kawaiiBar.setFloatingState(false)
        }
        isOneHanded = !isOneHanded
        kawaiiBar.setOneHandKeyboardState(isOneHanded)
        if (isOneHanded) {
            resolveOneHandWidth()
        }
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateKeyboardSize()
        updateOneHandGapScale(force = true)
        if (isOneHanded) {
            syncKeyboardBoundsAfterLayout()
        }
        service.updateFullscreenMode()
        requestLayout()
        service.window.window?.decorView?.requestLayout()
    }

    private fun toggleAdjustingMode() {
        popup.dismissAll()
        isAdjustingMode = !isAdjustingMode
        // In adjusting mode, force non-floating state for better UX
        if (isAdjustingMode && isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
            isFloating = false
            kawaiiBar.setFloatingState(false)
            updateFloatingState()
        }
        updateAdjustingModeUi()
        requestLayout()
    }

    private fun updateAdjustingModeUi() {
        updateAdjustingHandlesVisibility()
        updateAdjustingHandleAppearance()
        // Update floating handles visibility to ensure floatingMoveHandle visibility is correct
        updateFloatingHandlesVisibility()
        // Post to ensure layout is complete before positioning handles and overlay
        keyboardView.post {
            updateAdjustingHandlePosition()
            updateAdjustingOverlayVisibility()
        }
    }

    private fun updateAdjustingHandleAppearance() {
        val handleColor = theme.accentKeyBackgroundColor

        // Visual dimensions for drawables
        val handleVisualWidth = dp(48)
        val handleVisualHeight = dp(6)
        val marginVisualHeight = dp(48)  // Changed from 80 to 48 to match height handle length
        val marginVisualWidth = dp(6)

        // Height handle - horizontal bar with rounded corners, centered in touch area
        val heightBg = GradientDrawable().apply {
            setColor(handleColor)
            cornerRadius = dp(3).toFloat()
            setSize(handleVisualWidth, handleVisualHeight)
        }
        // Use InsetDrawable to center visual handle in larger touch area
        // InsetDrawable params: (drawable, left, top, right, bottom)
        val hInset = (dp(60) - handleVisualWidth) / 2
        val vInset = (dp(30) - handleVisualHeight) / 2
        adjustingHeightHandle.background = android.graphics.drawable.InsetDrawable(
            heightBg,
            hInset, vInset, hInset, vInset
        )

        // Margin handles - vertical bars with rounded corners
        val marginBg = GradientDrawable().apply {
            setColor(handleColor)
            cornerRadius = dp(3).toFloat()
            setSize(marginVisualWidth, marginVisualHeight)
        }
        val mHInset = (dp(30) - marginVisualWidth) / 2
        val mVInset = (dp(60) - marginVisualHeight) / 2  // Changed from 100 to 60 to match height handle touch area
        val marginInsetDrawable = android.graphics.drawable.InsetDrawable(
            marginBg,
            mHInset, mVInset, mHInset, mVInset
        )
        adjustingLeftMarginHandle.background = marginInsetDrawable
        adjustingRightMarginHandle.background = android.graphics.drawable.InsetDrawable(
            marginBg,
            mHInset, mVInset, mHInset, mVInset
        )

        // floatingMoveHandle serves as bottom padding adjuster in adjusting mode
        // Use the same appearance as in floating mode
        val moveHandleSize = dp(24)
        val moveBgDrawable = createHandleDrawable(moveHandleSize / 2f)
        val moveIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_move_handle_cross)?.mutate()
        val finalDrawable = if (moveIconDrawable != null) {
            moveIconDrawable.setTint(theme.keyTextColor)
            val inset = dp(4)
            val ld = LayerDrawable(arrayOf(moveBgDrawable, moveIconDrawable))
            ld.setLayerInset(1, inset, inset, inset, inset)
            ld
        } else {
            moveBgDrawable
        }
        adjustableHandle.background = finalDrawable
    }

    fun getFloatingKeyboardRegion(outRegion: Region) {
        if (!isEffectiveFloating) return
        val rect = Rect()

        keyboardView.getHitRect(rect)

        if (preedit.ui.root.visibility == View.VISIBLE) {
            val preeditRect = Rect()
            preedit.ui.root.getHitRect(preeditRect)
            rect.union(preeditRect)
        }

        if (floatingRightHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingRightHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        if (floatingBottomHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingBottomHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        if (adjustableHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            adjustableHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }

        // Include adjusting mode handles if in adjusting mode (even in floating mode)
        if (isAdjustingMode) {
            if (adjustingHeightHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustingHeightHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }

            if (adjustingLeftMarginHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustingLeftMarginHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }

            if (adjustingRightMarginHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustingRightMarginHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }

            // adjustingBottomHandle has been merged with floatingMoveHandle
            if (adjustableHandle.visibility == View.VISIBLE) {
                val handleRect = Rect()
                adjustableHandle.getHitRect(handleRect)
                rect.union(handleRect)
            }
        }

        // No extra inset needed now as handles provide padding and coverage

        outRegion.set(rect)
    }

    fun getDockedKeyboardRegion(outRegion: Region) {
        val keyboardLocation = IntArray(2)
        keyboardView.getLocationInWindow(keyboardLocation)
        val rect = Rect(
            keyboardLocation[0],
            keyboardLocation[1],
            keyboardLocation[0] + keyboardView.width,
            keyboardLocation[1] + keyboardView.height
        )

        if (oneHandHandle.visibility == View.VISIBLE) {
            val handleLocation = IntArray(2)
            oneHandHandle.getLocationInWindow(handleLocation)
            val handleRect = Rect(
                handleLocation[0],
                handleLocation[1],
                handleLocation[0] + oneHandHandle.width,
                handleLocation[1] + oneHandHandle.height
            )
            rect.union(handleRect)
        }

        // Include adjusting mode handles if in adjusting mode
        if (isAdjustingMode) {
            if (adjustingHeightHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustingHeightHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustingHeightHandle.width,
                    handleLocation[1] + adjustingHeightHandle.height
                )
                rect.union(handleRect)
            }

            if (adjustingLeftMarginHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustingLeftMarginHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustingLeftMarginHandle.width,
                    handleLocation[1] + adjustingLeftMarginHandle.height
                )
                rect.union(handleRect)
            }

            if (adjustingRightMarginHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustingRightMarginHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustingRightMarginHandle.width,
                    handleLocation[1] + adjustingRightMarginHandle.height
                )
                rect.union(handleRect)
            }

            // adjustingBottomHandle has been merged with floatingMoveHandle
            if (adjustableHandle.visibility == View.VISIBLE) {
                val handleLocation = IntArray(2)
                adjustableHandle.getLocationInWindow(handleLocation)
                val handleRect = Rect(
                    handleLocation[0],
                    handleLocation[1],
                    handleLocation[0] + adjustableHandle.width,
                    handleLocation[1] + adjustableHandle.height
                )
                rect.union(handleRect)
            }
        }

        outRegion.set(rect)
    }

    private fun updateFloatingState() {
        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        if (isEffectiveFloating) {
            // Floating mode
            params.width = resolveFloatingWidth()
            params.bottomToBottom = params.unset
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = params.unset

            // Set InputView height to match parent (screen) to allow dragging anywhere
            layoutParams?.height = matchParent

            // In floating mode, we rely on translation.
            val (storedX, storedY) = getStoredFloatingPosition()
            if (storedX != -1 && storedY != -1) {
                keyboardView.translationX = storedX.toFloat()
                keyboardView.translationY = storedY.toFloat()
            } else if (keyboardView.translationX == 0f && keyboardView.translationY == 0f) {
                keyboardView.translationX = (resources.displayMetrics.widthPixels * 0.1).toFloat()
                // Start a bit lower than center to avoid covering input field immediately if possible
                keyboardView.translationY = (resources.displayMetrics.heightPixels * 0.6).toFloat()
            }

            // Sync handles position
            updateHandlePosition()
            // Post update to ensure layout has happened
            keyboardView.post {
                clampFloatingPosition()
                updateHandlePosition()
            }

            // Update preedit constraints for floating mode
            // It should be attached to top of keyboardView
            preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = 0 // match constraints
                // Remove centerHorizontally (startToStart=parent, endToEnd=parent) if present from initial setup
                startToStart = keyboardView.id
                endToEnd = keyboardView.id
                bottomToTop = keyboardView.id
                // Remove other vertical constraints
                topToBottom = unset
                topToTop = unset
                bottomToBottom = unset
            }
            preedit.ui.root.translationX = keyboardView.translationX
            preedit.ui.root.translationY = keyboardView.translationY

            // Apply text scale
            (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.setTextScale(0.8f)

        } else {
            // Docked mode
            layoutParams?.height = matchParent
            applyDockedKeyboardState()
            // Reset text scale
            (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.setTextScale(1.0f)
        }
        // Always update handle position to ensure appearance is set correctly
        updateHandlePosition()
        keyboardView.layoutParams = params
        updateOneHandHandleVisibility()
        updateSplitBackgroundVisibility()
        // Request layout to apply changes to self and children
        keyboardView.invalidateOutline()
        requestLayout()
    }

    private val keyboardHeightPx: Int
        get() {
            val percent = (if (isLayoutLandscape) keyboardHeightPercentLandscape else keyboardHeightPercent).getValue()
            val baseHeight = resources.displayMetrics.heightPixels * percent / 100
            if (isFloating) {
                return (baseHeight * 0.8).toInt()
            }
            return baseHeight
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = (if (isLayoutLandscape) keyboardSidePaddingLandscape else keyboardSidePadding).getValue()
            val px = dp(value)
            if (isFloating) {
                return (px * 0.8).toInt()
            }
            return px
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = (if (isLayoutLandscape) keyboardBottomPaddingLandscape else keyboardBottomPadding).getValue()
            val px = dp(value)
            if (isFloating) {
                return (px * 0.8).toInt()
            }
            return px
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateFloatingState()
            updateFloatingHandlesVisibility()
            updateOneHandHandleVisibility()
            kawaiiBar.setFloatingState(isEffectiveFloating)
            updateKeyboardSize()
            service.updateFullscreenMode()

            // Refresh keyboard layout when split keyboard settings change
            if (key == keyboardPrefs.splitKeyboardEnabled.key ||
                key == keyboardPrefs.splitKeyboardThreshold.key ||
                key == keyboardPrefs.splitKeyboardGapPercent.key
            ) {
                (windowManager.getEssentialWindow(KeyboardWindow) as? KeyboardWindow)?.refreshAllKeyboards()
            }
        }
    }

    val keyboardView: View

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return super.onTouchEvent(event)
    }

    init {
        oneHandOnRight = getStoredOneHandSide()

        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = resolveKeyboardBackgroundDrawable()
        if (windowManager.view.id == View.NO_ID) {
            windowManager.view.id = View.generateViewId()
        }

        keyboardView = constraintLayout {
            id = View.generateViewId()
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(oneHandHandle, lParams(dp(16), dp(44)) {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        keyboardView.clipToOutline = true
        keyboardView.outlineProvider = keyboardOutlineProvider
        keyboardView.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            view.invalidateOutline()
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            bottomToTop = keyboardView.id
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(floatingRightHandle, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(floatingBottomHandle, lParams(dp(10), dp(10)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustableHandle, lParams(dp(24), dp(24)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        // Initialize adjustableHandle visibility based on current state
        updateFloatingHandlesVisibility()
        add(adjustingOverlay, lParams(matchParent, matchParent) {
            topToTop = keyboardView.id
            bottomToBottom = keyboardView.id
            startToStart = keyboardView.id
            endToEnd = keyboardView.id
        })
        // Add handles constrained to parent, positioned absolutely in updateAdjustingHandlePosition
        add(adjustingHeightHandle, lParams(dp(60), dp(30)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingLeftMarginHandle, lParams(dp(30), dp(100)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(adjustingRightMarginHandle, lParams(dp(30), dp(100)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
        add(buttonsAdjustingOverlayView, lParams(matchParent, matchParent) {
            topToTop = keyboardView.id
            bottomToBottom = keyboardView.id
            startToStart = keyboardView.id
            endToEnd = keyboardView.id
        })
        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        updateFloatingState()
        updateFloatingHandlesVisibility()
        updateOneHandHandleVisibility()
        updateSplitBackgroundVisibility()
        kawaiiBar.setFloatingState(isEffectiveFloating)
        updateKeyboardSize()

        kawaiiBar.onFloatingToggleListener = {
            // If currently in adjusting mode, exit adjusting mode; otherwise toggle floating mode
            if (isAdjustingMode) {
                toggleAdjustingMode()
            } else {
                toggleFloatingMode()
            }
        }
        kawaiiBar.onFloatingLongPressListener = {
            // If not in floating mode and not in one-handed mode, allow entering adjusting mode
            if (!isFloating && !isOneHanded) {
                toggleAdjustingMode()
            }
        }

        kawaiiBar.view.setOnTouchListener { v, event ->
            if (!isFloating) return@setOnTouchListener false
            v.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    v.performClick()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastTouchX
                    val dy = event.rawY - lastTouchY
                    keyboardView.translationX += dx
                    keyboardView.translationY += dy
                    clampFloatingPosition()
                    keyboardWindow.updateBounds()
                    // Sync preedit position
                    preedit.ui.root.translationX = keyboardView.translationX
                    preedit.ui.root.translationY = keyboardView.translationY

                    // Sync handles position
                    updateHandlePosition()
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    keyboardWindow.updateBounds() // Ensure bounds are correct after drag
                    // Save position
                    saveFloatingPosition(
                        keyboardView.translationX.toInt(),
                        keyboardView.translationY.toInt()
                    )
                    true
                }

                else -> false
            }
        }
    }

    internal val isButtonsAdjustingOverlayVisible: Boolean
        get() = buttonsAdjustingOverlayView.visibility == VISIBLE

    internal fun showButtonsAdjustingOverlay() {
        if (isButtonsAdjustingOverlayVisible) return
        popup.dismissAll()
        ButtonsAdjustingWindow.updateOverlayInsets(keyboardSidePaddingPx, keyboardBottomPaddingPx)
        ButtonsAdjustingWindow.onAttached()
        buttonsAdjustingOverlayView.bringToFront()
        buttonsAdjustingOverlayView.visibility = VISIBLE
    }

    internal fun hideButtonsAdjustingOverlay() {
        if (!isButtonsAdjustingOverlayVisible) return
        ButtonsAdjustingWindow.onDetached()
        buttonsAdjustingOverlayView.visibility = GONE
    }

    private fun updateKeyboardSize() {
        applyStoredOneHandSideIfNeeded()

        ButtonsAdjustingWindow.updateOverlayInsets(keyboardSidePaddingPx, keyboardBottomPaddingPx)

        val targetHeight = if (isFloating) {
            resolveFloatingHeight()
        } else {
            keyboardHeightPx
        }
        windowManager.view.updateLayoutParams {
            height = targetHeight
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        if (isDockedOneHandMode) {
            val containerWidth = keyboardView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val oneHandWidth = resolveOneHandWidth().coerceAtMost(containerWidth)
            val remaining = (containerWidth - oneHandWidth).coerceAtLeast(0)

            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            if (oneHandOnRight) {
                leftPaddingSpace.updateLayoutParams {
                    width = remaining
                }
                rightPaddingSpace.updateLayoutParams {
                    width = 0
                }
            } else {
                leftPaddingSpace.updateLayoutParams {
                    width = 0
                }
                rightPaddingSpace.updateLayoutParams {
                    width = remaining
                }
            }

            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
            preedit.ui.root.setPadding(0, 0, 0, 0)
            kawaiiBar.view.setPadding(0, 0, 0, 0)
            syncOneHandHandleUi()
            updateHandlePosition()
            syncKeyboardBoundsAfterLayout()
            return
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
        if (isFloating) {
            keyboardView.post {
                clampFloatingPosition()
                updateHandlePosition()
            }
        } else if (isOneHanded) {
            syncOneHandHandleUi()
        }
        // Sync handles when size changes
        updateHandlePosition()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        hideButtonsAdjustingOverlay()
        keyboardWindow.checkAndApplyFontRefresh()
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }

            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }

            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }

            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }

            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }

            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    /**
     * Control whether the preedit component should display preedit text.
     * Set to false when preedit is displayed elsewhere (e.g., in floating CandidatesView).
     */
    fun setPreeditVisibility(shouldDisplay: Boolean) {
        preedit.shouldDisplay = shouldDisplay
    }

    /**
     * Get the Y position of the input field top (where text appears)
     * This is used for positioning floating candidates window
     */
    internal fun getInputFieldTopY(): Float {
        // The input field top is approximately at the top of the keyboard view
        // In virtual keyboard mode, the text appears in the app's input field above the keyboard
        // We use the keyboard top as a reference point for the cursor Y position
        val kv = keyboardView
        val location = IntArray(2)
        kv.getLocationInWindow(location)
        return location[1].toFloat()
    }

    /**
     * Update space key label in "Always" floating mode
     * Called when InputView doesn't handle Fcitx events but still needs to update space key
     */
    internal fun updateSpaceLabelOnFloatingMode() {
        val ime = fcitx.runImmediately { inputMethodEntryCached }

        // Try to notify KeyboardWindow to update space label
        val keyboardWindow = windowManager.getEssentialWindow(KeyboardWindow) as? InputBroadcastReceiver
        if (keyboardWindow != null) {
            keyboardWindow.onImeUpdate(ime)
        } else {
            // Fallback: directly update TextKeyboard if KeyboardWindow is not ready
            // This can happen during initial view setup
            keyboardView?.let { kv ->
                val viewGroup = kv as? ViewGroup
                val childCount = viewGroup?.childCount ?: 0
                for (i in 0 until childCount) {
                    val child = viewGroup?.getChildAt(i)
                    (child as? BaseKeyboard)?.onInputMethodUpdate(ime)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    private val onButtonsLayoutChangeListener: ConfigChangeListener = {
        kawaiiBar.reloadButtonsConfig()
    }

    init {
        // Register listener for buttons layout config changes
        ConfigProviders.addButtonsLayoutListener(onButtonsLayoutChangeListener)
    }

    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        ConfigProviders.removeButtonsLayoutListener(onButtonsLayoutChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
