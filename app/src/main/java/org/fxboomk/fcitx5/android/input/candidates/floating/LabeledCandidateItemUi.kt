/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
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

    override val root = view(::CustomGestureView) {
        longPressFeedbackEnabled = false
        add(textView, lParams(wrapContent, matchParent))
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
        textView.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                append(candidate.text)
            }
            if (candidate.comment.isNotBlank()) {
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
