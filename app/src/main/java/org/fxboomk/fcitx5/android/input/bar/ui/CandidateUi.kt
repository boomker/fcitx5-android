/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.preedit.PreeditUi
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams as coreLParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

class CandidateUi(
    override val ctx: Context,
    theme: Theme,
    private val horizontalView: View,
    private val inlinePreeditUi: PreeditUi
) : Ui {

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
    }

    private val content = ctx.verticalLayout {
        add(
            inlinePreeditUi.root,
            coreLParams(matchParent, dp(KawaiiBarComponent.INLINE_PREEDIT_HEIGHT))
        )
        add(horizontalView, coreLParams(matchParent, dp(KawaiiBarComponent.HEIGHT)))
    }

    override val root = ctx.constraintLayout {
        add(expandButton, lParams(dp(40)) {
            centerVertically()
            endOfParent()
        })
        add(content, lParams(matchConstraints, wrapContent) {
            centerVertically()
            startOfParent()
            before(expandButton)
        })
    }

    private var inlineMode = false

    init {
        setInlineMode(false)
    }

    fun setInlineMode(inline: Boolean) {
        inlineMode = inline
        inlinePreeditUi.root.visibility =
            if (inlineMode && inlinePreeditUi.visible) View.VISIBLE else View.GONE
        content.requestLayout()
        root.requestLayout()
    }

    fun updateInlinePreedit(data: org.fxboomk.fcitx5.android.core.FcitxEvent.InputPanelEvent.Data) {
        inlinePreeditUi.update(data)
        inlinePreeditUi.root.visibility =
            if (inlineMode && inlinePreeditUi.visible) View.VISIBLE else View.GONE
        content.requestLayout()
        root.requestLayout()
    }
}
