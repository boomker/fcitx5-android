/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.popup

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import android.widget.TextView
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.gravityCenter

class PopupEntryUi(override val ctx: Context, theme: Theme, keyHeight: Int, radius: Float) : Ui {

    var lastShowTime = -1L

    val textView = view(::AutoScaleTextView) {
        // Use configured font size with fallback to default (23f)
        val fontSize = org.fxboomk.fcitx5.android.input.font.FontProviders.getFontSize(
            "popup_key_font", 23f
        )
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize)
        gravity = gravityCenter
        setTextColor(theme.popupTextColor)
        setFontTypeFace("popup_key_font")
    }

    override val root = constraintLayout {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(theme.popupBackgroundColor)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        add(textView, lParams(matchParent, keyHeight) {
            topOfParent()
            centerHorizontally()
        })
    }

    fun setText(text: String) {
        textView.text = text
    }
}
