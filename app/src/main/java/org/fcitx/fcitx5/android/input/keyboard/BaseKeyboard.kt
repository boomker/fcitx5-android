/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
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
    protected val theme: Theme,
    private val layoutProvider: () ->List<List<KeyDef>>
) : ConstraintLayout(context) {

    private val keyLayout: List<List<KeyDef>>
        get() = layoutProvider()
    var keyActionListener: KeyActionListener? = null

    private val prefs = AppPrefs.getInstance()

    private val popupOnKeyPress by prefs.keyboard.popupOnKeyPress
    private val expandKeypressArea by prefs.keyboard.expandKeypressArea
    private val swipeSymbolDirection by prefs.keyboard.swipeSymbolDirection
    private val splitKeyboardLandscape = prefs.keyboard.splitKeyboardLandscape
    private val splitKeyboardGapPercent = prefs.keyboard.splitKeyboardGapPercent

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
    private val splitKeyboardLandscapeListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        reloadLayout()
        reapplyTextScale()
        requestLayout()
        updateBounds()
    }

    @Keep
    private val splitKeyboardGapPercentListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        if (shouldUseSplitLandscapeLayout()) {
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

    init {
        isMotionEventSplittingEnabled = true
        reloadLayout()
        spaceSwipeMoveCursor.registerOnChangeListener(spaceSwipeChangeListener)
        splitKeyboardLandscape.registerOnChangeListener(splitKeyboardLandscapeListener)
        splitKeyboardGapPercent.registerOnChangeListener(splitKeyboardGapPercentListener)
    }

    protected open fun reloadLayout() {
        removeAllViews()
        spaceKeys.clear()
        touchTarget.clear()
        val splitLandscape = shouldUseSplitLandscapeLayout()
        lastSplitLandscapeState = splitLandscape
        keyRows = keyLayout.map { row ->
            val keyViews = row.map(::createKeyView)
            if (splitLandscape) {
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
    }

    private fun shouldUseSplitLandscapeLayout(): Boolean {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return isLandscape && splitKeyboardLandscape.getValue()
    }

    private fun splitGapPercent(): Float {
        return (splitKeyboardGapPercent.getValue().coerceIn(5, 60) / 100f)
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

    fun setHorizontalGapScale(scale: Float) {
        val target = scale.coerceIn(0.5f, 1f)
        if (kotlin.math.abs(horizontalGapScale - target) < 0.01f) return
        horizontalGapScale = target
        refreshStyle()
    }

    protected open fun onStyleRefreshFinished() {
        // do nothing by default
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, theme, def.appearance, horizontalGapScale)
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
                swipeThresholdY = disabledSwipeThreshold
                onGestureListener = OnGestureListener { view, event ->
                    when (event.type) {
                        GestureType.Move -> when (val count = event.countX) {
                            0 -> false
                            else -> {
                                val sym =
                                    if (count > 0) FcitxKeyMapping.FcitxKey_Right else FcitxKeyMapping.FcitxKey_Left
                                val action = KeyAction.SymAction(KeySym(sym), KeyStates.Virtual)
                                repeat(count.absoluteValue) {
                                    onAction(action)
                                    if (hapticOnRepeat) InputFeedbacks.hapticFeedback(view)
                                }
                                true
                            }
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
            def.behaviors.forEach {
                when (it) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener { _ ->
                            onAction(it.action)
                        }
                    }
                    is KeyDef.Behavior.LongPress -> {
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
                                    if (!event.consumed && swipeSymbolDirection.checkY(event.totalY)) {
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
            def.popup?.forEach {
                when (it) {
                    // TODO: gesture processing middleware
                    is KeyDef.Popup.Menu -> {
                        setOnLongClickListener { view ->
                            view as KeyView
                            onPopupAction(PopupAction.ShowMenuAction(view.id, it, view.bounds))
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
                                        val triggered = swipeSymbolDirection.checkY(event.totalY)
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
        val currentSplit = shouldUseSplitLandscapeLayout()
        if (currentSplit != lastSplitLandscapeState) {
            reloadLayout()
            reapplyTextScale()
            requestLayout()
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
        keyActionListener?.onKeyAction(action, source)
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
        FontProviders.refreshIfNeededOnAttach()
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
