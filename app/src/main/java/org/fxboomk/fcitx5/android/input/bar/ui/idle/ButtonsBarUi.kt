/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.action.ButtonAction
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    private var buttons: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    @DrawableRes
    private val floatingIcon = R.drawable.ic_floating_toggle_24

    override val root = view(::KawaiiBarRecyclerView) {
        // Set fixed height to match KawaiiBar height
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(KawaiiBarComponent.HEIGHT)
        )
    }

    // Map to store button references by ID
    private val buttonMap = mutableMapOf<String, ToolButton>()

    // Click listeners for each button
    private val clickListeners = mutableMapOf<String, View.OnClickListener>()
    private val longClickListeners = mutableMapOf<String, View.OnLongClickListener>()

    init {
        buildButtons()
    }

    private fun buildButtons() {
        buttonMap.clear()
        val recyclerView = root as KawaiiBarRecyclerView
        // Recreate adapter to ensure clean state
        recyclerView.adapter = ButtonsBarAdapter()
        // Update layout mode immediately (width should be available from previous layout)
        recyclerView.updateLayoutMode()
    }

    fun updateConfig(newButtons: List<ConfigurableButton>) {
        if (newButtons != buttons) {
            buttons = newButtons
            buildButtons()
        }
    }

    fun setOnClickListener(buttonId: String, listener: View.OnClickListener?) {
        if (listener != null) {
            clickListeners[buttonId] = listener
        } else {
            clickListeners.remove(buttonId)
        }
        buttonMap[buttonId]?.setOnClickListener(listener)
    }

    fun setOnLongClickListener(buttonId: String, listener: View.OnLongClickListener?) {
        if (listener != null) {
            longClickListeners[buttonId] = listener
        } else {
            longClickListeners.remove(buttonId)
        }
        buttonMap[buttonId]?.setOnLongClickListener(listener)
    }

    @DrawableRes
    private fun getIconResForButton(buttonId: String, customIcon: String?): Int {
        // If custom icon is specified, try to find it
        if (customIcon != null) {
            // Try to get resource ID from name
            val resId = ctx.resources.getIdentifier(customIcon, "drawable", ctx.packageName)
            if (resId != 0) return resId
        }

        // Return default icon from ButtonAction
        return ButtonAction.fromId(buttonId)?.defaultIcon ?: R.drawable.ic_baseline_more_horiz_24
    }

    private fun getDefaultLabel(buttonId: String): String {
        // Return default label from ButtonAction
        return ButtonAction.fromId(buttonId)?.let { action ->
            ctx.getString(action.defaultLabelRes)
        } ?: when (buttonId) {
            "floating_toggle" -> ctx.getString(R.string.floating_keyboard)
            else -> buttonId
        }
    }

    fun getButton(buttonId: String): ToolButton? = buttonMap[buttonId]

    fun setFloatingState(isFloating: Boolean) {
        buttonMap["floating_toggle"]?.setActive(isFloating)
    }

    fun setOneHandKeyboardState(isOneHanded: Boolean) {
        buttonMap["one_handed_keyboard"]?.setActive(isOneHanded)
    }

    /**
     * Update all buttons' active state based on their ButtonAction.isActive() method.
     */
    fun updateButtonsState(service: FcitxInputMethodService) {
        ButtonAction.allConfigurableActions.forEach { action ->
            buttonMap[action.id]?.setActive(action.isActive(service))
        }
    }

    private inner class ButtonsBarAdapter : RecyclerView.Adapter<ButtonsBarAdapter.ButtonViewHolder>() {

        inner class ButtonViewHolder(val button: ToolButton) : RecyclerView.ViewHolder(button)

        override fun getItemCount(): Int = buttons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
            val config = buttons[viewType]
            val iconRes = getIconResForButton(config.id, config.icon)
            val button = ToolButton(ctx, iconRes, theme).apply {
                contentDescription = config.label ?: getDefaultLabel(config.id)
                tag = config.id
                // Ensure button always fills KawaiiBar height
                minimumHeight = ctx.dp(KawaiiBarComponent.HEIGHT)
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    // Add horizontal margin for spacing between buttons
                    marginStart = ctx.dp(2)
                    marginEnd = ctx.dp(2)
                }

                // Apply click listeners
                clickListeners[config.id]?.let { setOnClickListener(it) }
                longClickListeners[config.id]?.let { setOnLongClickListener(it) }
            }
            buttonMap[config.id] = button
            return ButtonViewHolder(button)
        }

        override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
            val recyclerView = root as KawaiiBarRecyclerView
            val kawaiiBarLayout = recyclerView.layoutManager as KawaiiBarLayout
            val parentWidth = recyclerView.width
            val childCount = itemCount
            val button = holder.button

            val params = holder.button.layoutParams as FlexboxLayoutManager.LayoutParams

            // Calculate ideal width for even distribution
            if (parentWidth > 0 && childCount > 0) {
                val idealWidth = kawaiiBarLayout.calculateEvenDistributedWidth(childCount, parentWidth)

                // Switch to scroll mode if ideal width is less than minimum
                if (idealWidth < kawaiiBarLayout.minButtonWidth) {
                    if (kawaiiBarLayout.isEvenDistributionMode) {
                        kawaiiBarLayout.setScrollMode()
                        recyclerView.isHorizontalScrollBarEnabled = true
                        // Request relayout on next frame
                        if (position == 0) {
                            recyclerView.post {
                                notifyDataSetChanged()
                            }
                            return
                        }
                    }
                    // Scroll mode: WRAP_CONTENT with minimum width ensures buttons don't shrink
                    params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    params.minWidth = kawaiiBarLayout.minButtonWidth
                    button.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                } else {
                    // Switch to even distribution mode if not already
                    if (!kawaiiBarLayout.isEvenDistributionMode) {
                        kawaiiBarLayout.setEvenDistributionMode()
                        recyclerView.isHorizontalScrollBarEnabled = false
                        if (position == 0) {
                            recyclerView.post {
                                notifyDataSetChanged()
                            }
                            return
                        }
                    }
                    // Even distribution mode: Set fixed width for each button
                    params.width = idealWidth
                    params.minWidth = 0
                    button.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
            } else {
                // Fallback to scroll mode
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                params.minWidth = kawaiiBarLayout.minButtonWidth
            }
        }

        override fun getItemViewType(position: Int): Int {
            // Return position as view type since we recreate adapter on config changes
            return position
        }
    }
}
