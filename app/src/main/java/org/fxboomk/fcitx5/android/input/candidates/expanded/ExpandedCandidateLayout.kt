/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.expanded

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.keyboard.BackspaceKey
import org.fxboomk.fcitx5.android.input.keyboard.BaseKeyboard
import org.fxboomk.fcitx5.android.input.keyboard.ImageKeyView
import org.fxboomk.fcitx5.android.input.keyboard.ImageLayoutSwitchKey
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.fxboomk.fcitx5.android.input.keyboard.ReturnKey
import org.fxboomk.fcitx5.android.input.predict.AiSuggestionExpandedUi
import org.fxboomk.fcitx5.android.utils.setVerticalScrollbarThumbColor
import org.fxboomk.fcitx5.android.utils.singleSideBorderDrawable
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class ExpandedCandidateLayout(
    context: Context,
    theme: Theme,
    onSuggestionClick: (String) -> Unit,
    onQuestionAnswerClick: () -> Unit,
    onThinkingClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onLongFormClick: () -> Unit,
) : ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme) : BaseKeyboard(context, theme, ::Layout) {
        companion object {
            const val UpBtnLabel = "U"
            const val DownBtnLabel = "D"

            const val UpBtnId = 0xff55
            const val DownBtnId = 0xff56

            val Layout: List<List<KeyDef>> = listOf(
                listOf(
                    ImageLayoutSwitchKey(
                        R.drawable.ic_baseline_arrow_upward_24,
                        to = UpBtnLabel,
                        percentWidth = 1f,
                        variant = KeyDef.Appearance.Variant.Alternative,
                        viewId = UpBtnId
                    )
                ),
                listOf(
                    ImageLayoutSwitchKey(
                        R.drawable.ic_baseline_arrow_downward_24,
                        to = DownBtnLabel,
                        percentWidth = 1f,
                        variant = KeyDef.Appearance.Variant.Alternative,
                        viewId = DownBtnId
                    )
                ),
                listOf(BackspaceKey(percentWidth = 1f, KeyDef.Appearance.Variant.Alternative)),
                listOf(ReturnKey(percentWidth = 1f))
            )
        }

        val pageUpBtn: ImageKeyView? by lazy { findKeyViewById<ImageKeyView>(UpBtnId) }
        val pageDnBtn: ImageKeyView? by lazy { findKeyViewById<ImageKeyView>(DownBtnId) }
        val backspace: ImageKeyView? by lazy { findKeyViewById<ImageKeyView>(R.id.button_backspace) }
        val `return`: ImageKeyView? by lazy { findKeyViewById<ImageKeyView>(R.id.button_return) }

        override fun onReturnDrawableUpdate(returnDrawable: Int) {
            `return`?.img?.imageResource = resolveGboardReturnDrawable(returnDrawable)
        }
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val keyVerticalMargin by ThemeManager.prefs.keyVerticalMargin
    private val keyVerticalMarginLandscape by ThemeManager.prefs.keyVerticalMarginLandscape

    val recyclerView = recyclerView {
        // disable item cross-fade animation
        itemAnimator = null
        isVerticalScrollBarEnabled = false
    }

    val tabsContainer = constraintLayout {
        id = View.generateViewId()
    }

    val scrollableTabs = recyclerView {
        id = View.generateViewId()
        itemAnimator = null
        // always show scrollbar
        isScrollbarFadingEnabled = false
        scrollBarSize = dp(1)
        setVerticalScrollbarThumbColor(theme.candidateTextColor)
    }

    val pinnedTabs = recyclerView {
        id = View.generateViewId()
        itemAnimator = null
        // prevent scrolling in pinned tabs at bottom
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        isVerticalScrollBarEnabled = false
    }

    val aiSuggestionUi = AiSuggestionExpandedUi(
        context = context,
        theme = theme,
        onSuggestionClick = onSuggestionClick,
        onQuestionAnswerClick = onQuestionAnswerClick,
        onThinkingClick = onThinkingClick,
        onTranslateClick = onTranslateClick,
        onLongFormClick = onLongFormClick,
    )

    var pageUpBtn: ImageKeyView? = null
    var pageDnBtn: ImageKeyView? = null

    private val contentColumn = LinearLayout(context).apply {
        id = View.generateViewId()
        orientation = LinearLayout.VERTICAL
        addView(
            recyclerView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )
        addView(
            aiSuggestionUi,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
    }

    val embeddedKeyboard = Keyboard(context, theme).also {
        pageUpBtn = it.pageUpBtn
        pageDnBtn = it.pageDnBtn
    }

    init {
        id = R.id.expanded_candidate_view

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val inset = dp(if (landscape) keyVerticalMarginLandscape else keyVerticalMargin)
        tabsContainer.background =
            singleSideBorderDrawable(dp(1), theme.dividerColor, Gravity.RIGHT, inset)
        if (!keyBorder) {
            backgroundColor = theme.barColor
            embeddedKeyboard.background =
                singleSideBorderDrawable(dp(1), theme.dividerColor, Gravity.LEFT, inset)
        }

        add(tabsContainer, lParams {
            matchConstraintPercentWidth = 0.15f
            topOfParent()
            leftOfParent()
            bottomOfParent()
        })
        tabsContainer.apply {
            add(scrollableTabs, lParams {
                topOfParent()
                centerHorizontally()
                above(pinnedTabs)
            })
            add(pinnedTabs, lParams(height = wrapContent) {
                bottomOfParent()
                centerHorizontally()
            })
        }
        add(contentColumn, lParams {
            topOfParent()
            leftToRightOf(tabsContainer)
            rightToLeftOf(embeddedKeyboard)
            bottomOfParent()
        })
        add(embeddedKeyboard, lParams {
            matchConstraintPercentWidth = 0.15f
            topOfParent()
            leftToRightOf(contentColumn)
            rightOfParent()
            bottomOfParent()
        })
    }

    fun setAiSuggestionExpandedStyle(fillAvailableHeight: Boolean) {
        aiSuggestionUi.layoutParams = (aiSuggestionUi.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (fillAvailableHeight) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            weight = if (fillAvailableHeight) 1f else 0f
        }
    }

    fun resetPosition() {
        recyclerView.scrollToPosition(0)
    }
}
