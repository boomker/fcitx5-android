/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
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
    theme: Theme,
    // Optional: external font for batch setting (avoids repeated FontProviders access)
    private val font: Typeface? = null
) : Ui {

    val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // Use configured font size with fallback to default (20f)
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

    fun applyConfiguredTypeface(fontOverride: Typeface? = font) {
        // Priority: explicit override > constructor font > cand_font > font > current/system default
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

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)
        longPressFeedbackEnabled = false
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }
}
