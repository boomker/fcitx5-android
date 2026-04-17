/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.InputFeedbacks

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popup: Array<Popup>? = null
) {
    /**
     * Optional key definition that is used while IME is in composing state.
     * This must keep the same key type/size semantics as the base key.
     */
    var composeOverride: KeyDef? = null

    /**
     * When true on a compose override key, it uses its own independent color settings.
     * When false (default), compose override follows base key color state.
     */
    var independentColor: Boolean = false

    sealed class Appearance(
        val percentWidth: Float,
        val variant: Variant,
        val border: Border,
        val margin: Boolean,
        val viewId: Int,
        val soundEffect: InputFeedbacks.SoundEffect,
        val textColor: Int? = null,
        val textColorMonet: String? = null,
        val altTextColor: Int? = null,
        val altTextColorMonet: String? = null,
        val backgroundColor: Int? = null,
        val backgroundColorMonet: String? = null,
        val shadowColor: Int? = null,
        val shadowColorMonet: String? = null
    ) {
        enum class Variant {
            Normal, AltForeground, Alternative, Accent
        }

        enum class Border {
            Default, On, Off, Special
        }

        open class Text(
            val displayText: String,
            val textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            val textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Appearance(
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            soundEffect,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        class AltText(
            displayText: String,
            val altText: String,
            val character: String,
            textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            InputFeedbacks.SoundEffect.Standard,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        class Image(
            @DrawableRes
            val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Appearance(
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            soundEffect,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        class ImageText(
            displayText: String,
            textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            textStyle: Int = Typeface.NORMAL,
            @DrawableRes
            val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            InputFeedbacks.SoundEffect.Standard,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )
    }

    sealed class Behavior {
        class Press(
            val action: KeyAction
        ) : Behavior()

        class LongPress(
            val action: KeyAction
        ) : Behavior()

        class Repeat(
            val action: KeyAction
        ) : Behavior()

        class Swipe(
            val action: KeyAction
        ) : Behavior()

        class DoubleTap(
            val action: KeyAction
        ) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()

        class AltPreview(content: String, val alternative: String) : Preview(content)

        class Keyboard(val label: String) : Popup()

        class Menu(val items: Array<Item>) : Popup() {
            class Item(val label: String, @DrawableRes val icon: Int, val action: KeyAction)
        }

        /**
         * Represents a longPress macro action that appears in the popup keyboard
         * as the first candidate item.
         * @param displayLabel The text to display in the popup candidate
         * @param action The KeyAction to execute when selected
         * @param baseLabel The base label to lookup remaining candidates from PopupPreset
         */
        class LongPressKeyboard(
            val displayLabel: String,
            val action: KeyAction,
            val baseLabel: String
        ) : Popup()
    }
}
