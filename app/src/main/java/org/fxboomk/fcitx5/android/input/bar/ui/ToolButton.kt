/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.ViewPropertyAnimator
import android.widget.ImageView
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.font.ButtonIconFont
import org.fxboomk.fcitx5.android.input.font.TextIconDrawable
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.borderlessRippleDrawable
import org.fxboomk.fcitx5.android.utils.circlePressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context) : CustomGestureView(context) {

    companion object {
        val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation
    }

    val image = imageView {
        isClickable = false
        isFocusable = false
        padding = dp(10)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    var iconRotation: Float
        get() = image.rotation
        set(value) {
            image.rotation = value
        }

    private var theme: Theme? = null
    private var isActive: Boolean = false

    constructor(context: Context, @DrawableRes icon: Int, theme: Theme) : this(context) {
        this.theme = theme
        image.imageTintList = ColorStateList.valueOf(theme.altKeyTextColor)
        setIcon(icon)
        setPressHighlightColor(theme.keyPressHighlightColor)
        add(image, lParams(wrapContent, wrapContent, gravityCenter))
    }

    fun iconAnimate(): ViewPropertyAnimator = image.animate()

    fun setIcon(@DrawableRes icon: Int) {
        image.imageResource = icon
    }

    /**
     * Render a glyph from the button icon font through the same ImageView and
     * normalized 24dp box as drawable icons, so its size and vertical position
     * match them instead of following uneven font metrics.
     */
    fun setIconText(iconText: String) {
        val drawable = TextIconDrawable(
            iconText,
            ButtonIconFont.typeface(context),
            resources.displayMetrics.density
        )
        currentIconColor()?.let(drawable::setTint)
        image.setImageDrawable(drawable)
    }

    fun setIconDrawable(drawable: Drawable) {
        image.setImageDrawable(drawable.mutate().apply {
            currentIconColor()?.let(::setTint)
        })
    }

    fun setPressHighlightColor(@ColorInt color: Int) {
        background = if (disableAnimation) {
            circlePressHighlightDrawable(color)
        } else {
            borderlessRippleDrawable(color, dp(20))
        }
    }

    /**
     * Set the active state of this button.
     * When active, the button icon color changes, background remains transparent.
     */
    fun setActive(active: Boolean) {
        if (isActive == active || theme == null) return
        isActive = active
        updateAppearance()
    }

    private fun updateAppearance() {
        val theme = theme ?: return
        // Only change icon color when active, background remains transparent
        // Use accentKeyBackgroundColor to match the one-handed handle color
        val iconColor = if (isActive) theme.accentKeyBackgroundColor else theme.altKeyTextColor

        image.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private fun currentIconColor(): Int? = theme?.let {
        if (isActive) it.accentKeyBackgroundColor else it.altKeyTextColor
    }
}
