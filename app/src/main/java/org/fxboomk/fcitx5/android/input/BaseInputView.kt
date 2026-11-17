/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

@file:Suppress("ClickableViewAccessibility")
package org.fxboomk.fcitx5.android.input

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import org.fxboomk.fcitx5.android.core.CandidateAction
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.InputFeedbacks
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.candidates.CandidateCharacterPopup
import org.fxboomk.fcitx5.android.input.candidates.candidateCharacters
import org.fxboomk.fcitx5.android.input.candidates.candidateEdgeCharacters
import org.fxboomk.fcitx5.android.input.candidates.foldedSymbolActions
import org.fxboomk.fcitx5.android.input.candidates.isCandidateFrequencyResetActionText
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.utils.item
import org.fxboomk.fcitx5.android.utils.navbarFrameHeight
import org.fxboomk.fcitx5.android.utils.styledColorOrDefault
import org.fxboomk.fcitx5.android.utils.toast
import splitties.views.dsl.core.withTheme
import kotlin.math.max
import kotlin.math.abs

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
    private var candidateActionRequestJob: Job? = null
    private var candidateActionRequestAnchor: View? = null
    private var candidateActionRequestVersion = 0L

    private fun cancelCandidateActionRequest(anchor: View? = null) {
        if (anchor != null && candidateActionRequestAnchor !== anchor) return
        candidateActionRequestVersion++
        candidateActionRequestJob?.cancel()
        candidateActionRequestJob = null
        candidateActionRequestAnchor = null
    }

    private fun loadCandidateActions(
        idx: Int,
        candidateText: String,
        view: View,
        onLoaded: (Array<CandidateAction>) -> Unit
    ) {
        cancelCandidateActionRequest()
        candidateActionRequestAnchor = view
        val version = candidateActionRequestVersion
        candidateActionRequestJob = service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActionsIfCurrent(idx, candidateText) }
            if (version != candidateActionRequestVersion || !view.isAttachedToWindow) return@launch
            candidateActionRequestJob = null
            if (actions != null) onLoaded(actions)
        }
    }

    private fun showFoldedSymbolPopup(
        idx: Int,
        candidateText: String,
        view: View,
        actions: List<CandidateAction>
    ): CandidateCharacterPopup {
        dismissCandidateCharacterPopup()
        return CandidateCharacterPopup(view, actions.map { it.text }, theme) { selected ->
            triggerFoldedSymbolAction(idx, candidateText, actions[selected])
            dismissCandidateCharacterPopup()
        }.also {
            candidateCharacterPopup = it
            it.show()
            InputFeedbacks.hapticFeedback(view, longPress = true)
        }
    }

    private fun triggerFoldedSymbolAction(idx: Int, candidateText: String, action: CandidateAction) {
        fcitx.runIfReady {
            triggerCandidateActionIfCurrent(idx, candidateText, action.id, action.text, action.icon)
        }
    }

    /**
     * Binds candidate actions on the candidate bar, expanded panel and floating window.
     * Folded symbol alternatives open a bubble during the hold and commit the highlighted
     * alternative on release.
     *
     * The decompose ("拆字") popup and the reset-frequency ("重置词频") action are both gated
     * behind a long press: the user must long press the candidate first, then drag the finger
     * up (decompose) or down (reset frequency). A plain up/down drag no longer triggers these
     * actions, so it stays available for scrolling wherever the candidate view lives inside a
     * scrollable container.
     *
     * The original long-press action menu is preserved. Because a focusable [PopupMenu] steals
     * the ongoing touch stream (the framework delivers ACTION_CANCEL once it grabs focus), the
     * menu cannot be shown mid-hold while still tracking a follow-up drag. It is therefore shown
     * on release on the candidate bar, when no directional drag was recognized. Expanded panels
     * disable directional actions and keep their ordinary menu at the long-press timeout.
     */
    fun bindCandidateGesture(
        view: CustomGestureView,
        idx: Int,
        text: String,
        allowDirectionalActions: Boolean = true
    ) {
        view.cancelGestures()
        if (candidateActionRequestAnchor === view) {
            cancelCandidateActionRequest(view)
            dismissCandidateCharacterPopup()
        }
        val characters = text.candidateCharacters()
        val directionThreshold = resources.displayMetrics.density * 16f
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        var touching = false
        var longPressArmed = false
        var actionsPending = false
        var menuAfterRelease = false
        var pendingDirectionalDrag = false
        var actionTriggered = false
        var resetFrequency = false
        var loadedActions: Array<CandidateAction>? = null
        var symbolActions: List<CandidateAction>? = null
        var popup: CandidateCharacterPopup? = null
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var longPressX = 0f
        var longPressY = 0f
        var symbolPointerMoved = false

        // Swipe detection is handled manually below and gated behind the long press, so a plain
        // vertical drag falls through to the parent (e.g. RecyclerView) for scrolling.
        view.swipeEnabled = false

        fun updateLongPress(x: Float, y: Float): Boolean {
            val deltaY = y - downY
            if (actionsPending) {
                if (allowDirectionalActions && (deltaY > directionThreshold ||
                        deltaY < -directionThreshold && characters.isNotEmpty())) {
                    pendingDirectionalDrag = true
                }
                return true
            }
            return when {
                popup != null -> {
                    popup?.updateFocus(x, y)
                    true
                }

                allowDirectionalActions && !actionTriggered &&
                    deltaY < -directionThreshold && characters.isNotEmpty() -> {
                    actionTriggered = true
                    popup = CandidateCharacterPopup(view, characters, theme).also {
                        candidateCharacterPopup = it
                        it.show()
                        it.updateFocus(x, y)
                    }
                    InputFeedbacks.hapticFeedback(view, longPress = true)
                    true
                }

                allowDirectionalActions && !actionTriggered && deltaY > directionThreshold -> {
                    actionTriggered = true
                    resetFrequency = true
                    InputFeedbacks.hapticFeedback(view, longPress = true)
                    true
                }

                else -> actionTriggered
            }
        }

        view.setOnLongClickListener {
            if (!touching) {
                // Accessibility long clicks have no held pointer; the bubble supports clicks too.
                showCandidateActionMenu(idx, text, view)
                return@setOnLongClickListener true
            }
            longPressArmed = true
            longPressX = lastX
            longPressY = lastY
            view.parent?.requestDisallowInterceptTouchEvent(true)
            actionsPending = true
            loadCandidateActions(idx, text, view) { actions ->
                actionsPending = false
                loadedActions = actions
                val folded = actions.foldedSymbolActions()
                if (!longPressArmed || !touching) {
                    // Ordinary menus may finish loading after release. A folded bubble is
                    // useful only while its pointer is still held.
                    if (menuAfterRelease && folded.isEmpty()) {
                        menuAfterRelease = false
                        showCandidateActions(idx, text, view, actions)
                    }
                    return@loadCandidateActions
                }
                if (folded.isNotEmpty()) {
                    actionTriggered = true
                    symbolActions = folded
                    popup = showFoldedSymbolPopup(idx, text, view, folded).also {
                        if (symbolPointerMoved) it.updateFocus(lastX, lastY)
                    }
                } else if (!allowDirectionalActions) {
                    // Expanded candidates keep their ordinary long-press menu and scrolling.
                    actionTriggered = true
                    showCandidateActions(idx, text, view, actions)
                } else {
                    updateLongPress(lastX, lastY)
                }
            }
            true
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelCandidateActionRequest()
                    candidateActionMenu?.dismiss()
                    touching = true
                    longPressArmed = false
                    actionsPending = false
                    menuAfterRelease = false
                    pendingDirectionalDrag = false
                    symbolPointerMoved = false
                    actionTriggered = false
                    resetFrequency = false
                    loadedActions = null
                    symbolActions = null
                    popup = null
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    dismissCandidateCharacterPopup()
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    lastX = event.x
                    lastY = event.y
                    if (!longPressArmed) return@setOnTouchListener false
                    if (abs(event.x - longPressX) > touchSlop || abs(event.y - longPressY) > touchSlop) {
                        symbolPointerMoved = true
                    }
                    updateLongPress(event.x, event.y)
                }

                MotionEvent.ACTION_UP -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!touching) {
                        // A rebind ended the old stream; this release must not click the new item.
                        view.cancelGestures()
                        return@setOnTouchListener true
                    }
                    val armed = longPressArmed
                    val pending = actionsPending
                    touching = false
                    longPressArmed = false
                    menuAfterRelease = armed && pending && !actionTriggered && !pendingDirectionalDrag
                    if (!menuAfterRelease) cancelCandidateActionRequest(view)
                    val shownPopup = popup
                    when {
                        shownPopup != null -> {
                            if (symbolActions != null) {
                                if (symbolPointerMoved || abs(event.x - longPressX) > touchSlop ||
                                    abs(event.y - longPressY) > touchSlop) {
                                    shownPopup.updateFocus(event.x, event.y)
                                    shownPopup.selectedIndex()?.let { selected ->
                                        symbolActions?.getOrNull(selected)?.let { action ->
                                            triggerFoldedSymbolAction(idx, text, action)
                                        }
                                    }
                                }
                            } else {
                                shownPopup.updateFocus(event.x, event.y)
                                shownPopup.selectedCharacter()?.let(::commitCandidateCharacter)
                            }
                            dismissCandidateCharacterPopup()
                            popup = null
                        }

                        resetFrequency -> resetCandidateFrequency(idx)

                        armed && !pending && !actionTriggered && !pendingDirectionalDrag -> loadedActions?.let { actions ->
                            showCandidateActions(idx, text, view, actions)
                        }
                    }
                    actionsPending = false
                    resetFrequency = false
                    actionTriggered = false
                    // Return false so CustomGestureView still runs its ACTION_UP housekeeping
                    // (state reset + click suppression). performClick only fires for a plain tap,
                    // since an armed long press sets longPressTriggered and suppresses the click.
                    false
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    touching = false
                    longPressArmed = false
                    actionsPending = false
                    menuAfterRelease = false
                    pendingDirectionalDrag = false
                    cancelCandidateActionRequest(view)
                    resetFrequency = false
                    actionTriggered = false
                    popup = null
                    dismissCandidateCharacterPopup()
                    false
                }

                else -> false
            }
        }
    }

    fun unbindCandidateGesture(view: CustomGestureView) {
        view.cancelGestures()
        cancelCandidateActionRequest(view)
        dismissCandidateCharacterPopup()
        view.setOnTouchListener(null)
        view.setOnLongClickListener(null)
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
        dismissCandidateCharacterPopup()
        loadCandidateActions(idx, text, view) { actions ->
            showCandidateActions(idx, text, view, actions)
        }
    }

    private fun showCandidateActions(
        idx: Int,
        text: String,
        view: View,
        actions: Array<CandidateAction>
    ) {
        val folded = actions.foldedSymbolActions()
        if (folded.isNotEmpty()) {
            showFoldedSymbolPopup(idx, text, view, folded)
            return
        }
        val edgeCharacters = text.candidateEdgeCharacters()
        if (actions.isEmpty() && edgeCharacters == null) return
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

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
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
        cancelCandidateActionRequest()
        candidateActionMenu?.dismiss()
        dismissCandidateCharacterPopup()
        handleEvents = false
        super.onDetachedFromWindow()
    }
}
