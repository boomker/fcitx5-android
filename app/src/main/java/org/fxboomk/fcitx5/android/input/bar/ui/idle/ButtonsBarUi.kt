/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
@file:Suppress("NotifyDataSetChanged")
package org.fxboomk.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.action.ButtonAction
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ButtonIconSpec
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.fxboomk.fcitx5.android.input.config.KeyIconIds
import org.fxboomk.fcitx5.android.input.config.KeyIconThemeStore
import org.fxboomk.fcitx5.android.input.bar.ToolbarVoiceInputPolicy
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val theme: Theme,
    buttons: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    private var buttons = buttons.filter { it.id != "more" && it.id != "hide_keyboard" }

    private val onKeyIconThemeChanged: () -> Unit = {
        root.post { buildButtons() }
    }

    @DrawableRes
    private val floatingIcon = R.drawable.ic_floating_toggle_24

    override val root = view(::KawaiiBarRecyclerView) {
        // Set fixed height to match KawaiiBar height
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(KawaiiBarComponent.configuredHeightDp())
        )
    }

    // Map to store button references by ID
    private val buttonMap = mutableMapOf<String, ToolButton>()
    private val buttonActiveStates = mutableMapOf<String, Boolean>()
    private data class IconSpec(
        @DrawableRes val resource: Int,
        val glyph: String? = null,
        val drawable: android.graphics.drawable.Drawable? = null
    )

    private val voiceButtonIcons = mutableMapOf<String, Pair<IconSpec, IconSpec>>()

    private fun applyIcon(button: ToolButton, spec: IconSpec) {
        when {
            spec.drawable != null -> button.setIconDrawable(spec.drawable)
            spec.glyph != null -> button.setIconText(spec.glyph)
            else -> button.setIcon(spec.resource)
        }
    }

    // Click listeners for each button
    private val clickListeners = mutableMapOf<String, View.OnClickListener>()
    private val longClickListeners = mutableMapOf<String, View.OnLongClickListener>()

    init {
        buildButtons()
        KeyIconThemeStore.addOnChangedListener(onKeyIconThemeChanged)
    }

    fun dispose() {
        KeyIconThemeStore.removeOnChangedListener(onKeyIconThemeChanged)
    }

    private fun buildButtons() {
        buttonMap.clear()
        voiceButtonIcons.clear()
        buttonActiveStates.keys.retainAll(buttons.map { it.id }.toSet())
        val recyclerView = root
        // Recreate adapter to ensure clean state
        recyclerView.stopScroll()
        recyclerView.adapter = ButtonsBarAdapter()
        (recyclerView.layoutManager as KawaiiBarLayout).scrollToPositionWithOffset(0, 0)
        // Update layout mode immediately (width should be available from previous layout)
        recyclerView.updateLayoutMode()
    }

    fun updateConfig(newButtons: List<ConfigurableButton>) {
        val filteredButtons = newButtons.filter { it.id != "more" && it.id != "hide_keyboard" }
        if (filteredButtons != buttons) {
            buttons = filteredButtons
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
        val fallback = ButtonAction.fromId(buttonId)?.defaultIcon
            ?: R.drawable.ic_baseline_more_horiz_24
        // Voice theme icons can be SVG/glyph values; rendering is handled by
        // resolveDrawableObject() in onCreateViewHolder below.
        if (ToolbarVoiceInputPolicy.usesKeyIconTheme(ConfigurableButton(buttonId, icon = customIcon))) {
            return fallback
        }
        return ButtonIconSpec.drawableResource(ctx, customIcon, fallback)
    }

    private fun resolveButtonIcon(config: ConfigurableButton, value: String?, fallback: Int): IconSpec {
        if (ToolbarVoiceInputPolicy.usesKeyIconTheme(config)) {
            return IconSpec(
                resource = fallback,
                drawable = KeyIconThemeStore.resolveDrawableObject(ctx, KeyIconIds.VOICE_INPUT, fallback)
            )
        }
        val glyph = ButtonIconSpec.glyph(value)
        if (glyph != null) return IconSpec(resource = fallback, glyph = glyph)
        return IconSpec(
            resource = ButtonIconSpec.drawableResource(ctx, value, fallback),
            drawable = ButtonIconSpec.drawable(ctx, value, fallback)
        )
    }

    private fun resolveVoiceListeningIcon(config: ConfigurableButton, fallback: Int): IconSpec {
        val configured = config.activeIcon?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val glyph = ButtonIconSpec.glyph(configured)
            if (glyph != null) return IconSpec(R.drawable.ic_voice_input_active_24, glyph = glyph)
            return IconSpec(
                resource = ButtonIconSpec.drawableResource(ctx, configured, R.drawable.ic_voice_input_active_24),
                drawable = ButtonIconSpec.drawable(ctx, configured, R.drawable.ic_voice_input_active_24)
            )
        }
        return IconSpec(
            resource = R.drawable.ic_voice_input_active_24,
            drawable = AppCompatResources.getDrawable(ctx, R.drawable.ic_voice_input_active_24)
                ?: AppCompatResources.getDrawable(ctx, fallback)
        )
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
        buttonActiveStates["floating_toggle"] = isFloating
        buttonMap["floating_toggle"]?.setActive(isFloating)
    }

    fun setOneHandKeyboardState(isOneHanded: Boolean) {
        buttonActiveStates["one_handed_keyboard"] = isOneHanded
        buttonMap["one_handed_keyboard"]?.setActive(isOneHanded)
    }

    /**
     * Update all buttons' active state based on their ButtonAction.isActive() method.
     */
    fun updateButtonsState(service: FcitxInputMethodService) {
        ButtonAction.allConfigurableActions.forEach { action ->
            if (buttons.none { it.id == action.id }) return@forEach
            val active = action.isActive(service)
            buttonActiveStates[action.id] = active
            val button = buttonMap[action.id] ?: return@forEach
            button.setActive(active)
            if (action.id == ToolbarVoiceInputPolicy.VOICE_INPUT_ID) {
                val (idle, listening) = voiceButtonIcons[action.id] ?: return@forEach
                applyIcon(button, if (active) listening else idle)
            }
        }
    }

    private inner class ButtonsBarAdapter : RecyclerView.Adapter<ButtonsBarAdapter.ButtonViewHolder>() {

        inner class ButtonViewHolder(val button: ToolButton) : RecyclerView.ViewHolder(button)

        override fun getItemCount(): Int = buttons.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ButtonViewHolder {
            val config = buttons[viewType]
            val iconRes = getIconResForButton(config.id, config.icon)
            val idleIcon = resolveButtonIcon(config, config.icon, iconRes)
            val listeningIcon = if (config.id == ToolbarVoiceInputPolicy.VOICE_INPUT_ID) {
                resolveVoiceListeningIcon(config, iconRes)
            } else {
                idleIcon
            }
            val button = ToolButton(ctx, iconRes, theme).apply {
                applyIcon(this, idleIcon)
                contentDescription = config.label ?: getDefaultLabel(config.id)
                tag = config.id
                // Ensure button always fills KawaiiBar height
                minimumHeight = ctx.dp(KawaiiBarComponent.configuredHeightDp())
                layoutParams = RecyclerView.LayoutParams(
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
            if (config.id == ToolbarVoiceInputPolicy.VOICE_INPUT_ID) {
                voiceButtonIcons[config.id] = idleIcon to listeningIcon
                if (VoiceInputProviderManager.isActive()) {
                    applyIcon(button, listeningIcon)
                    button.setActive(true)
                }
            }
            return ButtonViewHolder(button)
        }

        override fun onBindViewHolder(holder: ButtonViewHolder, position: Int) {
            val recyclerView = root
            val kawaiiBarLayout = recyclerView.layoutManager as KawaiiBarLayout
            val parentWidth = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
            val params = holder.button.layoutParams as RecyclerView.LayoutParams
            params.width = kawaiiBarLayout.calculateEvenDistributedWidth(itemCount, parentWidth)
                .coerceAtLeast(kawaiiBarLayout.minButtonWidth)

            val buttonId = buttons[position].id
            val active = if (buttonId == ToolbarVoiceInputPolicy.VOICE_INPUT_ID) {
                VoiceInputProviderManager.isActive()
            } else {
                buttonActiveStates[buttonId] ?: false
            }
            holder.button.setActive(active)
            voiceButtonIcons[buttonId]?.let { (idle, listening) ->
                applyIcon(holder.button, if (active) listening else idle)
            }
        }

        override fun getItemViewType(position: Int): Int {
            // Return position as view type since we recreate adapter on config changes
            return position
        }
    }
}
