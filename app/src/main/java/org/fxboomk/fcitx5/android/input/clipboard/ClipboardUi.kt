/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardCategory
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.coordinatorlayout.coordinatorLayout
import splitties.views.dsl.coordinatorlayout.defaultLParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.setPaddingDp
import timber.log.Timber

class ClipboardUi(override val ctx: Context, private val theme: Theme) : Ui {

    private val categoryButtonBackgrounds = mutableMapOf<TextView, GradientDrawable>()

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
    }

    val enableUi = ClipboardInstructionUi.Enable(ctx, theme)

    val emptyUi = ClipboardInstructionUi.Empty(ctx, theme)

    val viewAnimator = view(::ViewAnimator) {
        add(recyclerView, lParams(matchParent, matchParent))
        add(emptyUi.root, lParams(matchParent, matchParent))
        add(enableUi.root, lParams(matchParent, matchParent))
    }

    private val categoryButtons = linkedMapOf(
        ClipboardCategory.All to createCategoryButton(R.string.clipboard_category_all),
        ClipboardCategory.Local to createCategoryButton(R.string.clipboard_category_local),
        ClipboardCategory.Media to createCategoryButton(R.string.clipboard_category_media),
        ClipboardCategory.Remote to createCategoryButton(R.string.clipboard_category_remote)
    )

    val categoryBar = view(::HorizontalScrollView) {
        isHorizontalScrollBarEnabled = false
        add(horizontalLayout {
            setPaddingDp(8, 8, 8, 4)
            categoryButtons.forEach { (_, button) ->
                add(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(30)
                    ).apply { rightMargin = dp(6) }
                )
            }
        }, lParams(wrapContent, matchParent))
    }

    private var showingPhrases = false

    private val sectionButtons = linkedMapOf(
        false to createCategoryButton(R.string.clipboard),
        true to createCategoryButton(R.string.clipboard_phrases)
    )

    val sectionSwitcher = horizontalLayout {
        gravity = gravityCenter
        add(horizontalLayout {
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(theme.keyBackgroundColor)
            }
            setPaddingDp(2, 2, 2, 2)
            sectionButtons.values.forEach { button ->
                button.setPaddingDp(8, 0, 8, 0)
                button.isSingleLine = true
                button.ellipsize = TextUtils.TruncateAt.END
                button.contentDescription = button.text
                add(button, lParams(dp(64), dp(24)))
            }
        }, lParams(wrapContent, wrapContent))
    }.also {
        setButtonSelected(sectionButtons.getValue(false), true)
    }

    private val keyBorder by ThemeManager.prefs.keyBorder
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    override val root = coordinatorLayout {
        if (!keyBorder) {
            backgroundColor = theme.barColor
        }
        add(verticalLayout {
            add(categoryBar, LinearLayout.LayoutParams(matchParent, LinearLayout.LayoutParams.WRAP_CONTENT))
            add(
                viewAnimator,
                LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
            )
        }, defaultLParams(matchParent, matchParent))
    }

    val deleteAllButton = ToolButton(ctx, R.drawable.ic_baseline_delete_sweep_24, theme).apply {
        contentDescription = ctx.getString(R.string.delete_all)
    }

    val searchButton = ToolButton(ctx, R.drawable.ic_baseline_search_24, theme).apply {
        contentDescription = ctx.getString(R.string.search)
    }

    val settingsButton = ToolButton(ctx, R.drawable.ic_baseline_settings_24, theme).apply {
        contentDescription = ctx.getString(R.string.clipboard_settings)
    }

    val extension = horizontalLayout {
        add(searchButton, lParams(dp(40), dp(40)))
        add(settingsButton, lParams(dp(40), dp(40)))
        add(deleteAllButton, lParams(dp(40), dp(40)))
    }

    private fun setActionButtonsShown(enabled: Boolean) {
        searchButton.visibility = if (enabled || showingPhrases) View.VISIBLE else View.INVISIBLE
        settingsButton.visibility = View.VISIBLE
        deleteAllButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun createCategoryButton(textRes: Int): TextView {
        val fill = GradientDrawable().apply {
            cornerRadius = ctx.dp(15).toFloat()
            setColor(theme.keyBackgroundColor)
        }
        return textView {
            gravity = gravityCenter
            minWidth = dp(64)
            text = ctx.getString(textRes)
            textSize = 13f
            setPaddingDp(12, 0, 12, 0)
            background = categoryBackground(fill)
            isSelected = false
            setTextColor(theme.keyTextColor)
        }.also { categoryButtonBackgrounds[it] = fill }
    }

    private fun categoryBackground(fill: GradientDrawable) = RippleDrawable(
        ColorStateList.valueOf(theme.keyPressHighlightColor),
        fill,
        GradientDrawable().apply {
            cornerRadius = ctx.dp(15).toFloat()
            setColor(Color.WHITE)
        }
    )

    fun setOnCategorySelectedListener(listener: (ClipboardCategory) -> Unit) {
        categoryButtons.forEach { (category, button) ->
            button.setOnClickListener {
                setSelectedCategory(category)
                listener(category)
            }
        }
    }

    fun setSelectedCategory(category: ClipboardCategory) {
        showingPhrases = category == ClipboardCategory.Favorites
        categoryBar.visibility = if (showingPhrases) View.GONE else View.VISIBLE
        emptyUi.setInstruction(
            if (showingPhrases) R.string.clipboard_phrases_empty else R.string.instruction_copy
        )
        sectionButtons.forEach { (phrases, button) ->
            setButtonSelected(button, phrases == showingPhrases)
        }
        categoryButtons.forEach { (buttonCategory, button) ->
            setButtonSelected(button, buttonCategory == category)
        }
        setActionButtonsShown(viewAnimator.displayedChild == 0)
    }

    fun setOnSectionSelectedListener(listener: (Boolean) -> Unit) {
        sectionButtons.forEach { (phrases, button) ->
            button.setOnClickListener { listener(phrases) }
        }
    }

    private fun setButtonSelected(button: TextView, selected: Boolean) {
        if (button.isSelected == selected) return
        button.isSelected = selected
        categoryButtonBackgrounds.getValue(button).setColor(
            if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor
        )
        button.setTextColor(if (selected) theme.accentKeyTextColor else theme.keyTextColor)
    }

    fun switchUiByState(state: ClipboardStateMachine.State) {
        Timber.d("Switch clipboard to $state")
        if (!disableAnimation)
            TransitionManager.beginDelayedTransition(root, Fade().apply { duration = 100L })
        when (state) {
            ClipboardStateMachine.State.Normal -> {
                viewAnimator.displayedChild = 0
                setActionButtonsShown(true)
            }

            ClipboardStateMachine.State.AddMore -> {
                viewAnimator.displayedChild = 1
                setActionButtonsShown(false)
            }

            ClipboardStateMachine.State.EnableListening -> {
                viewAnimator.displayedChild = 2
                setActionButtonsShown(false)
            }
        }
    }
}
