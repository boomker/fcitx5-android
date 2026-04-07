/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.views.dsl.core.Ui

class InputMethodEntryUi(override val ctx: Context) : Ui {
    private val activatedBgColor = ctx.styledColor(android.R.attr.colorControlActivated)
    private val activatedTextColor = if (ColorUtils.calculateLuminance(activatedBgColor) > 0.5) {
        Color.BLACK
    } else {
        Color.WHITE
    }

    val title = TextView(ctx).apply {
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        TextViewCompat.setTextAppearance(this, ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem))
        val defaultColor = currentTextColor
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
                intArrayOf(activatedTextColor, defaultColor)
            )
        )
    }

    val subtitle = TextView(ctx).apply {
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        TextViewCompat.setTextAppearance(this, ctx.resolveThemeAttribute(android.R.attr.textAppearanceSmall))
        val defaultColor = currentTextColor
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
                intArrayOf(activatedTextColor.alpha(0.74f), defaultColor)
            )
        )
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_activated),
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(activatedBgColor)
                }
            )
            addState(
                intArrayOf(),
                ColorDrawable(Color.TRANSPARENT)
            )
        }
        isFocusable = true
        isClickable = true
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                    topMargin = dp(1)
                    bottomMargin = dp(1)
                }
                addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                })
            }
        )
    }

    fun setActivated(activated: Boolean) {
        root.isActivated = activated
        title.isActivated = activated
        subtitle.isActivated = activated
    }
}
