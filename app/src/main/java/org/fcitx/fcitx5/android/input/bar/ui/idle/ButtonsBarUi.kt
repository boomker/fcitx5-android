/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.action.ButtonAction
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    private var buttons: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    @DrawableRes
    private val floatingOffIcon = R.drawable.ic_floating_toggle_24

    @DrawableRes
    private val floatingOnIcon = R.drawable.ic_baseline_keyboard_24

    override val root = view(::RecyclerView) {
        layoutManager = FlexboxLayoutManager(ctx, RecyclerView.HORIZONTAL).apply {
            alignItems = AlignItems.CENTER
            // Use FLEX_START to let buttons flow naturally and scroll when needed
            justifyContent = JustifyContent.FLEX_START
            // Disable wrapping to ensure single row horizontal scrolling
            flexWrap = FlexWrap.NOWRAP
        }
        // Disable nested scrolling to prevent conflicts with parent touch handling
        isNestedScrollingEnabled = false
        // Ensure RecyclerView can scroll horizontally
        overScrollMode = View.OVER_SCROLL_NEVER
        // Set fixed height to match KawaiiBar height
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(KawaiiBarComponent.HEIGHT)
        )
    }

    private val adapter = ButtonsBarAdapter()

    // Map to store button references by ID
    private val buttonMap = mutableMapOf<String, ToolButton>()

    // Click listeners for each button
    private val clickListeners = mutableMapOf<String, View.OnClickListener>()
    private val longClickListeners = mutableMapOf<String, View.OnLongClickListener>()

    init {
        (root as RecyclerView).adapter = adapter
        buildButtons()
    }

    private fun buildButtons() {
        buttonMap.clear()
        adapter.notifyDataSetChanged()
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
        buttonMap["floating_toggle"]?.setIcon(if (isFloating) floatingOnIcon else floatingOffIcon)
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
                layoutParams = FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    // Set minimum width to prevent button from shrinking too small
                    // Using 32dp as minimum to ensure icon is still clearly visible
                    minWidth = ctx.dp(32)
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
            val config = buttons[position]
            // Update button state if needed
        }

        override fun getItemViewType(position: Int): Int = position
    }
}
