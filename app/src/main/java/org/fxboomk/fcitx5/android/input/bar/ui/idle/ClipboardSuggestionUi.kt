/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

class ClipboardSuggestionUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val clipboardIcon = ctx.drawable(R.drawable.ic_clipboard)!!.apply {
        setTint(theme.altKeyTextColor)
    }

    private val imagePlaceholder = ctx.drawable(R.drawable.ic_baseline_image_24)!!.apply {
        setTint(theme.altKeyTextColor)
    }

    private val icon = imageView {
        imageDrawable = clipboardIcon
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    val text = textView {
        isSingleLine = true
        maxWidth = dp(120)
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(theme.altKeyTextColor)
    }

    private val layout = constraintLayout {
        val spacing = dp(4)
        add(icon, lParams(dp(20), dp(20)) {
            startOfParent(spacing)
            before(text)
            centerVertically()
        })
        add(text, lParams(wrapContent, wrapContent) {
            after(icon, spacing)
            endOfParent(spacing)
            centerVertically()
        })
    }

    val suggestionView = CustomGestureView(ctx).apply {
        add(layout, lParams(wrapContent, matchParent))
        background = rippleDrawable(theme.keyPressHighlightColor)
    }

    fun showText(value: CharSequence) {
        icon.setImageDrawable(clipboardIcon)
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        text.visibility = View.VISIBLE
        text.text = value
    }

    fun showImagePreview(bitmap: Bitmap?) {
        text.visibility = View.GONE
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        if (bitmap == null) {
            icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            icon.setImageDrawable(imagePlaceholder)
        } else {
            icon.setImageBitmap(bitmap)
        }
    }

    override val root = constraintLayout {
        add(suggestionView, lParams(wrapContent, matchConstraints) {
            centerInParent()
            verticalMargin = dp(4)
        })
    }
}
