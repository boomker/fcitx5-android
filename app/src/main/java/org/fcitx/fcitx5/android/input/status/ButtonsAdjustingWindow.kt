/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.content.ClipData
import android.content.res.Configuration
import android.graphics.Rect
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.IconFont
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.wm.InputWindow
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private const val DRAG_FEEDBACK_DURATION_MS = 240L
private const val DRAG_END_DURATION_MS = 280L

data object ButtonsAdjustingWindow : InputWindow.SimpleInputWindow<ButtonsAdjustingWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val keyBorder by ThemeManager.prefs.keyBorder
    private val currentTheme: Theme
        get() = ThemeManager.activeTheme

    override fun enterAnimation(lastWindow: InputWindow) = null

    override fun exitAnimation(nextWindow: InputWindow) = null

    private enum class Section { Top, Bottom, Available }

    private data class DragPayload(var section: Section, var index: Int, val sourceView: View)

    private val topButtons = mutableListOf<ConfigurableButton>()
    private val bottomButtons = mutableListOf<ConfigurableButton>()
    private val availableButtons = mutableListOf<ConfigurableButton>()
    private var originalTop = listOf<ConfigurableButton>()
    private var originalBottom = listOf<ConfigurableButton>()
    private var dragInProgress = false
    internal val isDragInProgress: Boolean
        get() = dragInProgress

    // Touch-based drag state
    private var draggingPayload: DragPayload? = null
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var initialDownX: Float = 0f
    private var initialDownY: Float = 0f

    private var indicatorSection: Section? = null
    private var indicatorIndex: Int = -1
    private var lastKnownOrientation = Configuration.ORIENTATION_UNDEFINED
    private var lastTopScrollerWidth = -1
    private val feedbackInterpolator = DecelerateInterpolator(1.6f)
    private val topScrollerLayoutListener =
        View.OnLayoutChangeListener { _, _, _, right, _, _, _, oldRight, _ ->
            val newWidth = right
            val oldWidth = oldRight
            if (newWidth <= 0) return@OnLayoutChangeListener
            val orientation = context.resources.configuration.orientation
            val orientationChanged = orientation != lastKnownOrientation
            val widthChanged = newWidth != oldWidth && newWidth != lastTopScrollerWidth
            if (orientationChanged || widthChanged) {
                lastKnownOrientation = orientation
                lastTopScrollerWidth = newWidth
                renderTopButtons()
            }
        }

    private val topContainer by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(KawaiiBarComponent.HEIGHT)
            )
        }
    }

    private val topScroller by lazy {
        HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(
                topContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private val collapseButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_keyboard_arrow_left_24, currentTheme).apply {
            layoutParams = LinearLayout.LayoutParams(
                context.dp(KawaiiBarComponent.HEIGHT),
                context.dp(KawaiiBarComponent.HEIGHT)
            )
            // Use force hide to bypass drag guard - explicit user exit should always work
            setOnClickListener { service.inputView?.forceHideButtonsAdjustingOverlay() }
        }
    }

    private val moreButton by lazy {
        val button = ToolButton(context, R.drawable.ic_baseline_arrow_drop_down_24, currentTheme)
        // Use icon font if configured
        val typeface = FontProviders.resolveTypeface("button_icon_font", null)
        if (typeface != null) {
            button.setIconText(IconFont.KEYBOARD_CLOSE)
        }
        button.apply {
            layoutParams = LinearLayout.LayoutParams(
                context.dp(KawaiiBarComponent.HEIGHT),
                context.dp(KawaiiBarComponent.HEIGHT)
            )
            alpha = 0.45f
        }
    }

    private val topRow by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dp(KawaiiBarComponent.HEIGHT)
            )
            if (!keyBorder) {
                backgroundColor = currentTheme.barColor
            }
            addView(collapseButton)
            addView(topScroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(moreButton)
        }
    }

    private class StatusButtonUi(context: android.content.Context) : LinearLayout(context) {
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            layoutParams = LayoutParams(context.dp(24), context.dp(24))
        }

        val label = TextView(context).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(4)
            }
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            addView(icon)
            addView(label)
            layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(72))
        }

        fun bind(iconRes: Int, text: String, disabled: Boolean, theme: Theme) {
            icon.setImageDrawable(ContextCompat.getDrawable(context, iconRes)?.mutate())
            icon.drawable?.setTint(theme.keyTextColor)
            label.setTextColor(theme.keyTextColor)
            label.text = text
            alpha = if (disabled) 0.45f else 1f
        }
    }

    private class SectionAdapter(
        private val outer: ButtonsAdjustingWindow,
        private val section: Section
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val list: List<ConfigurableButton>
            get() = when (section) {
                Section.Top -> outer.topButtons
                Section.Bottom -> outer.bottomButtons
                Section.Available -> outer.availableButtons
            }

        override fun getItemViewType(position: Int): Int {
            return if (section == Section.Bottom && position >= list.size) 2 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return object : RecyclerView.ViewHolder(StatusButtonUi(parent.context)) {}
        }

        override fun getItemCount(): Int = list.size + if (section == Section.Bottom) 1 else 0

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ui = holder.itemView as StatusButtonUi
            val theme = outer.currentTheme
            // Clear any existing listeners first
            ui.setOnLongClickListener(null)
            ui.isLongClickable = false

            if (position < list.size) {
                val button = list[position]
                val action = ButtonAction.fromId(button.id)
                val icon = action?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
                val label = button.label
                    ?: action?.let { holder.itemView.context.getString(it.defaultLabelRes) }
                    ?: button.id
                ui.bind(icon, label, disabled = false, theme = theme)
                // Enable long click and set listener
                ui.isLongClickable = true
                ui.setOnLongClickListener(View.OnLongClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    // Set drag state immediately
                    outer.dragInProgress = true
                    outer.draggingPayload = DragPayload(section, position, view)
                    view.animate()
                        .alpha(0.72f)
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(DRAG_FEEDBACK_DURATION_MS)
                        .setInterpolator(feedbackInterpolator)
                        .start()
                    // Prevent parent from intercepting touch events during drag
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // Notify drag started state (clear previous indicator)
                    outer.setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
                    outer.updateInsertionIndicator(section, position)
                    true
                })
                // Touch listener starts from ACTION_DOWN to track active pointer
                ui.setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // Record active pointer info
                            outer.activePointerId = event.getPointerId(0)
                            outer.initialDownX = event.rawX
                            outer.initialDownY = event.rawY
                            // Don't handle yet, let long press be detected first
                            false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!outer.dragInProgress) {
                                false
                            } else {
                                // Find the index of our active pointer
                                val pointerIndex = event.findPointerIndex(outer.activePointerId)
                                if (pointerIndex < 0) {
                                    // Pointer no longer valid, end drag
                                    outer.handleDragEnd()
                                    outer.activePointerId = MotionEvent.INVALID_POINTER_ID
                                    true
                                } else {
                                    // Convert to screen coordinates using view location
                                    val location = IntArray(2)
                                    view.getLocationOnScreen(location)
                                    outer.handleDragMove(
                                        location[0] + event.getX(pointerIndex),
                                        location[1] + event.getY(pointerIndex)
                                    )
                                    true
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!outer.dragInProgress) {
                                false
                            } else {
                                outer.handleDragEnd()
                                outer.activePointerId = MotionEvent.INVALID_POINTER_ID
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                                true
                            }
                        }
                        else -> false
                    }
                }
            } else {
                val action = ButtonAction.fromId("input_method_options")
                val icon = action?.defaultIcon ?: R.drawable.ic_baseline_language_24
                val label = action?.let { holder.itemView.context.getString(it.defaultLabelRes) } ?: "IME"
                ui.bind(icon, label, disabled = true, theme = theme)
            }
        }
    }

    private val bottomAdapter by lazy { SectionAdapter(this, Section.Bottom) }
    private val availableAdapter by lazy { SectionAdapter(this, Section.Available) }

    private val bottomList by lazy {
        RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = bottomAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    private val availableList by lazy {
        RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = availableAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            minimumHeight = context.dp(80)
        }
    }

    private fun renderTopButtons() {
        topContainer.removeAllViews()
        val minWidth = context.dp(40)
        val spacing = context.dp(4)
        val available = topScroller.width
        val count = topButtons.size + 1
        val evenWidth = if (available > 0 && count > 0) {
            ((available - count * spacing) / count).coerceAtLeast(0)
        } else {
            0
        }
        val useEven = evenWidth >= minWidth
        topButtons.forEachIndexed { index, button ->
            val action = ButtonAction.fromId(button.id)
            val icon = action?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
            val view = ToolButton(context, icon, currentTheme).apply {
                layoutParams = LinearLayout.LayoutParams(
                    if (useEven) evenWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
                    context.dp(KawaiiBarComponent.HEIGHT)
                ).apply {
                    marginStart = context.dp(2)
                    marginEnd = context.dp(2)
                }
                minimumWidth = minWidth
                image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                setOnLongClickListener(View.OnLongClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    // Set drag state immediately
                    dragInProgress = true
                    draggingPayload = DragPayload(Section.Top, index, view)
                    view.animate()
                        .alpha(0.72f)
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(DRAG_FEEDBACK_DURATION_MS)
                        .setInterpolator(feedbackInterpolator)
                        .start()
                    // Prevent parent from intercepting touch events during drag
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    // Notify drag started state (clear previous indicator)
                    setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
                    updateInsertionIndicator(Section.Top, index)
                    true
                })
                // Touch listener starts from ACTION_DOWN to track active pointer
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // Record active pointer info
                            activePointerId = event.getPointerId(0)
                            initialDownX = event.rawX
                            initialDownY = event.rawY
                            // Don't handle yet, let long press be detected first
                            false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!dragInProgress) {
                                false
                            } else {
                                // Find the index of our active pointer
                                val pointerIndex = event.findPointerIndex(activePointerId)
                                if (pointerIndex < 0) {
                                    // Pointer no longer valid, end drag
                                    handleDragEnd()
                                    activePointerId = MotionEvent.INVALID_POINTER_ID
                                    true
                                } else {
                                    // Get screen coordinates using view location
                                    val location = IntArray(2)
                                    view.getLocationOnScreen(location)
                                    handleDragMove(location[0] + event.getX(pointerIndex), location[1] + event.getY(pointerIndex))
                                    true
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (!dragInProgress) {
                                false
                            } else {
                                handleDragEnd()
                                activePointerId = MotionEvent.INVALID_POINTER_ID
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                                true
                            }
                        }
                        else -> false
                    }
                }
            }
            topContainer.addView(view)
        }
        val topMore = ToolButton(context, R.drawable.ic_baseline_more_horiz_24, currentTheme).apply {
            layoutParams = LinearLayout.LayoutParams(
                if (useEven) evenWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
                context.dp(KawaiiBarComponent.HEIGHT)
            ).apply {
                marginStart = context.dp(2)
                marginEnd = context.dp(2)
            }
            minimumWidth = minWidth
            image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            alpha = 1f
            visibility = View.GONE  // Hide per user request
        }
        topContainer.addView(topMore)
        topContainer.layoutParams = FrameLayout.LayoutParams(
            if (useEven) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            context.dp(KawaiiBarComponent.HEIGHT)
        )
    }

    private fun findTopInsertIndex(x: Float): Int {
        val adjustedX = x + topScroller.scrollX
        for (index in 0 until topContainer.childCount) {
            val child = topContainer.getChildAt(index)
            if (adjustedX < child.left + child.width / 2f) return index
        }
        return topButtons.size
    }

    private fun findRecyclerInsertIndex(
        recycler: RecyclerView,
        listSize: Int,
        x: Float,
        y: Float
    ): Int {
        val child = recycler.findChildViewUnder(x, y) ?: return listSize
        val pos = recycler.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return listSize
        if (pos >= listSize) return listSize
        return if (x > child.left + child.width / 2f || y > child.top + child.height / 2f) {
            (pos + 1).coerceAtMost(listSize)
        } else {
            pos
        }
    }

    private fun move(payload: DragPayload, targetSection: Section, targetIndexRaw: Int): Boolean {
        val sourceList = when (payload.section) {
            Section.Top -> topButtons
            Section.Bottom -> bottomButtons
            Section.Available -> availableButtons
        }
        val targetList = when (targetSection) {
            Section.Top -> topButtons
            Section.Bottom -> bottomButtons
            Section.Available -> availableButtons
        }
        if (payload.index !in sourceList.indices) return false

        if (sourceList === targetList && payload.index == targetIndexRaw.coerceIn(0, targetList.size)) {
            return false
        }

        val moving = sourceList.removeAt(payload.index)
        var targetIndex = targetIndexRaw.coerceIn(0, targetList.size)
        if (sourceList === targetList && payload.index < targetIndex) targetIndex -= 1
        if (sourceList === targetList && payload.index == targetIndex) {
            sourceList.add(payload.index, moving)
            return false
        }
        targetList.add(targetIndex, moving)
        payload.section = targetSection
        payload.index = targetIndex
        renderTopButtons()
        bottomAdapter.notifyDataSetChanged()
        availableAdapter.notifyDataSetChanged()
        updateInsertionIndicator(targetSection, targetIndex)
        return true
    }

    private fun setDragTargetState(topActive: Boolean, bottomActive: Boolean, availableActive: Boolean) {
        topRow.animate()
            .alpha(if (topActive) 0.94f else 1f)
            .setDuration(DRAG_FEEDBACK_DURATION_MS)
            .setInterpolator(feedbackInterpolator)
            .start()
        bottomList.animate()
            .alpha(if (bottomActive) 0.94f else 1f)
            .setDuration(DRAG_FEEDBACK_DURATION_MS)
            .setInterpolator(feedbackInterpolator)
            .start()
        availableList.animate()
            .alpha(if (availableActive) 0.94f else 1f)
            .setDuration(DRAG_FEEDBACK_DURATION_MS)
            .setInterpolator(feedbackInterpolator)
            .start()
    }

    private fun updateInsertionIndicator(section: Section, index: Int) {
        if (indicatorSection == section && indicatorIndex == index) return
        clearInsertionIndicator()
        indicatorSection = section
        indicatorIndex = index
        when (section) {
            Section.Top -> {
                val childIndex = index.coerceAtMost(topContainer.childCount - 1)
                if (childIndex >= 0) {
                    topContainer.getChildAt(childIndex)?.animate()
                        ?.scaleX(1.05f)
                        ?.scaleY(1.05f)
                        ?.alpha(0.9f)
                        ?.setDuration(DRAG_FEEDBACK_DURATION_MS)
                        ?.setInterpolator(feedbackInterpolator)
                        ?.start()
                }
            }

            Section.Bottom -> {
                val childIndex = index.coerceAtMost(bottomButtons.size - 1)
                if (childIndex >= 0) {
                    bottomList.findViewHolderForAdapterPosition(childIndex)?.itemView?.animate()
                        ?.scaleX(1.04f)
                        ?.scaleY(1.04f)
                        ?.alpha(0.9f)
                        ?.setDuration(DRAG_FEEDBACK_DURATION_MS)
                        ?.setInterpolator(feedbackInterpolator)
                        ?.start()
                }
            }

            Section.Available -> {
                val childIndex = index.coerceAtMost(availableButtons.size - 1)
                if (childIndex >= 0) {
                    availableList.findViewHolderForAdapterPosition(childIndex)?.itemView?.animate()
                        ?.scaleX(1.04f)
                        ?.scaleY(1.04f)
                        ?.alpha(0.9f)
                        ?.setDuration(DRAG_FEEDBACK_DURATION_MS)
                        ?.setInterpolator(feedbackInterpolator)
                        ?.start()
                }
            }
        }
    }

    private fun clearInsertionIndicator() {
        when (indicatorSection) {
            Section.Top -> {
                val childIndex = indicatorIndex.coerceAtMost(topContainer.childCount - 1)
                if (childIndex >= 0) {
                    topContainer.getChildAt(childIndex)?.animate()
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.alpha(1f)
                        ?.setDuration(DRAG_FEEDBACK_DURATION_MS)
                        ?.setInterpolator(feedbackInterpolator)
                        ?.start()
                }
            }

            Section.Bottom -> {
                val childIndex = indicatorIndex.coerceAtMost(bottomButtons.size - 1)
                if (childIndex >= 0) {
                    bottomList.findViewHolderForAdapterPosition(childIndex)?.itemView?.animate()
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.alpha(1f)
                        ?.setDuration(DRAG_FEEDBACK_DURATION_MS)
                        ?.setInterpolator(feedbackInterpolator)
                        ?.start()
                }
            }

            Section.Available -> {
                val childIndex = indicatorIndex.coerceAtMost(availableButtons.size - 1)
                if (childIndex >= 0) {
                    availableList.findViewHolderForAdapterPosition(childIndex)?.itemView?.animate()
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.alpha(1f)
                        ?.setDuration(DRAG_FEEDBACK_DURATION_MS)
                        ?.setInterpolator(feedbackInterpolator)
                        ?.start()
                }
            }

            null -> {}
        }
        indicatorSection = null
        indicatorIndex = -1
    }

    private fun loadState() {
        val config = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()?.value
            ?: ButtonsLayoutConfig.default()
        val reservedIds = setOf("more", "input_method_options")
        val configurableIds = ButtonAction.allConfigurableActions
            .map { it.id }
            .filterNot { it in reservedIds }
            .toSet()
        val seen = mutableSetOf<String>()
        topButtons.clear()
        config.kawaiiBarButtons.forEach { button ->
            if (button.id in configurableIds && seen.add(button.id)) topButtons.add(button)
        }
        bottomButtons.clear()
        config.statusAreaButtons.forEach { button ->
            if (button.id != "input_method_options" && button.id in configurableIds && seen.add(button.id)) {
                bottomButtons.add(button)
            }
        }
        availableButtons.clear()
        ButtonAction.allConfigurableActions
            .filterNot { it.id in reservedIds }
            .forEach { action ->
            if (action.id !in seen) availableButtons.add(ConfigurableButton(action.id))
        }
        originalTop = topButtons.toList()
        originalBottom = bottomButtons.toList()
    }

    private fun cleanupDragState(sourceView: View? = null) {
        dragInProgress = false
        // Clear dragging payload state
        val viewToRestore = sourceView ?: draggingPayload?.sourceView
        draggingPayload = null
        setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
        clearInsertionIndicator()
        // Restore view animation
        viewToRestore?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(DRAG_END_DURATION_MS)
            ?.setInterpolator(feedbackInterpolator)
            ?.start()
        // Restore parent's ability to intercept touch events (safety net)
        viewToRestore?.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun saveConfig() {
        val file = ConfigProviders.provider.buttonsLayoutConfigFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val config = ButtonsLayoutConfig(
                kawaiiBarButtons = topButtons.toList(),
                statusAreaButtons = bottomButtons.toList()
            )
            file.writeText(prettyJson.encodeToString(config) + "\n")
        }.onFailure {
            Toast.makeText(context, "${context.getString(R.string.save_failed)}: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val divider by lazy {
        View(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1))
            setBackgroundColor(currentTheme.dividerColor)
            alpha = 0f
        }
    }

    private val sectionDivider by lazy {
        View(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1))
            setBackgroundColor(currentTheme.dividerColor)
            alpha = 0.42f
        }
    }

    private val centerScroll by lazy {
        ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(bottomList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(sectionDivider)
                    addView(availableList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private val contentContainer by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(topRow)
            addView(divider)
            addView(centerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private val root by lazy {
        context.frameLayout {
            background = currentTheme.backgroundDrawable(keyBorder)
            add(contentContainer, lParams(matchParent, matchParent))
            // No touch listener here - drag events are handled by individual item touch listeners
        }
    }

    private fun handleDragMove(x: Float, y: Float) {
        val payload = draggingPayload ?: return
        // Determine which view the touch is over
        val location = IntArray(2)
        topScroller.getLocationOnScreen(location)
        val topScrollerBounds = Rect(
            location[0], location[1],
            location[0] + topScroller.width, location[1] + topScroller.height
        )
        bottomList.getLocationOnScreen(location)
        val bottomListBounds = Rect(
            location[0], location[1],
            location[0] + bottomList.width, location[1] + bottomList.height
        )
        availableList.getLocationOnScreen(location)
        val availableListBounds = Rect(
            location[0], location[1],
            location[0] + availableList.width, location[1] + availableList.height
        )

        when {
            topScrollerBounds.contains(x.toInt(), y.toInt()) -> {
                setDragTargetState(topActive = true, bottomActive = false, availableActive = false)
                val index = findTopInsertIndex(x - topScrollerBounds.left + topScroller.scrollX)
                updateInsertionIndicator(Section.Top, index)
                if (move(payload, Section.Top, index)) {
                    root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            bottomListBounds.contains(x.toInt(), y.toInt()) -> {
                setDragTargetState(topActive = false, bottomActive = true, availableActive = false)
                val index = findRecyclerInsertIndex(bottomList, bottomButtons.size, x - bottomListBounds.left, y - bottomListBounds.top)
                updateInsertionIndicator(Section.Bottom, index)
                if (move(payload, Section.Bottom, index)) {
                    root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            availableListBounds.contains(x.toInt(), y.toInt()) -> {
                setDragTargetState(topActive = false, bottomActive = false, availableActive = true)
                val index = findRecyclerInsertIndex(availableList, availableButtons.size, x - availableListBounds.left, y - availableListBounds.top)
                updateInsertionIndicator(Section.Available, index)
                if (move(payload, Section.Available, index)) {
                    root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            else -> {
                // Not over any drop target
                setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
            }
        }
    }

    private fun handleDragEnd() {
        // cleanupDragState will clear draggingPayload and restore source view animation
        cleanupDragState()
    }

    private fun refreshThemeUi() {
        val theme = currentTheme
        root.background = theme.backgroundDrawable(keyBorder)
        if (!keyBorder) {
            topRow.backgroundColor = theme.barColor
        } else {
            topRow.background = null
        }
        divider.setBackgroundColor(theme.dividerColor)
        sectionDivider.setBackgroundColor(theme.dividerColor)
        val iconTint = theme.altKeyTextColor
        collapseButton.image.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        collapseButton.setPressHighlightColor(theme.keyPressHighlightColor)
        moreButton.image.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        moreButton.setPressHighlightColor(theme.keyPressHighlightColor)
    }

    fun updateOverlayInsets(sidePadding: Int, bottomPadding: Int) {
        root.setPadding(sidePadding, 0, sidePadding, bottomPadding)
    }

    override fun onCreateView(): View {
        (root.parent as? ViewGroup)?.removeView(root)
        return root
    }

    override fun onAttached() {
        val currentOrientation = context.resources.configuration.orientation
        val orientationChanged = currentOrientation != lastKnownOrientation
        lastKnownOrientation = currentOrientation
        refreshThemeUi()
        loadState()
        if (topScroller.width > 0) {
            lastTopScrollerWidth = topScroller.width
            renderTopButtons()
        } else {
            topScroller.post {
                lastTopScrollerWidth = topScroller.width
                renderTopButtons()
            }
        }
        if (orientationChanged) {
            topScroller.requestLayout()
        }
        bottomAdapter.notifyDataSetChanged()
        availableAdapter.notifyDataSetChanged()
        topScroller.removeOnLayoutChangeListener(topScrollerLayoutListener)
        topScroller.addOnLayoutChangeListener(topScrollerLayoutListener)
        // Note: Using touch-based drag instead of framework DragEvent
        // No drag listeners needed anymore
    }

    override fun onDetached() {
        // Clean up drag state if detached during drag
        cleanupDragState()
        topScroller.removeOnLayoutChangeListener(topScrollerLayoutListener)
        if (topButtons != originalTop || bottomButtons != originalBottom) {
            saveConfig()
            Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }
}
