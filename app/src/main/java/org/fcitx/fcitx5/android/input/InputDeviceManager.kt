/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.utils.isTypeNull

class InputDeviceManager(
    private val onChange: (Boolean) -> Unit,
    private val floatingModeProvider: () -> FloatingCandidatesMode
) {

    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private fun setupInputViewEvents(isVirtual: Boolean) {
        val iv = inputView ?: return
        val floatingMode = floatingModeProvider()
        val useFloatingAlways = floatingMode == FloatingCandidatesMode.Always

        // InputView should always handle Fcitx events to broadcast to components like HorizontalCandidateComponent
        // For "Always" floating mode, CandidatesView also handles events for floating candidate window
        iv.handleEvents = isVirtual
        iv.visibility = if (isVirtual) View.VISIBLE else View.GONE

        // Hide preedit in InputView when using floating candidates (preedit will be in CandidatesView)
        // For "Always" mode, hide preedit when using virtual keyboard
        iv.setPreeditVisibility(!(useFloatingAlways && isVirtual))

        // In "Always" mode, manually update space label when InputView doesn't handle events
        if (useFloatingAlways && isVirtual) {
            iv.updateSpaceLabelOnFloatingMode()
        }
    }

    private fun setupCandidatesViewEvents(isVirtual: Boolean) {
        val cv = candidatesView ?: return
        val floatingMode = floatingModeProvider()
        val useFloatingAlways = floatingMode == FloatingCandidatesMode.Always

        // When using "Always" floating mode, CandidatesView should handle events for both virtual and physical keyboard
        cv.handleEvents = !isVirtual || useFloatingAlways

        // Control visibility based on mode
        when (floatingMode) {
            FloatingCandidatesMode.SystemDefault -> {
                // System default: use system's built-in candidate window
                // CandidatesView is hidden, system handles candidate display
                cv.visibility = View.GONE
            }
            FloatingCandidatesMode.Disabled -> {
                cv.visibility = if (isVirtual) View.GONE else cv.visibility
            }
            FloatingCandidatesMode.InputDevice -> {
                cv.visibility = if (isVirtual) View.GONE else cv.visibility
            }
            FloatingCandidatesMode.Always -> {
                // Keep visible for both virtual and physical keyboard
                // Actual visibility is controlled by content
                cv.visibility = View.VISIBLE
            }
        }
    }

    private fun setupViewEvents(isVirtual: Boolean) {
        setupInputViewEvents(isVirtual)
        setupCandidatesViewEvents(isVirtual)
    }

    var isVirtualKeyboard = true
        private set(value) {
            if (field == value) {
                return
            }
            field = value
            setupViewEvents(value)
            // fire change AFTER updating InputView(s),
            // make the view(s) ready for incoming events during `onChange`
            onChange(value)
        }

    /**
     * Called when floating candidates mode changes.
     * Re-configures InputView and CandidatesView based on the new mode.
     */
    fun onFloatingModeChanged() {
        setupInputViewEvents(isVirtualKeyboard)
        setupCandidatesViewEvents(isVirtualKeyboard)
    }

    fun setInputView(inputView: InputView) {
        this.inputView = inputView
        setupInputViewEvents(this.isVirtualKeyboard)
    }

    fun setCandidatesView(candidatesView: CandidatesView) {
        this.candidatesView = candidatesView
        setupCandidatesViewEvents(this.isVirtualKeyboard)
    }

    private var startedInputView = false
    private var isNullInputType = true

    private var candidatesViewMode by AppPrefs.getInstance().candidates.mode

    fun notifyOnStartInput(attribute: EditorInfo) {
        isNullInputType = attribute.isTypeNull()
    }

    /**
     * @return should use virtual keyboard
     */
    fun evaluateOnStartInputView(info: EditorInfo, service: FcitxInputMethodService): Boolean {
        startedInputView = true
        isNullInputType = info.isTypeNull()
        isVirtualKeyboard = when (candidatesViewMode) {
            FloatingCandidatesMode.SystemDefault -> service.superEvaluateInputViewShown()
            FloatingCandidatesMode.Always -> true
            FloatingCandidatesMode.InputDevice -> isVirtualKeyboard
            FloatingCandidatesMode.Disabled -> true
        }

        // Force update paging mode and keyboard bounds for "Always" mode
        if (candidatesViewMode == FloatingCandidatesMode.Always) {
            service.updateCandidatesViewPagingAndBounds()
        }

        return isVirtualKeyboard
    }

    /**
     * @return should force show input views on hardware key down
     */
    fun evaluateOnKeyDown(e: KeyEvent, service: FcitxInputMethodService): Boolean {
        if (startedInputView) {
            // filter out back/home/volume buttons and combination keys
            if (e.unicodeChar != 0) {
                // evaluate virtual keyboard visibility when pressing physical keyboard while InputView visible
                evaluateOnKeyDownInner(service)
            }
            // no need to force show InputView since it's already visible
            return false
        } else {
            // force show InputView when focusing on text input (likely inputType is not TYPE_NULL)
            // and pressing any digit/letter/punctuation key on physical keyboard
            val showInputView = !isNullInputType && e.unicodeChar != 0
            if (showInputView) {
                evaluateOnKeyDownInner(service)
            }
            return showInputView
        }
    }

    private fun evaluateOnKeyDownInner(service: FcitxInputMethodService) {
        isVirtualKeyboard = when (candidatesViewMode) {
            FloatingCandidatesMode.SystemDefault -> false
            FloatingCandidatesMode.Always -> false
            FloatingCandidatesMode.InputDevice -> false
            FloatingCandidatesMode.Disabled -> true
        }
    }

    fun evaluateOnViewClicked(service: FcitxInputMethodService) {
        if (!startedInputView) return
        isVirtualKeyboard = when (candidatesViewMode) {
            FloatingCandidatesMode.SystemDefault -> service.superEvaluateInputViewShown()  // Use system default
            FloatingCandidatesMode.Always -> true  // Keep virtual keyboard visible
            else -> true
        }
    }

    fun evaluateOnUpdateEditorToolType(toolType: Int, service: FcitxInputMethodService) {
        if (!startedInputView) return
        isVirtualKeyboard = when (candidatesViewMode) {
            FloatingCandidatesMode.SystemDefault -> service.superEvaluateInputViewShown()  // Use system default
            FloatingCandidatesMode.Always -> true  // Keep virtual keyboard visible
            FloatingCandidatesMode.InputDevice ->
                // switch to virtual keyboard on touch screen events, otherwise preserve current mode
                if (toolType == MotionEvent.TOOL_TYPE_FINGER || toolType == MotionEvent.TOOL_TYPE_STYLUS) true else isVirtualKeyboard
            FloatingCandidatesMode.Disabled -> true
        }
    }

    /**
     * Should be called when input method switched **by user**
     * @return should force show inputView for [CandidatesView] when input method switched by user
     */
    fun evaluateOnInputMethodSwitch(): Boolean {
        return !isVirtualKeyboard && !startedInputView
    }

    fun onFinishInputView() {
        startedInputView = false
    }
}
