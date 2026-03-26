/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import splitties.dimensions.dp

/**
 * A custom layout manager for Kawaii Bar that supports:
 * 1. Even distribution (SPACE_AROUND-like) when buttons have enough space
 * 2. Horizontal scrolling when buttons are compressed below minimum width (40dp)
 */
class KawaiiBarLayout(context: Context) : FlexboxLayoutManager(context, RecyclerView.HORIZONTAL) {

    // Minimum button width: 40dp to match icon size
    val minButtonWidth: Int = context.dp(40)
    private val buttonSpacing: Int = context.dp(4) // 2dp margin on each side

    var isEvenDistributionMode = false
        private set

    init {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.FLEX_START
        flexWrap = FlexWrap.NOWRAP
    }

    /**
     * Calculate if buttons should be evenly distributed or scrollable
     * @param childCount Number of buttons
     * @param parentWidth Available width in parent container
     * @return true if buttons can be evenly distributed, false if scrolling is needed
     */
    fun shouldDistributeEvenly(childCount: Int, parentWidth: Int): Boolean {
        if (childCount == 0) return true

        // Calculate minimum required width for all buttons (including margins)
        // Each button has 2dp margin on each side = 4dp total per button
        val minRequiredWidth = childCount * (minButtonWidth + buttonSpacing)

        // If minimum required width is less than parent width, we can distribute evenly
        // Otherwise, enable scrolling
        return minRequiredWidth <= parentWidth
    }

    /**
     * Configure the layout for even distribution mode
     */
    fun setEvenDistributionMode() {
        justifyContent = JustifyContent.SPACE_AROUND
        isEvenDistributionMode = true
    }

    /**
     * Configure the layout for scroll mode
     */
    fun setScrollMode() {
        justifyContent = JustifyContent.FLEX_START
        isEvenDistributionMode = false
    }

    /**
     * Calculate the ideal button width for even distribution
     * @param childCount Number of buttons
     * @param parentWidth Available width in parent container
     * @return Ideal width for each button in pixels (may be less than minButtonWidth)
     */
    fun calculateEvenDistributedWidth(childCount: Int, parentWidth: Int): Int {
        if (childCount == 0) return 0

        // Each button has marginStart=2dp and marginEnd=2dp
        // Total horizontal space per button = button width + 4dp (2dp on each side)
        // Total margin space = 4dp * childCount
        val totalMarginSpace = buttonSpacing * childCount

        // Available width for buttons
        val availableWidth = parentWidth - totalMarginSpace

        // Divide evenly among buttons
        return availableWidth / childCount
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        super.onLayoutChildren(recycler, state)
    }
}

/**
 * A custom RecyclerView for Kawaii Bar buttons with smart layout behavior
 */
class KawaiiBarRecyclerView(context: Context) : RecyclerView(context) {

    private val kawaiiBarLayout = KawaiiBarLayout(context)

    init {
        layoutManager = kawaiiBarLayout
        // Disable nested scrolling to prevent conflicts with parent touch handling
        isNestedScrollingEnabled = false
        // Ensure RecyclerView can scroll horizontally
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Update layout mode when size changes
        updateLayoutMode()
    }

    /**
     * Update the layout mode based on current button count and available space
     */
    internal fun updateLayoutMode() {
        val adapter = adapter ?: return
        val childCount = adapter.itemCount

        // Post to ensure layout measurement is complete
        post {
            val parentWidth = width
            if (parentWidth <= 0 || childCount == 0) return@post

            // Calculate ideal width for even distribution
            val idealWidth = kawaiiBarLayout.calculateEvenDistributedWidth(childCount, parentWidth)

            // If ideal width is less than minimum button width, use scroll mode
            val shouldDistribute = idealWidth >= kawaiiBarLayout.minButtonWidth

            if (shouldDistribute) {
                kawaiiBarLayout.setEvenDistributionMode()
                // Disable horizontal scrolling when in even distribution mode
                isHorizontalScrollBarEnabled = false
            } else {
                kawaiiBarLayout.setScrollMode()
                // Enable horizontal scrolling when buttons need more space
                isHorizontalScrollBarEnabled = true
            }
        }
    }

    /**
     * Check if the layout is in even distribution mode
     */
    fun isEvenDistributionMode(): Boolean {
        return kawaiiBarLayout.isEvenDistributionMode
    }
}
