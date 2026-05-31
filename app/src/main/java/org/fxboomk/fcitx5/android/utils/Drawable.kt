/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.utils

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.Gravity
import androidx.annotation.ColorInt
import androidx.annotation.GravityInt

fun rippleDrawable(
    @ColorInt color: Int,
    mask: Drawable = ColorDrawable(Color.WHITE)
): Drawable = RippleDrawable(ColorStateList.valueOf(color), null, mask)

fun borderlessRippleDrawable(
    @ColorInt color: Int,
    r: Int = RippleDrawable.RADIUS_AUTO
): Drawable = RippleDrawable(ColorStateList.valueOf(color), null, null).apply {
    radius = r
}

fun pressHighlightDrawable(
    @ColorInt color: Int
): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(color))
}

fun circlePressHighlightDrawable(
    @ColorInt color: Int
): Drawable = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_pressed),
        ShapeDrawable(OvalShape()).apply { paint.color = color }
    )
}

fun borderDrawable(
    width: Int,
    @ColorInt stroke: Int,
    @ColorInt background: Int = Color.TRANSPARENT,
    cornerRadius: Float = 0f
): Drawable = GradientDrawable().apply {
    setStroke(width, stroke)
    setColor(background)
    if (cornerRadius > 0f) {
        this.cornerRadius = cornerRadius
    }
}

fun firstCandidateDrawable(
    @ColorInt bgColor: Int,
    @ColorInt strokeColor: Int,
    cornerRadius: Float,
    strokeWidth: Int,
    @ColorInt pressColor: Int,
    inset: Int = 0
): Drawable {
    val fill = GradientDrawable().apply {
        setColor(bgColor)
        this.cornerRadius = cornerRadius
    }
    val stroke = GradientDrawable().apply {
        setStroke(strokeWidth, strokeColor)
        setColor(Color.TRANSPARENT)
        this.cornerRadius = cornerRadius
    }
    val pressOverlay = ColorDrawable(Color.argb((Color.alpha(pressColor) * 0.6f).toInt(), Color.red(pressColor), Color.green(pressColor), Color.blue(pressColor)))
    fun Drawable.withInset(): Drawable = if (inset > 0) InsetDrawable(this, inset) else this
    return StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            LayerDrawable(arrayOf(fill, stroke, pressOverlay)).withInset()
        )
        addState(intArrayOf(), LayerDrawable(arrayOf(fill, stroke)).withInset())
    }
}

fun singleSideBorderDrawable(
    width: Int,
    @ColorInt stroke: Int,
    @GravityInt gravity: Int,
    inset: Int = 0
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply { setColor(stroke) }
    )
).apply {
    setLayerGravity(0, gravity)
    if (Gravity.isVertical(gravity)) {
        setLayerHeight(0, width)
        setLayerInset(0, inset, 0, inset, 0)
    } else {
        setLayerWidth(0, width)
        setLayerInset(0, 0, inset, 0, inset)
    }
}
