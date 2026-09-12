/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
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
     *           "theme_toggle", "language_switch", "theme", "input_method_options", "reload_config",
     *           "virtual_keyboard", "one_handed_keyboard"
     */
    @SerialName("id")
    val id: String,

    /**
     * Optional: Drawable resource name or iconfont Unicode code point.
     * If null, uses default icon for the action.
     * Examples: "ic_baseline_undo_24", "ic_clipboard", "font:E141", "svg:<svg ...>"
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
     * Long-press behavior is currently controlled by the button action itself.
     */
    @SerialName("longPressAction")
    val longPressAction: String? = null
)

/** Shared parsing rules for drawable names and iconfont code points. */
object ButtonIconSpec {
    private val codePointPattern = Regex("(?:font:)?([0-9A-Fa-f]{4,6})")

    fun codePoint(value: String?): Int? {
        val match = value?.trim()?.let(codePointPattern::matchEntire) ?: return null
        return match.groupValues[1].toIntOrNull(16)?.takeIf {
            it in 0..0x10FFFF && it !in 0xD800..0xDFFF
        }
    }

    fun glyph(value: String?): String? = codePoint(value)?.let { String(Character.toChars(it)) }

    fun canonicalCodePoint(value: String): String? = codePoint(value)?.let { "font:%04X".format(it) }

    fun svg(value: String?): String? = value?.trim()?.takeIf { it.startsWith("svg:") }

    /**
     * Recognizes pasted SVG markup, tolerating an XML declaration, comments, a DOCTYPE
     * or any other prolog before the <svg> tag.
     */
    fun svgMarkup(value: String?): String? = value?.trim()?.takeIf { it.contains("<svg", ignoreCase = true) }

    fun canonicalSvg(value: String): String? = svg(value)?.let {
        "svg:" + it.removePrefix("svg:").trim()
    }

    @DrawableRes
    fun drawableResource(context: Context, value: String?, @DrawableRes fallback: Int): Int {
        if (value.isNullOrBlank() || codePoint(value) != null || svg(value) != null) return fallback
        return context.resources.getIdentifier(value, "drawable", context.packageName)
            .takeIf { it != 0 }
            ?: fallback
    }

    fun drawable(context: Context, value: String?, @DrawableRes fallback: Int): Drawable? {
        val svgValue = svg(value)
        if (svgValue != null) {
            return SvgIconDrawable.parse(svgValue.removePrefix("svg:"), context.resources.displayMetrics.density)
        }
        if (fallback == 0) return null
        return AppCompatResources.getDrawable(context, drawableResource(context, value, fallback))
    }
}

/**
 * Unified configuration for both Kawaii Bar and Status Area buttons layout.
 * Stored in a single JSON file for easier management.
 */
@Serializable
data class ButtonsLayoutConfig(
    /**
     * List of buttons to display on Kawaii Bar, in order.
     * Maximum 6 buttons recommended for visual balance.
     * The fixed 'more' button is stored first and cannot be moved.
     */
    @SerialName("kawaiiBarButtons")
    val kawaiiBarButtons: List<ConfigurableButton>,

    /**
     * List of buttons to display in Status Area, in order.
     * Displayed in a 4-column grid layout.
     * Note: 'input_method_options' button is always added automatically at the end and should not be in this list.
     */
    @SerialName("statusAreaButtons")
    val statusAreaButtons: List<ConfigurableButton>,

    /** Buttons hidden from both visible toolbar sections. */
    @SerialName("optionalButtons")
    val optionalButtons: List<ConfigurableButton> = emptyList()
) {
    companion object {
        private const val DEFAULT_MORE_ICON = "font:E141"

        fun defaultMoreButton(): ConfigurableButton = ConfigurableButton(
            id = "more",
            icon = DEFAULT_MORE_ICON
        )

        fun moreButtonOrDefault(buttons: List<ConfigurableButton>): ConfigurableButton {
            val button = buttons.firstOrNull { it.id == "more" } ?: return defaultMoreButton()
            return if (button.icon.isNullOrBlank()) button.copy(icon = DEFAULT_MORE_ICON) else button
        }

        /**
         * Default unified button configuration.
         */
        fun default(): ButtonsLayoutConfig = ButtonsLayoutConfig(
            kawaiiBarButtons = listOf(
                defaultMoreButton(),
                ConfigurableButton("undo"),
                ConfigurableButton("redo"),
                ConfigurableButton("cursor_move"),
                ConfigurableButton("floating_toggle"),
                ConfigurableButton("clipboard"),
                ConfigurableButton("theme_toggle")
            ),
            // Note: input_method_options is always added automatically at the end of Status Area
            statusAreaButtons = listOf(
                ConfigurableButton("theme"),
                ConfigurableButton("reload_config"),
                ConfigurableButton("virtual_keyboard"),
                ConfigurableButton("one_handed_keyboard")
            ),
            optionalButtons = listOf(ConfigurableButton("search"))
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
        @Suppress("DEPRECATION")
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
        @Suppress("DEPRECATION")
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
