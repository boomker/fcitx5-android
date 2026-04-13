/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.SparseIntArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.ScancodeMapping
import org.fxboomk.fcitx5.android.data.InputFeedbacks
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.SwipeAxis
import org.fxboomk.fcitx5.android.input.popup.PopupAction
import org.fxboomk.fcitx5.android.input.popup.PopupActionListener
import org.fxboomk.fcitx5.android.utils.DeviceInfoCollector

// Import Macro types
import org.fxboomk.fcitx5.android.input.keyboard.MacroAction
import org.fxboomk.fcitx5.android.input.keyboard.MacroStep
import org.fxboomk.fcitx5.android.input.keyboard.KeyRef
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

abstract class BaseKeyboard(
    context: Context,
    protected var theme: Theme,
    private val layoutProvider: () ->List<List<KeyDef>>
) : ConstraintLayout(context) {

    private val keyLayout: List<List<KeyDef>>
        get() = layoutProvider()
    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()
    private val splitKeyboardManager = SplitKeyboardStateManager.getInstance()
    private val cachedMacroScancodes = SparseIntArray(64)
    private val shiftKeyCode = KeyEvent.KEYCODE_SHIFT_LEFT
    private val shiftScanCode by lazy { mapFcitxToScanCode("Shift_L", shiftKeyCode) }

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection

    private val spaceSwipeMoveCursor = prefs.keyboard.spaceSwipeMoveCursor
    private val spaceKeys = mutableListOf<KeyView>()
    private val spaceSwipeChangeListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        spaceKeys.forEach {
            it.swipeEnabled = v
        }
    }

    private val vivoKeypressWorkaround by prefs.advanced.vivoKeypressWorkaround

    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    var popupActionListener: PopupActionListener? = null

    private val selectionSwipeThreshold = dp(10f)
    private val inputSwipeThreshold = dp(36f)

    // a rather large threshold effectively disables swipe of the direction
    private val disabledSwipeThreshold = dp(800f)

    private val bounds = Rect()
    private lateinit var keyRows: List<ConstraintLayout>
    private var horizontalGapScale = 1f

    private var lastSplitLandscapeState = false

    @Keep
    private val splitStateChangeListener = SplitKeyboardStateManager.OnSplitStateChangeListener { shouldSplit ->
        // Only reload if split state actually changed
        if (shouldSplit != lastSplitLandscapeState) {
            lastSplitLandscapeState = shouldSplit
            reloadLayout()
            reapplyTextScale()
            requestLayout()
            updateBounds()
        }
    }

    /**
     * HashMap of [PointerId (Int)][MotionEvent.getPointerId] to [KeyView]
     */
    private val touchTarget = hashMapOf<Int, View>()

    /**
     * Find a key view by its type tag. Returns the first matching view or null if not found.
     */
    protected inline fun <reified T : View> findKeyViewById(tagId: Int): T? {
        return allViews.firstOrNull { it.tag == tagId && it is T } as? T
    }

    init {
        isMotionEventSplittingEnabled = true
        reloadLayout()
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        splitKeyboardManager.registerListener(splitStateChangeListener)
    }

    protected open fun reloadLayout() {
        removeAllViews()
        spaceKeys.clear()
        touchTarget.clear()

        // Get all fonts once for batch setting - improves performance by reducing FontProviders access
        val fontMap = org.fxboomk.fcitx5.android.input.font.FontProviders.fontTypefaceMap
        val mainFont = fontMap["key_main_font"]
        val altFont = fontMap["key_alt_font"]

        val splitKeyboard = splitKeyboardManager.shouldUseSplitKeyboard(width)
        lastSplitLandscapeState = splitKeyboard
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView).apply {
                // Batch set fonts for all key views in this row using setFontTypeFace()
                // to properly apply custom fonts from FontProviders
                // Note: Check AltTextKeyView before TextKeyView since AltTextKeyView is a subclass of TextKeyView
                forEach { keyView ->
                    when (keyView) {
                        is AltTextKeyView -> {
                            keyView.mainText.setFontTypeFace("key_main_font")
                            keyView.altText.setFontTypeFace("key_alt_font")
                        }
                        is TextKeyView -> keyView.mainText.setFontTypeFace("key_main_font")
                    }
                }
            }
            if (splitKeyboard) {
                buildSplitRow(row, keyViews)
            } else {
                buildRegularRow(row, keyViews)
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                centerHorizontally()
            })
        }
        // 修复时序问题：在键盘布局完成后，统一刷新所有标点位置
        // 确保使用最终稳定的高度进行判断
        post {
            refreshAltTextLayouts()
        }
    }

    private fun splitGapPercent(): Float {
        return splitKeyboardManager.getSplitGapPercent()
    }

    private fun resolveRowWidths(row: List<KeyDef>): List<Float> {
        if (row.isEmpty()) return emptyList()
        val fixedSum = row.sumOf { def ->
            val width = def.appearance.percentWidth
            if (width > 0f) width.toDouble() else 0.0
        }.toFloat()
        val flexCount = row.count { it.appearance.percentWidth <= 0f }
        val remaining = (1f - fixedSum).coerceAtLeast(0f)
        val flexWidth = if (flexCount > 0) remaining / flexCount else 0f
        val widths = row.map { def ->
            val width = def.appearance.percentWidth
            if (width > 0f) width else flexWidth
        }
        val sum = widths.sum()
        return if (sum > 0f) {
            widths.map { it / sum }
        } else {
            val equal = 1f / widths.size
            widths.map { equal }
        }
    }

    private fun chooseSplitIndex(row: List<KeyDef>, normalizedWidths: List<Float>): Int {
        if (row.size <= 1) return 0
        val candidates = (0 until row.lastIndex)
        var prefix = 0f
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        candidates.forEach { i ->
            prefix += normalizedWidths[i]
            val distance = kotlin.math.abs(prefix - 0.5f)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }

        val spaceIndex = row.indexOfFirst { it is SpaceKey || it is MiniSpaceKey }
        if (spaceIndex in 1 until row.lastIndex) {
            val aroundSpaceCandidates = listOf(spaceIndex - 1, spaceIndex)
                .filter { it in 0 until row.lastIndex }
            var running = 0f
            val prefixByBoundary = HashMap<Int, Float>(row.size)
            for (i in 0 until row.lastIndex) {
                running += normalizedWidths[i]
                prefixByBoundary[i] = running
            }
            aroundSpaceCandidates.forEach { index ->
                val p = prefixByBoundary[index] ?: return@forEach
                val d = kotlin.math.abs(p - 0.5f)
                if (d <= bestDistance + 0.06f) {
                    bestDistance = d
                    bestIndex = index
                }
            }
        }
        return bestIndex
    }

    private fun buildSplitRow(row: List<KeyDef>, keyViews: List<KeyView>): ConstraintLayout = constraintLayout {
        if (row.isEmpty()) return@constraintLayout
        val gap = splitGapPercent()
        val normalizedWidths = resolveRowWidths(row)

        val bridgeIndex = row.indexOfFirst { it is SpaceKey || it is MiniSpaceKey }
            .takeIf { it in 1 until row.lastIndex }
        if (bridgeIndex != null) {
            val minSideReach = 0.06f
            val bridgeMinWidth = (gap + minSideReach * 2f).coerceAtMost(0.75f)
            val bridgeMaxWidth = 0.75f
            val splitScale = (1f - gap).coerceIn(0.40f, 0.95f)

            val leftIndices = 0 until bridgeIndex
            val rightIndices = (bridgeIndex + 1)..keyViews.lastIndex

            val nonBridgeIndices = row.indices.filter { it != bridgeIndex }
            val preferredNormalWidths = nonBridgeIndices.mapNotNull { index ->
                val width = row[index].appearance.percentWidth
                if (width > 0f && row[index] !is SpaceKey && row[index] !is MiniSpaceKey) width else null
            }
            val fallbackWidths = nonBridgeIndices.mapNotNull { index ->
                val width = row[index].appearance.percentWidth
                if (width > 0f) width else null
            }
            val referenceNormalWidth = when {
                preferredNormalWidths.isNotEmpty() -> preferredNormalWidths.sum() / preferredNormalWidths.size
                fallbackWidths.isNotEmpty() -> fallbackWidths.sum() / fallbackWidths.size
                else -> 1f / row.size.coerceAtLeast(1)
            }

            val desiredNonBridgeWidths = mutableMapOf<Int, Float>()
            nonBridgeIndices.forEach { index ->
                val width = row[index].appearance.percentWidth
                val baseWidth = if (width > 0f) width else referenceNormalWidth
                desiredNonBridgeWidths[index] = baseWidth * splitScale
            }

            val assignedNonBridgeWidths = desiredNonBridgeWidths.toMutableMap()
            fun sumAssigned(): Float = assignedNonBridgeWidths.values.sum().coerceIn(0f, 1f)

            val flexIndices = nonBridgeIndices.filter { row[it].appearance.percentWidth <= 0f }
            val fixedIndices = nonBridgeIndices.filter { row[it].appearance.percentWidth > 0f }

            var overflowForMinBridge = (sumAssigned() + bridgeMinWidth - 1f).coerceAtLeast(0f)
            if (overflowForMinBridge > 0f && flexIndices.isNotEmpty()) {
                val flexSum = flexIndices.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (flexSum > 0f) {
                    val reduce = overflowForMinBridge.coerceAtMost(flexSum)
                    flexIndices.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current - reduce * (current / flexSum)
                    }
                    overflowForMinBridge = (sumAssigned() + bridgeMinWidth - 1f).coerceAtLeast(0f)
                }
            }
            if (overflowForMinBridge > 0f && fixedIndices.isNotEmpty()) {
                val fixedSum = fixedIndices.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (fixedSum > 0f) {
                    fixedIndices.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current - overflowForMinBridge * (current / fixedSum)
                    }
                }
            }

            var bridgeWidth = (1f - sumAssigned()).coerceAtLeast(bridgeMinWidth)
            if (bridgeWidth > bridgeMaxWidth) {
                val extra = bridgeWidth - bridgeMaxWidth
                val growTargets = if (flexIndices.isNotEmpty()) flexIndices else fixedIndices
                val targetSum = growTargets.sumOf { (assignedNonBridgeWidths[it] ?: 0f).toDouble() }.toFloat()
                if (targetSum > 0f) {
                    growTargets.forEach { index ->
                        val current = assignedNonBridgeWidths[index] ?: 0f
                        assignedNonBridgeWidths[index] = current + extra * (current / targetSum)
                    }
                }
                bridgeWidth = bridgeMaxWidth
            }

            keyViews.forEachIndexed { index, view ->
                add(view, lParams {
                    centerVertically()
                    matchConstraintPercentWidth = if (index == bridgeIndex) {
                        bridgeWidth
                    } else {
                        assignedNonBridgeWidths[index] ?: 0f
                    }
                    if (index == 0) {
                        leftOfParent()
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.lastIndex) {
                        rightOfParent()
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                })
            }
            return@constraintLayout
        }

        val splitIndex = chooseSplitIndex(row, normalizedWidths)
        val sideCapacity = ((1f - gap) / 2f).coerceAtLeast(0.05f)

        val leftIndices = 0..splitIndex
        val rightIndices = (splitIndex + 1)..keyViews.lastIndex

        fun adjustedSideWidths(indices: IntRange): Map<Int, Float> {
            if (indices.isEmpty()) return emptyMap()
            val base = indices.associateWith { normalizedWidths[it] }
            val flexible = indices.filter { row[it].appearance.percentWidth <= 0f }
            val fixed = indices.filter { row[it].appearance.percentWidth > 0f }

            val fixedSum = fixed.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
            val flexSum = flexible.sumOf { (base[it] ?: 0f).toDouble() }.toFloat()
            val total = (fixedSum + flexSum).coerceAtLeast(0.0001f)

            // Side without flexible keys: just scale proportionally to side capacity.
            if (flexible.isEmpty()) {
                val ratio = sideCapacity / total
                return base.mapValues { (_, w) -> w * ratio }
            }

            // Keep at least part of side capacity for flexible keys (e.g. Space)
            // to avoid "space key too tiny" when gap is large.
            val minFlexShare = (0.30f + (gap - 0.20f) * 0.80f).coerceIn(0.30f, 0.55f)
            val targetFlex = maxOf(
                sideCapacity * minFlexShare,
                (sideCapacity - fixedSum).coerceAtLeast(0f)
            ).coerceAtMost(sideCapacity)
            val targetFixed = (sideCapacity - targetFlex).coerceAtLeast(0f)

            val fixedScale = if (fixedSum > 0f) targetFixed / fixedSum else 0f
            val result = mutableMapOf<Int, Float>()
            fixed.forEach { idx ->
                result[idx] = (base[idx] ?: 0f) * fixedScale
            }
            val flexScale = if (flexSum > 0f) targetFlex / flexSum else 0f
            flexible.forEach { idx ->
                result[idx] = (base[idx] ?: 0f) * flexScale
            }
            return result
        }

        val leftAdjusted = adjustedSideWidths(leftIndices)
        val rightAdjusted = adjustedSideWidths(rightIndices)

        val leftGuide = Guideline(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                orientation = LayoutParams.VERTICAL
                guidePercent = (0.5f - gap / 2f).coerceIn(0.2f, 0.8f)
            }
        }
        val rightGuide = Guideline(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                orientation = LayoutParams.VERTICAL
                guidePercent = (0.5f + gap / 2f).coerceIn(0.2f, 0.8f)
            }
        }
        addView(leftGuide)
        addView(rightGuide)

        keyViews.forEachIndexed { index, view ->
            add(view, lParams {
                centerVertically()
                val isLeftGroup = index <= splitIndex
                matchConstraintPercentWidth = if (isLeftGroup) {
                    leftAdjusted[index] ?: 0f
                } else {
                    rightAdjusted[index] ?: 0f
                }
                if (isLeftGroup) {
                    if (index == 0) {
                        leftOfParent()
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == splitIndex) {
                        rightToLeft = leftGuide.id
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                } else {
                    if (index == splitIndex + 1) {
                        leftToRight = rightGuide.id
                    } else {
                        leftToRightOf(keyViews[index - 1])
                    }
                    if (index == keyViews.lastIndex) {
                        rightOfParent()
                    } else {
                        rightToLeftOf(keyViews[index + 1])
                    }
                }
            })
        }
    }

    private fun buildRegularRow(row: List<KeyDef>, keyViews: List<KeyView>): ConstraintLayout = constraintLayout Row@{
        var totalWidth = 0f
        keyViews.forEachIndexed { index, view ->
            add(view, lParams {
                centerVertically()
                if (index == 0) {
                    leftOfParent()
                    horizontalChainStyle = LayoutParams.CHAIN_PACKED
                } else {
                    leftToRightOf(keyViews[index - 1])
                }
                if (index == keyViews.size - 1) {
                    rightOfParent()
                    // for RTL
                    horizontalChainStyle = LayoutParams.CHAIN_PACKED
                } else {
                    rightToLeftOf(keyViews[index + 1])
                }
                val def = row[index]
                matchConstraintPercentWidth = def.appearance.percentWidth
            })
            row[index].appearance.percentWidth.let {
                // 0f means fill remaining space, thus does not need expanding
                totalWidth += if (it != 0f) it else 1f
            }
        }
        if (expandKeypressArea && totalWidth < 1f) {
            val free = (1f - totalWidth) / 2f
            keyViews.first().apply {
                updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth += free
                }
                layoutMarginLeft = free / (row.first().appearance.percentWidth + free)
            }
            keyViews.last().apply {
                updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth += free
                }
                layoutMarginRight = free / (row.last().appearance.percentWidth + free)
            }
        }
    }

    private var currentTextScale = 1.0f

    fun setTextScale(scale: Float) {
        val scaleChanged = currentTextScale != scale
        currentTextScale = scale
        keyRows.forEach { row ->
            row.children.forEach { child ->
                (child as? KeyView)?.let { keyView ->
                    keyView.setTextScale(scale)
                    if (!scaleChanged) {
                        keyView.requestLayout()
                        keyView.invalidate()
                    }
                }
            }
        }
    }

    fun reapplyTextScale() {
        setTextScale(currentTextScale)
    }

    fun refreshStyle() {
        reloadLayout()
        reapplyTextScale()
        onStyleRefreshFinished()
        requestLayout()
        updateBounds()
    }

    /**
     * Lightweight style refresh, updates colors without rebuilding layout
     */
    fun refreshStyleLight() {
        onStyleRefreshFinished()
        invalidate()
    }

    fun setHorizontalGapScale(scale: Float) {
        val target = scale.coerceIn(0.5f, 1f)
        if (kotlin.math.abs(horizontalGapScale - target) < 0.01f) return
        horizontalGapScale = target
        refreshStyle()
    }

    protected open fun onStyleRefreshFinished() {
        // do nothing by default
    }

    /**
     * Update theme without rebuilding views.
     * Iterates all KeyViews and updates their theme properties.
     */
    fun updateTheme(newTheme: Theme) {
        theme = newTheme

        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach { child ->
                    (child as? KeyView)?.updateTheme(newTheme)
                }
            }
        }

        onThemeUpdate(newTheme)
    }

    /**
     * Called after theme update for keyboard-specific updates
     */
    protected open fun onThemeUpdate(newTheme: Theme) {
        // default: no-op
    }

    /**
     * Refresh all AltTextKeyView layouts with current final heights.
     * Should be called after keyboard size is fully applied and stable.
     */
    fun refreshAltTextLayouts() {
        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach { child ->
                    (child as? AltTextKeyView)?.refreshLayout()
                }
            }
        }
    }

    private fun createKeyView(def: KeyDef): KeyView {
        android.util.Log.d("BaseKeyboard", "createKeyView: def=${def::class.simpleName}, appearance=${def.appearance::class.simpleName}")
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> {
                android.util.Log.d("BaseKeyboard", "createKeyView: Creating AltTextKeyView, altText=${def.appearance.altText}")
                AltTextKeyView(context, theme, def.appearance, horizontalGapScale)
            }
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, theme, def.appearance, horizontalGapScale)
            is KeyDef.Appearance.Text -> TextKeyView(context, theme, def.appearance, horizontalGapScale)
            is KeyDef.Appearance.Image -> ImageKeyView(context, theme, def.appearance, horizontalGapScale)
        }.apply {
            setTextScale(currentTextScale)
            soundEffect = when (def) {
                is SpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is MiniSpaceKey -> InputFeedbacks.SoundEffect.SpaceBar
                is BackspaceKey -> InputFeedbacks.SoundEffect.Delete
                is ReturnKey -> InputFeedbacks.SoundEffect.Return
                else -> InputFeedbacks.SoundEffect.Standard
            }
            if (def is SpaceKey) {
                spaceKeys.add(this)
                swipeEnabled = spaceSwipeMoveCursor.getValue()
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                // Use a larger threshold for Y axis to avoid accidental up/down triggers
                // when user intends to swipe left/right
                swipeThresholdY = selectionSwipeThreshold * 1.5f
                // Track the locked swipe direction to avoid conflicting gestures
                var swipeDirectionLocked: SwipeAxis? = null
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val countX = event.countX
                            val countY = event.countY

                            // Lock direction on first swipe
                            if (swipeDirectionLocked == null && (countX != 0 || countY != 0)) {
                                swipeDirectionLocked = if (kotlin.math.abs(countX) >= kotlin.math.abs(countY)) {
                                    SwipeAxis.X
                                } else {
                                    SwipeAxis.Y
                                }
                            }

                            val handled = when (swipeDirectionLocked) {
                                SwipeAxis.X -> {
                                    if (countX != 0) {
                                        val sym =
                                            if (countX > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                        val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                        repeat(countX.absoluteValue) {
                                            onAction(action)
                                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                SwipeAxis.Y -> {
                                    if (countY != 0) {
                                        val sym =
                                            if (countY > 0) FcitxKeyMapping.FcitxKey_Down else FcitxKeyMapping.FcitxKey_Up
                                        val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                        repeat(countY.absoluteValue) {
                                            onAction(action)
                                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                null -> false
                            }
                            handled
                        }
                        GestureType.Up -> {
                            // Reset direction lock on finger up
                            swipeDirectionLocked = null
                            false
                        }
                        else -> false
                    }
                }
            } else if (def is BackspaceKey) {
                swipeEnabled = true
                swipeRepeatEnabled = true
                swipeThresholdX = selectionSwipeThreshold
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> {
                            val count = event.countX
                            if (count != 0) {
                                onAction(KeyAction.MoveSelectionAction(count))
                                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                true
                            } else false
                        }
                        GestureType.Up -> {
                            onAction(KeyAction.DeleteSelectionAction(event.totalX))
                            false
                        }
                        else -> false
                    }
                }
            }
            // Track if LongPress behavior is configured (for MacroKey longPress feature)
            var hasLongPressBehavior = false
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
                        hasLongPressBehavior = true
                        setOnLongClickListener { _ ->
                            onAction(it.action)
                            true
                        }
                    }
                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { view ->
                            onAction(it.action)
                            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                        }
                    }
                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = disabledSwipeThreshold
                        swipeThresholdY = inputSwipeThreshold
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            when (event.type) {
                                GestureType.Up -> {
                                    if (!event.consumed && shouldTriggerSymbolBySwipe(view, event.totalY)) {
                                        onAction(it.action)
                                        true
                                    } else {
                                        false
                                    }
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { _ ->
                            onAction(it.action)
                        }
                    }
                }
            }
            // Check if there's a LongPressKeyboard in popup array
            // If so, we should use it as the primary popup and skip regular Keyboard processing
            val hasLongPressKeyboard = def.popup?.any { it is KeyDef.Popup.LongPressKeyboard } == true

            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        // Keep long-press behavior unless LongPressKeyboard explicitly takes over.
                        if (!hasLongPressKeyboard && !hasLongPressBehavior) {
                            setOnLongClickListener { view ->
                                view as KeyView
                                onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
                                false
                            }
                            val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                            swipeEnabled = true
                            onGestureListener = OnGestureListener { view, event ->
                                view as KeyView
                                when (event.type) {
                                    GestureType.Move -> {
                                        onPopupChangeFocus(view.id, event.x, event.y)
                                    }
                                    GestureType.Up -> {
                                        onPopupTrigger(view.id)
                                    }
                                    else -> false
                                } || oldOnGestureListener.onGesture(view, event)
                            }
                        }
                    }
                    is KeyDef.Popup.LongPressKeyboard -> {
                        // LongPress macro appears as the first candidate in popup keyboard
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowLongPressKeyboardAction(view.id, it, view.bounds))
                            // do not consume this LongClick gesture
                            false
                        }
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        swipeEnabled = true
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            when (event.type) {
                                GestureType.Move -> {
                                    onPopupChangeFocus(view.id, event.x, event.y)
                                }
                                GestureType.Up -> {
                                    onPopupTrigger(view.id)
                                }
                                else -> false
                            } || oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Keyboard -> {
                        // Keep long-press behavior unless LongPressKeyboard explicitly takes over.
                        // LongPressKeyboard already contains baseLabel to lookup candidates.
                        if (!hasLongPressKeyboard && !hasLongPressBehavior) {
                            setOnLongClickListener { view ->
                                view as KeyView
                                onPopupAction(PopupAction.ShowKeyboardAction(view.id, it, view.bounds))
                                // do not consume this LongClick gesture
                                false
                            }
                            val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                            swipeEnabled = true
                            onGestureListener = OnGestureListener { view, event ->
                                view as KeyView
                                when (event.type) {
                                    GestureType.Move -> {
                                        onPopupChangeFocus(view.id, event.x, event.y)
                                    }
                                    GestureType.Up -> {
                                        onPopupTrigger(view.id)
                                    }
                                    else -> false
                                } || oldOnGestureListener.onGesture(view, event)
                            }
                        }
                    }
                    is KeyDef.Popup.AltPreview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Move -> {
                                        val triggered = shouldTriggerSymbolBySwipe(view, event.totalY)
                                        val text = if (triggered) it.alternative else it.content
                                        onPopupAction(
                                            PopupAction.PreviewUpdateAction(view.id, text)
                                        )
                                    }
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                    is KeyDef.Popup.Preview -> {
                        val oldOnGestureListener = onGestureListener ?: OnGestureListener.Empty
                        onGestureListener = OnGestureListener { view, event ->
                            view as KeyView
                            if (popupOnKeyPress) {
                                when (event.type) {
                                    GestureType.Down -> onPopupAction(
                                        PopupAction.PreviewAction(view.id, it.content, view.bounds)
                                    )
                                    GestureType.Up -> {
                                        onPopupAction(PopupAction.DismissAction(view.id))
                                    }
                                    else -> {}
                                }
                            }
                            // never consume gesture in preview popup
                            oldOnGestureListener.onGesture(view, event)
                        }
                    }
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // Re-evaluate split keyboard when view width changes
        if (w != oldw) {
            val shouldSplit = splitKeyboardManager.shouldUseSplitKeyboard(w)
            if (shouldSplit != lastSplitLandscapeState) {
                reloadLayout()
                reapplyTextScale()
                onStyleRefreshFinished()
                requestLayout()
            }
        }

        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
    }

    fun updateBounds() {
        val (x, y) = intArrayOf(0, 0).also { getLocationInWindow(it) }
        bounds.set(x, y, x + width, y + height)
        if (::keyRows.isInitialized) {
            keyRows.forEach { row ->
                row.children.forEach {
                    (it as? KeyView)?.invalidateCachedBounds()
                }
            }
        }
    }

    fun keyBoundsInKeyboard(): List<Rect> {
        if (!::keyRows.isInitialized) return emptyList()
        updateBounds()
        val result = ArrayList<Rect>(48)
        keyRows.forEach { row ->
            row.children.forEach { child ->
                val key = child as? KeyView ?: return@forEach
                key.updateBounds()
                val rect = Rect(key.bounds)
                rect.offset(-bounds.left, -bounds.top)
                // Exclude key outer margins so only actual key area gets blur
                // and keep inter-key gaps/side-bottom paddings unblurred.
                if (key.hMargin > 0 || key.vMargin > 0) {
                    rect.inset(key.hMargin, key.vMargin)
                }
                if (rect.width() <= 0 || rect.height() <= 0) return@forEach
                result.add(rect)
            }
        }
        return result
    }

    private fun findTargetChild(x: Float, y: Float): View? {
        val y0 = y.roundToInt()
        val x1 = x.roundToInt() + bounds.left
        val y1 = y0 + bounds.top
        return keyRows.asSequence().flatMap { it.children }.find {
            if (it !is KeyView) false else it.bounds.contains(x1, y1)
        }
    }

    private fun transformMotionEventToChild(
        child: View,
        event: MotionEvent,
        action: Int,
        pointerIndex: Int
    ): MotionEvent {
        if (child !is KeyView) {
            Timber.w("child view is not KeyView when transforming MotionEvent $event")
            return event
        }
        val childX = event.getX(pointerIndex) + bounds.left - child.bounds.left
        val childY = event.getY(pointerIndex) + bounds.top - child.bounds.top
        return MotionEvent.obtain(
            event.downTime, event.eventTime, action,
            childX, childY, event.getPressure(pointerIndex), event.getSize(pointerIndex),
            event.metaState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // intercept ACTION_DOWN and all following events will go to parent's onTouchEvent
        return if (vivoKeypressWorkaround && ev.actionMasked == MotionEvent.ACTION_DOWN) true
        else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (vivoKeypressWorkaround) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val target = findTargetChild(event.x, event.y) ?: return false
                    touchTarget[event.getPointerId(0)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, 0)
                    )
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val target = findTargetChild(event.getX(i), event.getY(i)) ?: return false
                    touchTarget[event.getPointerId(i)] = target
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_DOWN, i)
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val target = touchTarget[event.getPointerId(i)] ?: continue
                        target.dispatchTouchEvent(
                            transformMotionEventToChild(target, event, MotionEvent.ACTION_MOVE, i)
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[event.getPointerId(i)] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_UP, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val i = event.actionIndex
                    val pid = event.getPointerId(i)
                    val target = touchTarget[pid] ?: return false
                    target.dispatchTouchEvent(
                        transformMotionEventToChild(target, event, MotionEvent.ACTION_CANCEL, i)
                    )
                    touchTarget.remove(pid)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    @CallSuper
    protected open fun onAction(
        action: KeyAction,
        source: KeyActionListener.Source = KeyActionListener.Source.Keyboard
    ) {
        when (action) {
            is MacroAction -> executeMacro(preprocessMacroAction(action, source))
            else -> keyActionListener?.onKeyAction(action, source)
        }
    }

    private fun shouldTriggerSymbolBySwipe(view: View, totalY: Int): Boolean {
        val altTextView = view as? AltTextKeyView
        return altTextView?.shouldTriggerAltBySwipe(totalY, swipeSymbolDirection)
            ?: swipeSymbolDirection.checkY(totalY)
    }

    protected open fun preprocessMacroAction(
        action: MacroAction,
        source: KeyActionListener.Source
    ): MacroAction = action

    /**
     * Execute a Macro action
     * @param macroAction the MacroAction to execute
     */
    private fun executeMacro(macroAction: MacroAction) {
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            Timber.v("executeMacro: steps=%d", macroAction.steps.size)

            // General macro execution
            for ((index, step) in macroAction.steps.withIndex()) {
                Timber.v("executeMacro: executing step %d type=%s", index, step::class.java.simpleName)
                when (step) {
                    is MacroStep.Down -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    // Fcitx keys follow the Fcitx path:
                                    // - Mappable keys are sent by simulating physical keyboard events
                                    // - Unmappable keys fall back to Fcitx sendKey to avoid silent drops
                                    Timber.v("executeMacro: Down Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyDown(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyDown(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Up -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    Timber.v("executeMacro: Up Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyUp(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyUp(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Tap -> {
                        step.keys.forEach { keyRef ->
                            when (keyRef) {
                                is KeyRef.Fcitx -> {
                                    Timber.v("executeMacro: Tap Fcitx key=%s", keyRef.code)
                                    sendFcitxKeyTap(keyRef.code)
                                }
                                is KeyRef.Android -> sendAndroidKeyTap(keyRef.code)
                            }
                        }
                        delay(50) // key interval
                    }
                    is MacroStep.Text -> {
                        Timber.v("executeMacro: Text length=%d", step.text.length)
                        commitText(step.text)
                    }
                    is MacroStep.Edit -> {
                        Timber.v("executeMacro: Edit action=%s", step.action)
                        executeEditAction(step.action)
                    }
                    is MacroStep.Shortcut -> {
                        Timber.v("executeMacro: Shortcut modifiers=%d key=%s", step.modifiers.size, step.key)
                        executeShortcut(step.modifiers, step.key)
                    }
                }
            }
        }
    }

    /**
     * Execute shortcut as syntactic sugar:
     * modifiers down + key tap + modifiers up.
     */
    private suspend fun executeShortcut(modifiers: List<KeyRef>, key: KeyRef) {
        val modifierDownSteps = mutableListOf<KeyRef>()
        val modifierUpSteps = mutableListOf<KeyRef>()
        
        for (mod in modifiers) {
            val isSupportedModifier = when ((mod as? KeyRef.Fcitx)?.code) {
                "Ctrl_L", "Ctrl_R",
                "Alt_L", "Alt_R",
                "Shift_L", "Shift_R",
                "Meta_L", "Meta_R",
                "Super_L", "Super_R",
                "Hyper_L", "Hyper_R",
                "Mode_switch",
                "ISO_Level3_Shift",
                "ISO_Level5_Shift" -> true
                else -> false
            }
            if (isSupportedModifier || mod is KeyRef.Android) {
                modifierDownSteps.add(mod)
                modifierUpSteps.add(mod)
            }
        }

        // Generic down/tap/up flow
        // Press all modifiers down
        for (mod in modifierDownSteps) {
            when (mod) {
                is KeyRef.Fcitx -> sendFcitxKeyDown(mod.code)
                is KeyRef.Android -> sendAndroidKeyDown(mod.code)
            }
            delay(50)
        }
        
        // key tap
        when (key) {
            is KeyRef.Fcitx -> sendFcitxKeyTap(key.code)
            is KeyRef.Android -> sendAndroidKeyTap(key.code)
        }
        delay(50)
        
        // Release all modifiers (reverse order)
        for (mod in modifierUpSteps.asReversed()) {
            when (mod) {
                is KeyRef.Fcitx -> sendFcitxKeyUp(mod.code)
                is KeyRef.Android -> sendAndroidKeyUp(mod.code)
            }
            delay(50)
        }
    }

    /**
     * Execute edit actions (copy/cut/paste/selectAll/undo/redo)
     */
    private suspend fun executeEditAction(action: String) {
        val service = getService() ?: return
        val ic = service.currentInputConnection ?: return

        when (action.lowercase()) {
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "selectall", "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "undo" -> ic.performContextMenuAction(android.R.id.undo)
            "redo" -> ic.performContextMenuAction(android.R.id.redo)
        }
    }

    /**
     * Check whether it is a function key (F1-F12)
     */
    private fun isFunctionKey(code: String): Boolean {
        return code.matches(Regex("^F(1[0-2]|[1-9])$"))
    }

    /**
     * Check whether it is a modifier key
     * Includes Ctrl, Alt, Shift, Meta, Super, Hyper, and Mode_switch
     */
    private fun isModifierKey(code: String): Boolean {
        return code in arrayOf(
            "Ctrl_L", "Ctrl_R",
            "Alt_L", "Alt_R",
            "Shift_L", "Shift_R",
            "Meta_L", "Meta_R",
            "Super_L", "Super_R",
            "Hyper_L", "Hyper_R",
            "Mode_switch",
            "ISO_Level3_Shift",
            "ISO_Level5_Shift"
        )
    }

    /**
     * Map Fcitx key name to Android key code
     */
    private fun mapFcitxToAndroidKey(code: String): Int {
        return when (code) {
            "Ctrl_L" -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
            "Ctrl_R" -> android.view.KeyEvent.KEYCODE_CTRL_RIGHT
            "Shift_L" -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
            "Shift_R" -> android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
            "Alt_L" -> android.view.KeyEvent.KEYCODE_ALT_LEFT
            "Alt_R" -> android.view.KeyEvent.KEYCODE_ALT_RIGHT
            "Meta_L" -> android.view.KeyEvent.KEYCODE_META_LEFT
            "Meta_R" -> android.view.KeyEvent.KEYCODE_META_RIGHT
            "Enter" -> android.view.KeyEvent.KEYCODE_ENTER
            "Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "Escape" -> android.view.KeyEvent.KEYCODE_ESCAPE
            "Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "Delete" -> android.view.KeyEvent.KEYCODE_FORWARD_DEL
            "BackSpace" -> android.view.KeyEvent.KEYCODE_DEL
            "Home" -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            "End" -> android.view.KeyEvent.KEYCODE_MOVE_END
            "Page_Up" -> android.view.KeyEvent.KEYCODE_PAGE_UP
            "Page_Down" -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
            "Left" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            "Up" -> android.view.KeyEvent.KEYCODE_DPAD_UP
            "Down" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            "F1" -> android.view.KeyEvent.KEYCODE_F1
            "F2" -> android.view.KeyEvent.KEYCODE_F2
            "F3" -> android.view.KeyEvent.KEYCODE_F3
            "F4" -> android.view.KeyEvent.KEYCODE_F4
            "F5" -> android.view.KeyEvent.KEYCODE_F5
            "F6" -> android.view.KeyEvent.KEYCODE_F6
            "F7" -> android.view.KeyEvent.KEYCODE_F7
            "F8" -> android.view.KeyEvent.KEYCODE_F8
            "F9" -> android.view.KeyEvent.KEYCODE_F9
            "F10" -> android.view.KeyEvent.KEYCODE_F10
            "F11" -> android.view.KeyEvent.KEYCODE_F11
            "F12" -> android.view.KeyEvent.KEYCODE_F12
            "A" -> android.view.KeyEvent.KEYCODE_A
            "B" -> android.view.KeyEvent.KEYCODE_B
            "C" -> android.view.KeyEvent.KEYCODE_C
            "D" -> android.view.KeyEvent.KEYCODE_D
            "E" -> android.view.KeyEvent.KEYCODE_E
            "F" -> android.view.KeyEvent.KEYCODE_F
            "G" -> android.view.KeyEvent.KEYCODE_G
            "H" -> android.view.KeyEvent.KEYCODE_H
            "I" -> android.view.KeyEvent.KEYCODE_I
            "J" -> android.view.KeyEvent.KEYCODE_J
            "K" -> android.view.KeyEvent.KEYCODE_K
            "L" -> android.view.KeyEvent.KEYCODE_L
            "M" -> android.view.KeyEvent.KEYCODE_M
            "N" -> android.view.KeyEvent.KEYCODE_N
            "O" -> android.view.KeyEvent.KEYCODE_O
            "P" -> android.view.KeyEvent.KEYCODE_P
            "Q" -> android.view.KeyEvent.KEYCODE_Q
            "R" -> android.view.KeyEvent.KEYCODE_R
            "S" -> android.view.KeyEvent.KEYCODE_S
            "T" -> android.view.KeyEvent.KEYCODE_T
            "U" -> android.view.KeyEvent.KEYCODE_U
            "V" -> android.view.KeyEvent.KEYCODE_V
            "W" -> android.view.KeyEvent.KEYCODE_W
            "X" -> android.view.KeyEvent.KEYCODE_X
            "Y" -> android.view.KeyEvent.KEYCODE_Y
            "Z" -> android.view.KeyEvent.KEYCODE_Z
            "0" -> android.view.KeyEvent.KEYCODE_0
            "1" -> android.view.KeyEvent.KEYCODE_1
            "2" -> android.view.KeyEvent.KEYCODE_2
            "3" -> android.view.KeyEvent.KEYCODE_3
            "4" -> android.view.KeyEvent.KEYCODE_4
            "5" -> android.view.KeyEvent.KEYCODE_5
            "6" -> android.view.KeyEvent.KEYCODE_6
            "7" -> android.view.KeyEvent.KEYCODE_7
            "8" -> android.view.KeyEvent.KEYCODE_8
            "9" -> android.view.KeyEvent.KEYCODE_9
            // Shift+number symbols on standard layouts.
            "exclam", "Exclam" -> android.view.KeyEvent.KEYCODE_1
            "dollar", "Dollar" -> android.view.KeyEvent.KEYCODE_4
            "percent", "Percent" -> android.view.KeyEvent.KEYCODE_5
            "asciicircum", "Asciicircum" -> android.view.KeyEvent.KEYCODE_6
            "ampersand", "Ampersand" -> android.view.KeyEvent.KEYCODE_7
            "asterisk", "Asterisk", "multiply", "Multiply" -> android.view.KeyEvent.KEYCODE_8
            // Parentheses are produced from Shift+9/Shift+0 on standard layouts.
            "parenleft", "Parenleft" -> android.view.KeyEvent.KEYCODE_9
            "parenright", "Parenright" -> android.view.KeyEvent.KEYCODE_0
            // Symbol keys (only Android-supported keycodes)
            "minus", "Minus" -> android.view.KeyEvent.KEYCODE_MINUS
            "underscore", "Underscore" -> android.view.KeyEvent.KEYCODE_MINUS
            "equal", "Equal" -> android.view.KeyEvent.KEYCODE_EQUALS
            "plus", "Plus", "Add" -> android.view.KeyEvent.KEYCODE_EQUALS
            "bracketleft", "Bracketleft", "Bracket_L" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            "braceleft", "Braceleft" -> android.view.KeyEvent.KEYCODE_LEFT_BRACKET
            "bracketright", "Bracketright", "Bracket_R" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            "braceright", "Braceright" -> android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
            "backslash", "Backslash" -> android.view.KeyEvent.KEYCODE_BACKSLASH
            "bar", "Bar" -> android.view.KeyEvent.KEYCODE_BACKSLASH
            "semicolon", "Semicolon" -> android.view.KeyEvent.KEYCODE_SEMICOLON
            "colon", "Colon" -> android.view.KeyEvent.KEYCODE_SEMICOLON
            "apostrophe", "Apostrophe" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            "quotedbl", "Quotedbl" -> android.view.KeyEvent.KEYCODE_APOSTROPHE
            "comma", "Comma", "less", "Less", "Separator" -> android.view.KeyEvent.KEYCODE_COMMA
            "period", "Period", "greater", "Greater" -> android.view.KeyEvent.KEYCODE_PERIOD
            "slash", "Slash", "question", "Question", "Divide" -> android.view.KeyEvent.KEYCODE_SLASH
            "At" -> android.view.KeyEvent.KEYCODE_AT
            "Numbersign" -> android.view.KeyEvent.KEYCODE_POUND
            "grave", "Grave" -> android.view.KeyEvent.KEYCODE_GRAVE
            // KEYCODE_GRAVE + Shift can produce '~', so map asciitilde aliases here too.
            "asciitilde", "Asciitilde", "tilde", "Tilde" -> android.view.KeyEvent.KEYCODE_GRAVE
            // Special keys
            "Print" -> android.view.KeyEvent.KEYCODE_SYSRQ
            "Menu" -> android.view.KeyEvent.KEYCODE_MENU
            "Scroll_Lock" -> android.view.KeyEvent.KEYCODE_SCROLL_LOCK
            "Pause" -> android.view.KeyEvent.KEYCODE_BREAK
            "Break" -> android.view.KeyEvent.KEYCODE_BREAK
            "Insert" -> android.view.KeyEvent.KEYCODE_INSERT
            "Caps_Lock" -> android.view.KeyEvent.KEYCODE_CAPS_LOCK
            "Num_Lock" -> android.view.KeyEvent.KEYCODE_NUM_LOCK
            "Super_L" -> android.view.KeyEvent.KEYCODE_META_LEFT
            "Super_R" -> android.view.KeyEvent.KEYCODE_META_RIGHT
            // Numpad keys
            "KP_Space" -> android.view.KeyEvent.KEYCODE_SPACE
            "KP_Tab" -> android.view.KeyEvent.KEYCODE_TAB
            "KP_0" -> android.view.KeyEvent.KEYCODE_NUMPAD_0
            "KP_1" -> android.view.KeyEvent.KEYCODE_NUMPAD_1
            "KP_2" -> android.view.KeyEvent.KEYCODE_NUMPAD_2
            "KP_3" -> android.view.KeyEvent.KEYCODE_NUMPAD_3
            "KP_4" -> android.view.KeyEvent.KEYCODE_NUMPAD_4
            "KP_5" -> android.view.KeyEvent.KEYCODE_NUMPAD_5
            "KP_6" -> android.view.KeyEvent.KEYCODE_NUMPAD_6
            "KP_7" -> android.view.KeyEvent.KEYCODE_NUMPAD_7
            "KP_8" -> android.view.KeyEvent.KEYCODE_NUMPAD_8
            "KP_9" -> android.view.KeyEvent.KEYCODE_NUMPAD_9
            "KP_Enter" -> android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            "KP_Add" -> android.view.KeyEvent.KEYCODE_NUMPAD_ADD
            "KP_Subtract" -> android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
            "KP_Multiply" -> android.view.KeyEvent.KEYCODE_NUMPAD_MULTIPLY
            "KP_Divide" -> android.view.KeyEvent.KEYCODE_NUMPAD_DIVIDE
            "KP_Decimal" -> android.view.KeyEvent.KEYCODE_NUMPAD_DOT
            "KP_Equal" -> android.view.KeyEvent.KEYCODE_NUMPAD_EQUALS
            "KP_Separator" -> android.view.KeyEvent.KEYCODE_NUMPAD_COMMA
            else -> {
                // For other keys, try converting to lowercase letters
                val lower = code.lowercase()
                if (lower.length == 1 && lower[0] in 'a'..'z') {
                    android.view.KeyEvent.KEYCODE_A + (lower[0] - 'a')
                } else {
                    // Unknown key: default to Fcitx
                    -1
                }
            }
        }
    }

    private fun mapSpecialFcitxToAndroidKey(code: String): Int? {
        return when (code) {
            "Shift_L" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "Shift_R" -> KeyEvent.KEYCODE_SHIFT_RIGHT
            "Ctrl_L" -> KeyEvent.KEYCODE_CTRL_LEFT
            "Ctrl_R" -> KeyEvent.KEYCODE_CTRL_RIGHT
            "Alt_L" -> KeyEvent.KEYCODE_ALT_LEFT
            "Alt_R" -> KeyEvent.KEYCODE_ALT_RIGHT
            "Meta_L" -> KeyEvent.KEYCODE_META_LEFT
            "Meta_R" -> KeyEvent.KEYCODE_META_RIGHT
            "Super_L" -> KeyEvent.KEYCODE_META_LEFT
            "Super_R" -> KeyEvent.KEYCODE_META_RIGHT
            "Hyper_L" -> KeyEvent.KEYCODE_FUNCTION
            "Hyper_R" -> KeyEvent.KEYCODE_FUNCTION
            "Mode_switch" -> KeyEvent.KEYCODE_ALT_RIGHT
            "ISO_Level3_Shift" -> KeyEvent.KEYCODE_ALT_RIGHT
            "ISO_Level5_Shift" -> KeyEvent.KEYCODE_ALT_RIGHT
            else -> null
        }
    }

    private fun shouldShiftSymbol(code: String): Boolean {
        return when (code.lowercase()) {
            "underscore", "plus", "braceleft", "braceright", "bar",
            "colon", "quotedbl", "less", "greater", "question",
            "exclam", "at", "numbersign", "dollar", "percent",
            "asciicircum", "ampersand", "asterisk", "multiply", "parenleft", "parenright",
            "add",
            "asciitilde", "tilde" -> true
            else -> false
        }
    }

    private fun mapFcitxToScanCode(code: String, keyCode: Int): Int {
        val known = when (code) {
            "Shift_L" -> 42
            "Shift_R" -> 54
            "Ctrl_L" -> 29
            "Ctrl_R" -> 97
            "Alt_L" -> 56
            "Alt_R" -> 100
            "Meta_L" -> 125
            "Meta_R" -> 126
            "F1" -> 59
            "F2" -> 60
            "F3" -> 61
            "F4" -> 62
            "F5" -> 63
            "F6" -> 64
            "F7" -> 65
            "F8" -> 66
            "F9" -> 67
            "F10" -> 68
            "F11" -> 87
            "F12" -> 88
            else -> null
        }
        if (known != null) {
            return known
        }
        return if (keyCode >= 0) {
            // Use try-catch to handle potential exceptions from generated code
            try {
                val uncached = Int.MIN_VALUE
                val cached = cachedMacroScancodes.get(keyCode, uncached)
                val scanCode = if (cached != uncached) cached else {
                    ScancodeMapping.keyCodeToScancode(keyCode).also {
                        cachedMacroScancodes.put(keyCode, it)
                    }
                }
                if (scanCode != 0) scanCode else 0
            } catch (e: Exception) {
                Timber.w("keyCodeToScancode failed for keyCode=$keyCode: ${e.message}")
                0
            }
        } else 0
    }

    /**
     * Send Fcitx key tap event (down + up)
     */
    private suspend fun sendFcitxKeyTap(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        // For modifier keys (e.g. Shift), keep press time longer so Rime can recognize standalone Shift
        val isMod = isModifierKey(code)
        val keyHoldDelayMs = if (isMod) 150L else 50L

        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            }
            // Key down
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            delay(keyHoldDelayMs)
            // Key up
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_UP, fromMacro = true)
            }
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = false),
                KeyActionListener.Source.Keyboard
            )
            delay(keyHoldDelayMs)
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = true),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Send Fcitx key down event
     */
    private fun sendFcitxKeyDown(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
            }
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN, fromMacro = true)
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = false),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Send Fcitx key up event
     */
    private fun sendFcitxKeyUp(code: String) {
        val isLetter = code.length == 1 && code[0].isLetter()
        val isUppercaseLetter = isLetter && code[0].isUpperCase()
        val capsOn = isSimulatedCapsLockOn()
        val shouldPressShift = if (isLetter) {
            isUppercaseLetter.xor(capsOn)
        } else {
            shouldShiftSymbol(code)
        }
        val fallbackAct = if (isLetter) code.lowercase() else code
        val keyCode = mapSpecialFcitxToAndroidKey(code) ?: mapFcitxToAndroidKey(code)
        val scanCode = mapFcitxToScanCode(code, keyCode)

        // Send through the physical keyboard path so Rime can recognize correctly
        val service = getService()
        if (service != null && keyCode >= 0) {
            service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP, fromMacro = true)
            if (shouldPressShift) {
                service.sendSimulatedKeyEvent(shiftKeyCode, shiftScanCode, KeyEvent.ACTION_UP, fromMacro = true)
            }
        } else {
            val states = if (shouldPressShift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Empty
            // Fall back to original method
            onAction(
                KeyAction.FcitxKeyAction(act = fallbackAct, code = scanCode, states = states, up = true),
                KeyActionListener.Source.Keyboard
            )
        }
    }

    /**
     * Get FcitxInputMethodService instance
     */
    private fun getService(): org.fxboomk.fcitx5.android.input.FcitxInputMethodService? {
        // Try obtaining directly from context
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is org.fxboomk.fcitx5.android.input.FcitxInputMethodService) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return context as? org.fxboomk.fcitx5.android.input.FcitxInputMethodService
    }

    protected fun isSimulatedCapsLockOn(): Boolean {
        return getService()?.isSimulatedCapsLockOn() == true
    }

    /**
     * Send Android key down event
     */
    private fun sendAndroidKeyDown(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val event = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(event)
    }

    /**
     * Send Android key up event
     */
    private fun sendAndroidKeyUp(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val event = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(event)
    }

    /**
     * Send Android key tap event (down + up)
     */
    private fun sendAndroidKeyTap(keyCode: Int) {
        if (keyCode < 0) return
        val service = getService() ?: return
        val downEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            keyCode
        )
        val upEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            keyCode
        )
        service.currentInputConnection?.sendKeyEvent(downEvent)
        service.currentInputConnection?.sendKeyEvent(upEvent)
    }

    /**
     * Commit text
     */
    private fun commitText(text: String) {
        keyActionListener?.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
    }

    @CallSuper
    protected open fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        popupActionListener?.onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        // ask popup keyboard whether there's a pending KeyAction
        onPopupAction(triggerAction)
        val action = triggerAction.outAction ?: return false
        onAction(action, KeyActionListener.Source.Popup)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    open fun onAttach() {
    }

    open fun onReturnDrawableUpdate(@DrawableRes returnDrawable: Int) {
        // do nothing by default
    }

    open fun onPunctuationUpdate(mapping: Map<String, String>) {
        // do nothing by default
    }

    open fun onInputMethodUpdate(ime: InputMethodEntry) {
        // do nothing by default
    }

    open fun onDetach() {
        // do nothing by default
    }

}
