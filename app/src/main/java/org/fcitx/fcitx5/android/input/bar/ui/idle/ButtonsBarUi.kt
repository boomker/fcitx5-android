/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import androidx.annotation.DrawableRes
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.action.ButtonAction
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

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    private fun toolButton(@DrawableRes icon: Int) = ToolButton(ctx, icon, theme).also {
        val size = ctx.dp(40)
        root.addView(it, FlexboxLayout.LayoutParams(size, size))
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
        root.removeAllViews()
        buttons.forEach { config ->
            val iconRes = getIconResForButton(config.id, config.icon)
            val button = toolButton(iconRes).apply {
                contentDescription = config.label ?: getDefaultLabel(config.id)
                tag = config.id
                // Apply existing click listener if any
                clickListeners[config.id]?.let { setOnClickListener(it) }
                // Apply existing long click listener if any
                longClickListeners[config.id]?.let { setOnLongClickListener(it) }
            }
            buttonMap[config.id] = button
        }
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
}
