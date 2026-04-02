/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.content.ClipData
import android.content.res.Configuration
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
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
            setOnClickListener { service.inputView?.hideButtonsAdjustingOverlay() }
        }
    }

    private val moreButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_arrow_drop_down_24, currentTheme).apply {
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
            if (position < list.size) {
                val button = list[position]
                val action = ButtonAction.fromId(button.id)
                val icon = action?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
                val label = button.label
                    ?: action?.let { holder.itemView.context.getString(it.defaultLabelRes) }
                    ?: button.id
                ui.bind(icon, label, disabled = false, theme = theme)
                ui.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    it.animate()
                        .alpha(0.72f)
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(DRAG_FEEDBACK_DURATION_MS)
                        .setInterpolator(feedbackInterpolator)
                        .start()
                    val payload = DragPayload(section, position, it)
                    it.startDragAndDrop(
                        ClipData.newPlainText("button", section.name.lowercase()),
                        View.DragShadowBuilder(it),
                        payload,
                        0
                    )
                }
            } else {
                val action = ButtonAction.fromId("input_method_options")
                val icon = action?.defaultIcon ?: R.drawable.ic_baseline_language_24
                val label = action?.let { holder.itemView.context.getString(it.defaultLabelRes) } ?: "IME"
                ui.bind(icon, label, disabled = true, theme = theme)
                ui.setOnLongClickListener(null)
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
                setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    it.animate()
                        .alpha(0.72f)
                        .scaleX(0.97f)
                        .scaleY(0.97f)
                        .setDuration(DRAG_FEEDBACK_DURATION_MS)
                        .setInterpolator(feedbackInterpolator)
                        .start()
                    val payload = DragPayload(Section.Top, index, it)
                    it.startDragAndDrop(
                        ClipData.newPlainText("button", "top"),
                        View.DragShadowBuilder(it),
                        payload,
                        0
                    )
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

    private val dragListener = View.OnDragListener { v, event ->
        val payload = event.localState as? DragPayload ?: return@OnDragListener true
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                dragInProgress = true
                setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
                updateInsertionIndicator(payload.section, payload.index)
            }

            DragEvent.ACTION_DRAG_ENTERED -> {
                when (v) {
                    topScroller -> setDragTargetState(topActive = true, bottomActive = false, availableActive = false)
                    bottomList -> setDragTargetState(topActive = false, bottomActive = true, availableActive = false)
                    availableList -> setDragTargetState(topActive = false, bottomActive = false, availableActive = true)
                }
            }

            DragEvent.ACTION_DRAG_EXITED -> {
                setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
            }

            DragEvent.ACTION_DRAG_LOCATION -> {
                val index = when (v) {
                    topScroller -> findTopInsertIndex(event.x)
                    bottomList -> findRecyclerInsertIndex(bottomList, bottomButtons.size, event.x, event.y)
                    availableList -> findRecyclerInsertIndex(availableList, availableButtons.size, event.x, event.y)
                    else -> payload.index
                }
                when (v) {
                    topScroller -> updateInsertionIndicator(Section.Top, index)
                    bottomList -> updateInsertionIndicator(Section.Bottom, index)
                    availableList -> updateInsertionIndicator(Section.Available, index)
                    else -> {}
                }
                val changed = when (v) {
                    topScroller -> move(payload, Section.Top, index)
                    bottomList -> move(payload, Section.Bottom, index)
                    availableList -> move(payload, Section.Available, index)
                    else -> false
                }
                if (changed) {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }

            DragEvent.ACTION_DROP -> {
                // Final drop is already previewed during ACTION_DRAG_LOCATION.
                setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
                clearInsertionIndicator()
            }

            DragEvent.ACTION_DRAG_ENDED -> {
                dragInProgress = false
                setDragTargetState(topActive = false, bottomActive = false, availableActive = false)
                clearInsertionIndicator()
                payload.sourceView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(DRAG_END_DURATION_MS)
                    .setInterpolator(feedbackInterpolator)
                    .start()
            }
        }
        true
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
        }
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
        topScroller.setOnDragListener(dragListener)
        bottomList.setOnDragListener(dragListener)
        availableList.setOnDragListener(dragListener)
    }

    override fun onDetached() {
        topScroller.removeOnLayoutChangeListener(topScrollerLayoutListener)
        topScroller.setOnDragListener(null)
        bottomList.setOnDragListener(null)
        availableList.setOnDragListener(null)
        if (topButtons != originalTop || bottomButtons != originalBottom) {
            saveConfig()
            Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }
}
