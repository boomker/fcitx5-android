/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp

/**
 * A custom layout manager for Kawaii Bar that supports:
 * 1. Even distribution when buttons have enough space
 * 2. Horizontal scrolling while preserving a minimum button width (40dp)
 */
class KawaiiBarLayout(context: Context) : LinearLayoutManager(context, HORIZONTAL, false) {

    // Minimum button width: 40dp to match icon size
    val minButtonWidth: Int = context.dp(40)
    private val buttonSpacing: Int = context.dp(2) * 2 // Match each margin's pixel rounding.

    var isEvenDistributionMode = false
        private set

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
        isEvenDistributionMode = true
    }

    /**
     * Configure the layout for scroll mode
     */
    fun setScrollMode() {
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
        // Rebind after measurement so existing children also receive new widths.
        post {
            val currentAdapter = adapter ?: return@post
            val parentWidth = width - paddingLeft - paddingRight
            if (parentWidth <= 0) return@post
            val shouldDistribute = kawaiiBarLayout.shouldDistributeEvenly(currentAdapter.itemCount, parentWidth)

            if (shouldDistribute) {
                kawaiiBarLayout.setEvenDistributionMode()
                kawaiiBarLayout.scrollToPositionWithOffset(0, 0)
            } else {
                kawaiiBarLayout.setScrollMode()
            }
            isHorizontalScrollBarEnabled = !shouldDistribute
            // All widths changed; a payload lets RecyclerView reuse the existing holders.
            currentAdapter.notifyItemRangeChanged(0, currentAdapter.itemCount, Unit)
        }
    }

    /**
     * Check if the layout is in even distribution mode
     */
    fun isEvenDistributionMode(): Boolean {
        return kawaiiBarLayout.isEvenDistributionMode
    }
}
