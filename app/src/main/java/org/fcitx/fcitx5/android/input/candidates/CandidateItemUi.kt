/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(
    override val ctx: Context,
    theme: Theme,
    private val fontKey: String? = null,
    private val enableScrollMode: Boolean = false,
    // Optional: external font for batch setting (avoids repeated FontProviders access)
    private val font: Typeface? = null
) : Ui {

    val text = view(::CandidateAutoScaleTextView) {
        scaleMode = if (enableScrollMode) {
            AutoScaleTextView.Mode.Proportional
        } else {
            AutoScaleTextView.Mode.None
        }
        // Use configured font size with fallback to default (20f)
        val fontSize = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "cand_font", 20f
        )
        textSize = fontSize
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    init {
        // Priority: external font > fontKey > default
        font?.let { text.typeface = it } ?: fontKey?.let { text.setFontTypeFace(it) }
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)
        longPressFeedbackEnabled = false
        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }
}
