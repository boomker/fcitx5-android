/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.utils.isTypeNull

class InputDeviceManager(
    private val onChange: (Boolean) -> Unit,
    private val floatingModeProvider: () -> FloatingCandidatesMode
) {

    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private fun usePhysicalKeyboardHorizontalCandidateBar(isVirtual: Boolean): Boolean {
        if (isVirtual) return false
        return AppPrefs.getInstance().candidates.physicalKeyboardHorizontalCandidateBar.getValue()
    }

    val isPhysicalCandidateBarMode: Boolean
        get() = usePhysicalKeyboardHorizontalCandidateBar(isVirtualKeyboard)

    private fun setupInputViewEvents(isVirtual: Boolean) {
        val iv = inputView ?: return
        val floatingMode = floatingModeProvider()
        val useFloatingAlways = floatingMode == FloatingCandidatesMode.Always
        val useHorizontalCandidateBar = usePhysicalKeyboardHorizontalCandidateBar(isVirtual)

        // InputView should always handle Fcitx events to broadcast to components like HorizontalCandidateComponent
        // For "Always" floating mode, CandidatesView also handles events for floating candidate window
        iv.setPhysicalCandidateBarMode(useHorizontalCandidateBar)
        iv.handleEvents = isVirtual || useHorizontalCandidateBar
        iv.visibility = if (isVirtual || useHorizontalCandidateBar) View.VISIBLE else View.GONE

        // Hide preedit in InputView only when CandidatesView is responsible for floating preedit.
        // Physical-keyboard horizontal candidate bar still uses InputView preedit.
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
        if (usePhysicalKeyboardHorizontalCandidateBar(isVirtual)) {
            cv.handleEvents = false
            cv.visibility = View.GONE
            return
        }

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

    fun onPhysicalKeyboardHorizontalCandidateBarChanged() {
        setupViewEvents(isVirtualKeyboard)
    }

    fun forceVirtualKeyboardForKawaiiBarAction() {
        if (!startedInputView) return
        isVirtualKeyboard = true
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
        val preferHorizontalCandidateBar =
            AppPrefs.getInstance().candidates.physicalKeyboardHorizontalCandidateBar.getValue()
        isVirtualKeyboard = when (candidatesViewMode) {
            FloatingCandidatesMode.SystemDefault -> service.superEvaluateInputViewShown()
            FloatingCandidatesMode.Always ->
                if (preferHorizontalCandidateBar) service.superEvaluateInputViewShown() else true
            FloatingCandidatesMode.InputDevice ->
                if (preferHorizontalCandidateBar) service.superEvaluateInputViewShown() else isVirtualKeyboard
            FloatingCandidatesMode.Disabled ->
                if (preferHorizontalCandidateBar) service.superEvaluateInputViewShown() else true
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
    fun evaluateOnKeyDown(e: KeyEvent): Boolean {
        if (startedInputView) {
            // filter out back/home/volume buttons and combination keys
            if (e.unicodeChar != 0) {
                // evaluate virtual keyboard visibility when pressing physical keyboard while InputView visible
                evaluateOnKeyDownInner()
            }
            // no need to force show InputView since it's already visible
            return false
        } else {
            // force show InputView when focusing on text input (likely inputType is not TYPE_NULL)
            // and pressing any digit/letter/punctuation key on physical keyboard
            val showInputView = !isNullInputType && e.unicodeChar != 0
            if (showInputView) {
                evaluateOnKeyDownInner()
            }
            return showInputView
        }
    }

    private fun evaluateOnKeyDownInner() {
        val preferHorizontalCandidateBar =
            AppPrefs.getInstance().candidates.physicalKeyboardHorizontalCandidateBar.getValue()
        isVirtualKeyboard = when (candidatesViewMode) {
            FloatingCandidatesMode.SystemDefault -> false
            FloatingCandidatesMode.Always -> false
            FloatingCandidatesMode.InputDevice -> false
            FloatingCandidatesMode.Disabled -> !preferHorizontalCandidateBar
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

    /**
     * Should be called whenever active input method changes,
     * eg. focus in/out, editor capability flag changes, user presses the shortcut, and so on...
     * @return should update status icon for this change
     */
    fun evaluateOnInputMethodActivate(): Boolean {
        return startedInputView && !isVirtualKeyboard
    }

    fun onFinishInputView() {
        startedInputView = false
    }
}
