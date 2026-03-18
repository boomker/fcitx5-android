/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a configurable button on Kawaii Bar or Status Area.
 */
@Serializable
data class ConfigurableButton(
    /**
     * Unique identifier for the button action.
     * Examples: "undo", "redo", "cursor_move", "floating_toggle", "clipboard", "more",
     *           "theme", "input_method_options", "reload_config", "virtual_keyboard", "one_handed_keyboard"
     */
    @SerialName("id")
    val id: String,
    
    /**
     * Optional: Icon resource name (without extension) to use for this button.
     * If null, uses default icon for the action.
     * Examples: "ic_baseline_undo_24", "ic_clipboard", "ic_cursor_move"
     */
    @SerialName("icon")
    val icon: String? = null,
    
    /**
     * Optional: Custom label for accessibility/content description.
     * If null, uses default label for the action.
     */
    @SerialName("label")
    val label: String? = null,
    
    /**
     * Optional: Long press action, if different from short press.
     * For buttons that support different long-press behavior.
     * Examples: "floating_menu" (for floating_toggle long press)
     */
    @SerialName("longPressAction")
    val longPressAction: String? = null
)

/**
 * Unified configuration for both Kawaii Bar and Status Area buttons layout.
 * Stored in a single JSON file for easier management.
 */
@Serializable
data class ButtonsLayoutConfig(
    /**
     * List of buttons to display on Kawaii Bar, in order.
     * Maximum 6 buttons recommended for visual balance.
     * Note: 'more' button is always added automatically and should not be in this list.
     */
    @SerialName("kawaiiBarButtons")
    val kawaiiBarButtons: List<ConfigurableButton>,

    /**
     * List of buttons to display in Status Area, in order.
     * Displayed in a 4-column grid layout.
     * Note: 'input_method_options' button is always added automatically at the end and should not be in this list.
     */
    @SerialName("statusAreaButtons")
    val statusAreaButtons: List<ConfigurableButton>
) {
    companion object {
        /**
         * Default unified button configuration.
         */
        fun default(): ButtonsLayoutConfig = ButtonsLayoutConfig(
            kawaiiBarButtons = listOf(
                ConfigurableButton("undo"),
                ConfigurableButton("redo"),
                ConfigurableButton("cursor_move"),
                ConfigurableButton("floating_toggle"),
                ConfigurableButton("clipboard")
            ),
            // Note: input_method_options is always added automatically at the end of Status Area
            statusAreaButtons = listOf(
                ConfigurableButton("theme"),
                ConfigurableButton("reload_config"),
                ConfigurableButton("virtual_keyboard"),
                ConfigurableButton("one_handed_keyboard")
            )
        )
    }
}

/**
 * Configuration for Kawaii Bar buttons layout.
 * @deprecated Use [ButtonsLayoutConfig] instead
 */
@Deprecated("Use ButtonsLayoutConfig instead", ReplaceWith("ButtonsLayoutConfig"))
@Serializable
data class KawaiiBarButtonsConfig(
    /**
     * List of buttons to display on Kawaii Bar, in order.
     * Maximum 6 buttons recommended for visual balance.
     */
    @SerialName("buttons")
    val buttons: List<ConfigurableButton>
) {
    companion object {
        /**
         * Default Kawaii Bar button configuration.
         * Note: 'more' button is always added automatically and is not part of this default config.
         */
        @Deprecated("Use ButtonsLayoutConfig.default() instead")
        fun default(): KawaiiBarButtonsConfig = KawaiiBarButtonsConfig(
            buttons = listOf(
                ConfigurableButton("undo"),
                ConfigurableButton("redo"),
                ConfigurableButton("cursor_move"),
                ConfigurableButton("floating_toggle"),
                ConfigurableButton("clipboard")
            )
        )
    }
}

/**
 * Configuration for Status Area buttons layout.
 * @deprecated Use [ButtonsLayoutConfig] instead
 */
@Deprecated("Use ButtonsLayoutConfig instead", ReplaceWith("ButtonsLayoutConfig"))
@Serializable
data class StatusAreaButtonsConfig(
    /**
     * List of buttons to display in Status Area, in order.
     * Displayed in a 4-column grid layout.
     */
    @SerialName("buttons")
    val buttons: List<ConfigurableButton>
) {
    companion object {
        /**
         * Default Status Area button configuration.
         */
        @Deprecated("Use ButtonsLayoutConfig.default() instead")
        fun default(): StatusAreaButtonsConfig = StatusAreaButtonsConfig(
            // Note: input_method_options is always added automatically at the end of Status Area
            buttons = listOf(
                ConfigurableButton("theme"),
                ConfigurableButton("reload_config"),
                ConfigurableButton("virtual_keyboard"),
                ConfigurableButton("one_handed_keyboard")
            )
        )
    }
}
