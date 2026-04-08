/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar.ui

import android.content.Context
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.Gravity
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.Space
import android.widget.ViewAnimator
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fxboomk.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fxboomk.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fxboomk.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fxboomk.fcitx5.android.input.popup.PopupComponent
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.matchParent
import splitties.views.imageResource
import timber.log.Timber

class IdleUi(
    override val ctx: Context,
    private val theme: Theme,
    private val popup: PopupComponent,
    private val commonKeyActionListener: CommonKeyActionListener,
    private val buttonsConfig: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons
) : Ui {

    enum class State {
        Empty, Toolbar, Clipboard, NumberRow, InlineSuggestion
    }

    var currentState = State.Empty
        private set

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private var inPrivate = false

    private val translateDirection by lazy {
        if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1f else -1f
    }

    val menuButton = ToolButton(ctx, R.drawable.ic_baseline_apps_24, theme)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_keyboard_hide_24, theme)

    val emptyBar = Space(ctx)

    val buttonsUi = ButtonsBarUi(ctx, theme, buttonsConfig)

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val numberRow = NumberRow(ctx, theme).apply {
        visibility = View.GONE
    }

    val inlineSuggestionsBar = InlineSuggestionsUi(ctx)

    private val animator = ViewAnimator(ctx).apply {
        add(emptyBar, lParams(matchParent, matchParent))
        add(buttonsUi.root, lParams(matchParent, matchParent))
        add(clipboardUi.root, lParams(matchParent, matchParent))
        add(inlineSuggestionsBar.root, lParams(matchParent, matchParent))
    }

    private val inAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(0f, 1f))
            // 2 stands for Animation.RELATIVE_TO_PARENT
            addAnimation(TranslateAnimation(2, -0.3f * translateDirection, 2, 0f, 0, 0f, 0, 0f))
        }
    }

    private val outAnimation by lazy {
        AnimationSet(true).apply {
            duration = 200L
            addAnimation(AlphaAnimation(1f, 0f))
            addAnimation(TranslateAnimation(2, 0f, 2, -0.3f * translateDirection, 0, 0f, 0, 0f))
        }
    }

    private val idleBody = constraintLayout {
        val size = dp(KawaiiBarComponent.HEIGHT)
        add(menuButton, lParams(size, size) {
            startOfParent()
            centerVertically()
        })
        add(hideKeyboardButton, lParams(size, size) {
            endOfParent()
            centerVertically()
        })
        add(animator, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(hideKeyboardButton)
            centerVertically()
        })
    }

    override val root = frameLayout {
        add(idleBody, lParams(matchParent, matchParent))
        add(numberRow, lParams(matchParent, matchParent))
    }

    fun privateMode(activate: Boolean = true) {
        if (activate == inPrivate) return
        inPrivate = activate
        updateMenuButtonIcon()
        updateMenuButtonContentDescription()
    }

    private fun updateMenuButtonIcon() {
        menuButton.image.imageResource = when {
            inPrivate -> R.drawable.ic_view_private
            currentState == State.Clipboard || currentState == State.InlineSuggestion -> R.drawable.ic_baseline_arrow_back_24
            else -> R.drawable.ic_baseline_apps_24
        }
    }

    private fun updateMenuButtonContentDescription() {
        menuButton.contentDescription = when {
            inPrivate -> ctx.getString(R.string.private_mode)
            currentState == State.Toolbar -> ctx.getString(R.string.hide_toolbar)
            else -> ctx.getString(R.string.expand_toolbar)
        }
    }

    fun setHideKeyboardIsVoiceInput(isVoiceInput: Boolean, callback: View.OnClickListener) {
        if (isVoiceInput) {
            hideKeyboardButton.setIcon(R.drawable.ic_baseline_keyboard_voice_24)
            hideKeyboardButton.contentDescription = ctx.getString(R.string.switch_to_voice_input)
        } else {
            // Use icon font if configured, otherwise show keyboard hide icon
            if (org.fxboomk.fcitx5.android.input.font.FontProviders.hasFont("button_icon_font")) {
                hideKeyboardButton.setIconText(IconFont.KEYBOARD_CLOSE)
            } else {
                hideKeyboardButton.setIcon(R.drawable.ic_keyboard_hide_24)
            }
            hideKeyboardButton.contentDescription = ctx.getString(R.string.hide_keyboard)
        }
        hideKeyboardButton.setOnClickListener(callback)
    }

    private fun clearAnimation() {
        animator.inAnimation = null
        animator.outAnimation = null
    }

    private fun setAnimation() {
        animator.inAnimation = inAnimation
        animator.outAnimation = outAnimation
    }

    private fun enableSlideTransition(inTarget: View, outTarget: View, inGravity: Int, outGravity: Int) {
        val slideIn = Slide(inGravity).apply { duration = 200L }
        val slideOut = Slide(outGravity).apply { duration = 200L }
        slideIn.addTarget(inTarget)
        slideOut.addTarget(outTarget)
        val set = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(slideIn)
            addTransition(slideOut)
        }
        TransitionManager.beginDelayedTransition(root, set)
    }

    fun updateState(state: State, fromUser: Boolean = false) {
        Timber.d("Switch idle ui to $state")
        if (
            !fromUser ||
            disableAnimation ||
            (state == State.InlineSuggestion || currentState == State.InlineSuggestion) ||
            (state == State.NumberRow || currentState == State.NumberRow)
        ) {
            clearAnimation()
        } else {
            setAnimation()
        }
        when (state) {
            State.Empty -> animator.displayedChild = 0
            State.Toolbar -> animator.displayedChild = 1
            State.Clipboard -> animator.displayedChild = 2
            State.NumberRow -> {}
            State.InlineSuggestion -> animator.displayedChild = 3
        }
        if (state == State.NumberRow) {
            numberRow.keyActionListener = commonKeyActionListener.listener
            numberRow.popupActionListener = popup.listener
            if (fromUser && !disableAnimation) {
                enableSlideTransition(numberRow, idleBody, Gravity.END, Gravity.START)
            }
            numberRow.visibility = View.VISIBLE
            idleBody.visibility = View.GONE
        } else if (currentState == State.NumberRow) {
            if (fromUser && !disableAnimation) {
                enableSlideTransition(idleBody, numberRow, Gravity.START, Gravity.END)
            }
            numberRow.visibility = View.GONE
            idleBody.visibility = View.VISIBLE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        } else if (state == State.Clipboard) {
            // Arrow button only visible in Clipboard state
            menuButton.visibility = View.VISIBLE
            hideKeyboardButton.visibility = View.VISIBLE
            animator.visibility = View.VISIBLE
            numberRow.visibility = View.GONE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        } else {
            // Show menu button (apps icon) in other states
            menuButton.visibility = View.VISIBLE
            hideKeyboardButton.visibility = View.VISIBLE
            animator.visibility = View.VISIBLE
            idleBody.visibility = View.VISIBLE
            numberRow.visibility = View.GONE
            numberRow.keyActionListener = null
            numberRow.popupActionListener = null
            popup.dismissAll()
        }
        currentState = state
        updateMenuButtonIcon()
        updateMenuButtonContentDescription()
    }
}
