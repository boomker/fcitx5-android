/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.view.View
import android.view.Gravity
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.input.candidates.CandidateAnnotationLayout
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit,
    private val highlightRadius: Float
) : Ui {

    private val textView = textView {
        setupTextView(this)
    }

    private val initialTextGravity = textView.gravity

    val pinyinText = view(::AutoScaleTextView) {
        isSingleLine = true
        gravity = Gravity.CENTER
        scaleMode = AutoScaleTextView.Mode.Proportional
        typeface = textView.typeface
        visibility = View.GONE
        setPadding(textView.paddingLeft, 0, textView.paddingRight, 0)
    }

    private val textColumn = CandidateAnnotationLayout(ctx, textView, pinyinText)

    override val root = view({ context ->
        object : CustomGestureView(context) {
            override fun getBaseline(): Int = paddingTop + textColumn.baseline
        }
    }) {
        longPressFeedbackEnabled = false
        add(textColumn, lParams(wrapContent, matchParent))
    }

    private val highlightDrawable = GradientDrawable().apply {
        setColor(theme.genericActiveBackgroundColor)
        cornerRadius = highlightRadius
    }

    fun update(candidate: CandidateWord, active: Boolean) {
        val activeFg = if (theme.isDark) Color.BLACK else Color.WHITE
        val labelFg = if (active) activeFg else theme.candidateLabelColor
        val fg = if (active) activeFg else theme.candidateTextColor
        val altFg = if (active) activeFg else theme.candidateCommentColor
        val topPinyin = candidate.topPinyinAnnotation
        textView.gravity = if (topPinyin != null) {
            (initialTextGravity and Gravity.VERTICAL_GRAVITY_MASK.inv()) or Gravity.CENTER_VERTICAL
        } else {
            initialTextGravity
        }
        pinyinText.text = topPinyin.orEmpty()
        pinyinText.visibility = if (topPinyin == null) View.GONE else View.VISIBLE
        pinyinText.setTextColor(if (active) {
            CandidateAnnotationLayout.highlightTextColor(theme.genericActiveBackgroundColor, theme.keyboardColor)
        } else altFg)
        textView.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                append(candidate.text)
            }
            if (topPinyin == null && candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(candidate.comment)
                }
            }
        }
        root.background = if (active) highlightDrawable else null
    }
}
