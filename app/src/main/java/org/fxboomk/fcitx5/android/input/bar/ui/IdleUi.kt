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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewAnimator
import androidx.core.view.updateLayoutParams
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.ToolbarVoiceInputPolicy
import org.fxboomk.fcitx5.android.input.bar.ui.idle.ButtonsBarUi
import org.fxboomk.fcitx5.android.input.bar.ui.idle.ClipboardSuggestionUi
import org.fxboomk.fcitx5.android.input.bar.ui.idle.InlineSuggestionsUi
import org.fxboomk.fcitx5.android.input.bar.ui.idle.NumberRow
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ButtonIconSpec
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
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageResource
import timber.log.Timber
import kotlin.math.roundToInt
import kotlin.math.sqrt

class IdleUi(
    override val ctx: Context,
    private val theme: Theme,
    private val popup: PopupComponent,
    private val commonKeyActionListener: CommonKeyActionListener,
    private val buttonsConfig: List<ConfigurableButton> = ButtonsLayoutConfig.default().kawaiiBarButtons,
    private var hideKeyboardConfig: ConfigurableButton = ButtonsLayoutConfig.defaultHideKeyboardButton()
) : Ui {

    enum class State {
        Toolbar, Clipboard, NumberRow, InlineSuggestion
    }

    var currentState = State.Toolbar
        private set

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private var inPrivate = false
    private var backToKeyboardMode = false

    private val translateDirection by lazy {
        if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1f else -1f
    }

    private var menuButtonConfig = buttonsConfig.firstOrNull { it.id == "more" }

    val menuButton = ToolButton(
        ctx,
        ButtonIconSpec.drawableResource(
            ctx,
            menuButtonConfig?.icon,
            R.drawable.ic_baseline_apps_24
        ),
        theme
    ).apply {
        contentDescription = ctx.getString(R.string.status_area)
        when {
            ButtonIconSpec.glyph(menuButtonConfig?.icon) != null ->
                setIconText(ButtonIconSpec.glyph(menuButtonConfig?.icon)!!)
            ButtonIconSpec.svg(menuButtonConfig?.icon) != null ->
                ButtonIconSpec.drawable(ctx, menuButtonConfig?.icon, R.drawable.ic_baseline_apps_24)
                    ?.let(::setIconDrawable)
        }
    }

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_keyboard_hide_24, theme)

    val buttonsUi = ButtonsBarUi(ctx, theme, buttonsConfig.filter { it.id != "more" })

    val clipboardUi = ClipboardSuggestionUi(ctx, theme)

    val numberRow = NumberRow(ctx, theme).apply {
        visibility = View.GONE
    }

    val inlineSuggestionsBar = InlineSuggestionsUi(ctx)

    private val voiceStatusText: TextView = textView {
        text = ctx.getString(R.string.voice_status_recording)
        textSize = 14f
        setTextColor(theme.keyTextColor)
        isSingleLine = true
    }

    private val voiceBars = List(12) {
        View(ctx).apply {
            setBackgroundColor(theme.keyTextColor)
            alpha = 0.35f
        }
    }

    private var voiceWavePhase = 0
    private var lastVoiceLevelAt = 0L

    /**
     * Stop control shown while a voice session owns the center strip.
     *
     * The configurable microphone button lives in [animator], which is hidden while
     * recording, so the session's finish control is kept inside the visible voice
     * status strip instead. Its click listener is installed by [KawaiiBarComponent].
     */
    val voiceInputFinishButton = ToolButton(ctx, ToolbarVoiceInputPolicy.finishButtonIcon(), theme).apply {
        contentDescription = ctx.getString(R.string.voice_input_finish)
    }

    private val voiceStatusBar = horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(8), 0, dp(8), 0)
        add(voiceStatusText, lParams(0, wrapContent, weight = 1f))
        voiceBars.forEach { bar ->
            add(bar, LinearLayout.LayoutParams(dp(3), dp(8)).apply {
                marginStart = dp(3)
            })
        }
        add(
            voiceInputFinishButton,
            lParams(ctx.dp(KawaiiBarComponent.configuredHeightDp()), matchParent)
        )
    }

    private val voiceWaveTicker = object : Runnable {
        override fun run() {
            if (voiceStatusBar.visibility != View.VISIBLE) return
            val hasRecentLevel = System.currentTimeMillis() - lastVoiceLevelAt < 500L
            if (!hasRecentLevel) {
                updateVoiceBars(-1f)
            }
            voiceStatusBar.postDelayed(this, 120L)
        }
    }

    init {
        // Toolbar button icons are refreshed by ButtonsBarUi itself.
    }

    fun dispose() {
        buttonsUi.dispose()
    }

    fun updateConfig(newButtons: List<ConfigurableButton>) {
        menuButtonConfig = newButtons.firstOrNull { it.id == "more" }
        buttonsUi.updateConfig(newButtons)
        updateMenuButtonIcon()
    }

    fun updateHideKeyboardConfig(newConfig: ConfigurableButton) {
        hideKeyboardConfig = newConfig
        applyHideKeyboardIcon()
    }

    private fun applyHideKeyboardIcon() {
        val icon = hideKeyboardConfig.icon
        when {
            ButtonIconSpec.glyph(icon) != null -> hideKeyboardButton.setIconText(ButtonIconSpec.glyph(icon)!!)
            ButtonIconSpec.svg(icon) != null -> ButtonIconSpec.drawable(ctx, icon, R.drawable.ic_keyboard_hide_24)
                ?.let(hideKeyboardButton::setIconDrawable)
            else -> hideKeyboardButton.setIcon(
                ButtonIconSpec.drawableResource(ctx, icon, R.drawable.ic_keyboard_hide_24)
            )
        }
    }

    private val animator = ViewAnimator(ctx).apply {
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
        val size = dp(KawaiiBarComponent.configuredHeightDp())
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
        add(voiceStatusBar, lParams(matchConstraints, matchParent) {
            after(menuButton)
            before(hideKeyboardButton)
            centerVertically()
        })
    }

    /**
     * Keep the fixed menu / hide-keyboard buttons as tall as the toolbar when the
     * toolbar height setting changes at runtime.
     */
    fun applyBarHeight(heightDp: Int) {
        val size = ctx.dp(heightDp)
        menuButton.updateLayoutParams {
            width = size
            height = size
        }
        hideKeyboardButton.updateLayoutParams {
            width = size
            height = size
        }
        voiceInputFinishButton.updateLayoutParams {
            width = size
            height = size
        }
    }

    fun showVoiceStatus(label: String = ctx.getString(R.string.voice_status_recording)) {
        voiceStatusText.text = label
        voiceStatusBar.visibility = View.VISIBLE
        // The center strip shows exactly one of the voice status or the animator;
        // the fixed edge buttons stay visible throughout.
        animator.visibility = View.GONE
        idleBody.visibility = View.VISIBLE
        numberRow.visibility = View.GONE
        numberRow.keyActionListener = null
        numberRow.popupActionListener = null
        startVoiceWave()
    }

    fun updateVoiceLevel(rms: Int) {
        if (voiceStatusBar.visibility != View.VISIBLE) return
        lastVoiceLevelAt = System.currentTimeMillis()
        val normalized = sqrt((rms / 3500f).coerceIn(0f, 1f))
        updateVoiceBars(normalized)
    }

    private fun updateVoiceBars(normalized: Float) {
        val animated = normalized < 0f
        val active = if (animated) {
            voiceWavePhase = (voiceWavePhase + 1) % voiceBars.size
            voiceBars.size
        } else {
            (normalized * voiceBars.size).roundToInt().coerceIn(1, voiceBars.size)
        }
        voiceBars.forEachIndexed { index, bar ->
            val waveDistance = kotlin.math.abs(index - voiceWavePhase).let {
                minOf(it, voiceBars.size - it)
            }
            val animatedHeight = ctx.dp(5 + (4 - waveDistance.coerceAtMost(4)) * 4)
            val levelHeight = ctx.dp(5 + ((index % 4) + 1) * 4)
            val targetHeight = when {
                animated -> animatedHeight
                index < active -> levelHeight
                else -> ctx.dp(5)
            }
            bar.layoutParams = bar.layoutParams.apply { this.height = targetHeight }
            bar.alpha = when {
                animated -> if (waveDistance <= 3) 0.9f else 0.3f
                index < active -> 0.95f
                else -> 0.25f
            }
        }
    }

    fun hideVoiceStatus() {
        if (voiceStatusBar.visibility == View.GONE) return
        stopVoiceWave()
        voiceStatusBar.visibility = View.GONE
        if (currentState == State.NumberRow) {
            numberRow.keyActionListener = commonKeyActionListener.listener
            numberRow.popupActionListener = popup.listener
        }
        // Re-derive the strip's normal contents for the state we were in.
        updateState(currentState)
    }

    private fun startVoiceWave() {
        voiceStatusBar.removeCallbacks(voiceWaveTicker)
        voiceStatusBar.post(voiceWaveTicker)
    }

    private fun stopVoiceWave() {
        voiceStatusBar.removeCallbacks(voiceWaveTicker)
        lastVoiceLevelAt = 0L
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

    fun setBackToKeyboardMode(active: Boolean) {
        if (active == backToKeyboardMode) return
        backToKeyboardMode = active
        updateMenuButtonIcon()
        updateMenuButtonContentDescription()
    }

    private fun updateMenuButtonIcon() {
        when {
            backToKeyboardMode -> menuButton.setIcon(R.drawable.ic_baseline_arrow_back_24)
            currentState == State.Clipboard ->
                menuButton.setIcon(R.drawable.ic_baseline_arrow_back_24)
            inPrivate -> menuButton.setIcon(R.drawable.ic_view_private)
            else -> {
                val iconText = ButtonIconSpec.glyph(menuButtonConfig?.icon)
                if (iconText != null) {
                    menuButton.setIconText(iconText)
                } else if (ButtonIconSpec.svg(menuButtonConfig?.icon) != null) {
                    ButtonIconSpec.drawable(
                        ctx,
                        menuButtonConfig?.icon,
                        R.drawable.ic_baseline_apps_24
                    )?.let(menuButton::setIconDrawable)
                } else {
                    menuButton.setIcon(
                        ButtonIconSpec.drawableResource(
                            ctx,
                            menuButtonConfig?.icon,
                            R.drawable.ic_baseline_apps_24
                        )
                    )
                }
            }
        }
    }

    private fun updateMenuButtonContentDescription() {
        menuButton.contentDescription = when {
            backToKeyboardMode -> ctx.getString(R.string.back_to_keyboard)
            currentState == State.Clipboard -> ctx.getString(R.string.return_to_toolbar)
            inPrivate -> ctx.getString(R.string.private_mode)
            else -> ctx.getString(R.string.status_area)
        }
    }

    fun setHideKeyboardClickListener(callback: View.OnClickListener) {
        hideKeyboardButton.contentDescription = ctx.getString(R.string.hide_keyboard)
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
        if (voiceStatusBar.visibility == View.VISIBLE) {
            // A voice session owns the center strip; only remember the state so it
            // can be restored when the session ends.
            currentState = state
            animator.visibility = View.GONE
            idleBody.visibility = View.VISIBLE
            numberRow.visibility = View.GONE
            updateMenuButtonIcon()
            updateMenuButtonContentDescription()
            return
        }
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
            State.Toolbar -> animator.displayedChild = 0
            State.Clipboard -> animator.displayedChild = 1
            State.NumberRow -> {}
            State.InlineSuggestion -> animator.displayedChild = 2
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
