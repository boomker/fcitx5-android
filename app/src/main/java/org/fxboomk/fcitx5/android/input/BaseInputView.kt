/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input

import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.InputFeedbacks
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs
import org.fxboomk.fcitx5.android.input.candidates.CandidateCharacterPopup
import org.fxboomk.fcitx5.android.input.candidates.candidateCharacters
import org.fxboomk.fcitx5.android.input.candidates.candidateEdgeCharacters
import org.fxboomk.fcitx5.android.input.candidates.isCandidateFrequencyResetActionText
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.item
import org.fxboomk.fcitx5.android.utils.navbarFrameHeight
import org.fxboomk.fcitx5.android.utils.styledColorOrDefault
import org.fxboomk.fcitx5.android.utils.toast
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in FcitxAPI) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: FcitxEvent<*>)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
    }

    var handleEvents = false
        set(value) {
            field = value
            if (field) {
                onStartHandleFcitxEvent()
                if (eventHandlerJob == null) {
                    setupFcitxEventHandler()
                }
            } else {
                eventHandlerJob?.cancel()
                eventHandlerJob = null
            }
        }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx) }
    }

    private fun commitCandidateCharacter(character: String) {
        service.postFcitxJob {
            reset()
            withContext(Dispatchers.Main.immediate) {
                service.commitText(character)
            }
        }
    }

    private var candidateCharacterPopup: CandidateCharacterPopup? = null

    fun bindCandidateGesture(view: CustomGestureView, idx: Int, text: String) {
        val characters = text.candidateCharacters()
        var resetFrequency = false
        var gestureCancelled = false
        var popup: CandidateCharacterPopup? = null

        view.swipeEnabled = true
        view.swipeThresholdY = resources.displayMetrics.density * 20f
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureCancelled = true
                resetFrequency = false
                popup = null
                dismissCandidateCharacterPopup()
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        view.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
            when (event.type) {
                CustomGestureView.GestureType.Down -> {
                    dismissCandidateCharacterPopup()
                    resetFrequency = false
                    gestureCancelled = false
                    popup = null
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }

                CustomGestureView.GestureType.Move -> {
                    when {
                        popup != null -> {
                            popup?.updateFocus(event.x, event.y)
                            true
                        }

                        event.totalY < 0 && characters.isNotEmpty() -> {
                            popup = CandidateCharacterPopup(view, characters, theme).also {
                                candidateCharacterPopup = it
                                it.show()
                                it.updateFocus(event.x, event.y)
                            }
                            InputFeedbacks.hapticFeedback(view, longPress = true)
                            true
                        }

                        event.totalY > 0 -> {
                            resetFrequency = true
                            InputFeedbacks.hapticFeedback(view, longPress = true)
                            true
                        }

                        else -> false
                    }
                }

                CustomGestureView.GestureType.Up -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (gestureCancelled) {
                        gestureCancelled = false
                        return@OnGestureListener event.consumed
                    }
                    popup?.let {
                        it.updateFocus(event.x, event.y)
                        it.selectedCharacter()?.let(::commitCandidateCharacter)
                        dismissCandidateCharacterPopup()
                        popup = null
                        return@OnGestureListener true
                    }
                    if (resetFrequency) {
                        resetCandidateFrequency(idx)
                        resetFrequency = false
                        return@OnGestureListener true
                    }
                    event.consumed
                }
            }
        }
    }

    fun unbindCandidateGesture(view: CustomGestureView) {
        dismissCandidateCharacterPopup()
        view.setOnTouchListener(null)
        view.onGestureListener = null
        view.swipeEnabled = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun dismissCandidateCharacterPopup() {
        candidateCharacterPopup?.dismiss()
        candidateCharacterPopup = null
    }

    private fun resetCandidateFrequency(idx: Int) {
        service.lifecycleScope.launch {
            val triggered = runCatching {
                fcitx.runOnReady {
                    val action = getCandidateActions(idx).firstOrNull {
                        !it.isSeparator && it.text.isCandidateFrequencyResetActionText()
                    } ?: return@runOnReady false
                    triggerCandidateAction(idx, action.id)
                    true
                }
            }.getOrDefault(false)
            if (triggered) {
                withContext(Dispatchers.Main.immediate) {
                    context.toast(R.string.candidate_frequency_reset)
                }
            }
        }
    }

    private var candidateActionMenu: PopupMenu? = null

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    fun showCandidateActionMenu(idx: Int, text: String, view: View) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActions(idx) }
            val edgeCharacters = text.candidateEdgeCharacters()
            if (actions.isEmpty() && edgeCharacters == null) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            candidateActionMenu = PopupMenu(themedContext, view).apply {
                menu.add(buildSpannedString {
                    bold {
                        color(
                            context.styledColorOrDefault(
                                android.R.attr.colorAccent,
                                theme.genericActiveForegroundColor
                            )
                        ) {
                            append(text)
                        }
                    }
                }).apply {
                    isEnabled = false
                }
                edgeCharacters?.let { characters ->
                    menu.item(R.string.commit_first_candidate_character) {
                        commitCandidateCharacter(characters.first)
                    }
                    menu.item(R.string.commit_last_candidate_character) {
                        commitCandidateCharacter(characters.last)
                    }
                }
                actions.forEach { action ->
                    menu.item(action.text) {
                        triggerCandidateAction(idx, action.id)
                    }
                }
                setOnDismissListener {
                    candidateActionMenu = null
                }
                show()
            }
        }
    }

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navbarBackground != ThemePrefs.NavbarBackground.Full) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        // use navigation bar insets when available
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, context.navbarFrameHeight())
            }
        }
        return insetsBottom
    }

    private val ignoreSystemWindowInsets by AppPrefs.getInstance().advanced.ignoreSystemWindowInsets

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ignoreSystemWindowInsets) {
            // suppress view's own onApplyWindowInsets
            setOnApplyWindowInsetsListener { _, insets -> insets }
        } else {
            // on API 35+, we must call requestApplyInsets() manually after replacing views,
            // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
            requestApplyInsets()
        }
    }

    override fun onDetachedFromWindow() {
        dismissCandidateCharacterPopup()
        handleEvents = false
        super.onDetachedFromWindow()
    }
}
