/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.firstCandidateDrawable
import org.fxboomk.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.dimensions.dp

class CandidateItemUi(
    override val ctx: Context,
    private val theme: Theme,
    private val font: Typeface? = null
) : Ui {

    val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        val fontSize = org.fxboomk.fcitx5.android.input.font.FontProviders.getFontSize(
            "cand_font", 20f
        )
        textSize = fontSize
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    init {
        applyConfiguredTypeface()
    }

    private val normalBackground = pressHighlightDrawable(theme.keyPressHighlightColor)

    private val activeBackground = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        cornerRadius = 8f
    }

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        val resolved = fontOverride ?: FontProviders.resolveTypeface("cand_font", text.typeface)
        if (text.typeface !== resolved) {
            text.typeface = resolved
        }
    }

    fun applyFirstCandidateStyle(
        @ColorInt bgColor: Int,
        @ColorInt strokeColor: Int,
        @ColorInt pressColor: Int,
        cornerRadius: Float = ctx.dp(6f).toFloat()
    ) {
        root.background = firstCandidateDrawable(
            bgColor = bgColor,
            strokeColor = strokeColor,
            cornerRadius = cornerRadius,
            strokeWidth = 1,
            pressColor = pressColor
        )
    }

    fun resetToDefaultBackground(@ColorInt pressColor: Int) {
        root.background = pressHighlightDrawable(pressColor)
    }

    fun setActive(active: Boolean) {
        text.setTextColor(if (active) theme.genericActiveForegroundColor else theme.candidateTextColor)
        text.background = null
        root.background = if (active) activeBackground else normalBackground
    }

    override val root = view(::CustomGestureView) {
        background = normalBackground
        longPressFeedbackEnabled = false
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }
}
