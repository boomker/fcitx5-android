/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.setPaddingDp

class ThemeThumbnailUi(override val ctx: Context) : Ui {

    enum class State { Normal, Selected, LightMode, DarkMode }
    private val keyBorder by ThemeManager.prefs.keyBorder

    val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    val bar = view(::View)

    val themeNameText = textView {
        textSize = 14f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        isClickable = false
        isFocusable = false
        setPaddingDp(8, 4, 8, 4)
    }

    val spaceBar = view(::View)

    val returnKey = view(::View)

    val checkMark = imageView {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val editButton = imageView {
        setPaddingDp(16, 4, 4, 16)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_edit_24
        contentDescription = "Edit theme"
    }

    val dynamicIcon = imageView {
        setPaddingDp(5, 5, 5, 5)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageResource = R.drawable.ic_baseline_auto_awesome_24
    }

    val thumbnailView = constraintLayout {
        outlineProvider = ViewOutlineProvider.BOUNDS
        elevation = dp(2f)
        add(bkg, lParams(matchParent, matchParent))
        add(bar, lParams(matchParent, dp(14)))
        add(themeNameText, lParams(matchParent, wrapContent) {
            centerInParent()
        })
        add(spaceBar, lParams(height = dp(10)) {
            centerHorizontally()
            bottomOfParent(dp(6))
            matchConstraintPercentWidth = 0.5f
        })
        add(returnKey, lParams(dp(14), dp(14)) {
            rightOfParent(dp(4))
            bottomOfParent(dp(4))
        })
        add(dynamicIcon, lParams(dp(32), dp(32)) {
            topOfParent(dp(2))
            startOfParent(dp(2))
        })
        add(checkMark, lParams(dp(60), dp(60)) {
            centerInParent()
        })
        add(editButton, lParams(dp(44), dp(44)) {
            topOfParent()
            endOfParent()
        })
    }

    override val root = thumbnailView

    fun setTheme(theme: Theme) {
        root.apply {
            foreground = rippleDrawable(theme.keyPressHighlightColor)
        }
        bkg.imageDrawable = when (theme) {
            is Theme.Custom -> {
                if (theme.shouldApplyBlur()) {
                    theme.blurredBackgroundDrawable(enableBlur = true, keyBorder = keyBorder)
                } else {
                    theme.backgroundDrawable()
                }
            }
            else -> theme.backgroundDrawable()
        }
        bar.backgroundColor = theme.barColor
        themeNameText.apply {
            text = formatThemeName(theme.name)
            // Use theme's key text color to ensure visibility on background
            setTextColor(theme.keyTextColor)
        }
        spaceBar.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(2f)
            setColor(theme.spaceBarColor)
        }
        returnKey.background = ShapeDrawable(OvalShape()).apply {
            paint.color = theme.accentKeyBackgroundColor
        }
        val foregroundTint = ColorStateList.valueOf(theme.altKeyTextColor)
        editButton.apply {
            visibility =
                if (theme is Theme.Custom || (theme is Theme.Monet && ThemeMonet.supportsCustomMappingEditor(ctx))) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            background = rippleDrawable(theme.keyPressHighlightColor)
            imageTintList = foregroundTint
        }
        dynamicIcon.apply {
            visibility =
                if (theme is Theme.Monet && ThemeMonet.supportsCustomMappingEditor(ctx)) View.VISIBLE else View.GONE
            imageTintList = foregroundTint
        }
        checkMark.imageTintList = foregroundTint
    }

    private fun formatThemeName(name: String): String {
        // Truncate UUID to first 8 characters for display
        return if (name.length == 36 && name.count { it == '-' } == 4) {
            name.take(8)
        } else {
            name
        }
    }

    fun setChecked(checked: Boolean) {
        checkMark.isVisible = checked
        checkMark.imageResource = R.drawable.ic_baseline_check_24
    }

    fun setChecked(state: State) {
        checkMark.isVisible = state != State.Normal
        checkMark.imageResource = when (state) {
            State.Normal -> 0
            State.Selected -> R.drawable.ic_baseline_check_24
            State.LightMode -> R.drawable.ic_baseline_light_mode_24
            State.DarkMode -> R.drawable.ic_baseline_dark_mode_24
        }
    }
}
