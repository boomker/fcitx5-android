/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.keyboard

import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.dependency.context
import org.fxboomk.fcitx5.android.input.dependency.fcitx
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fxboomk.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.SpaceSwipeVerticalAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.VoiceInputHoldEnd
import org.fxboomk.fcitx5.android.input.picker.PickerWindow
import org.fxboomk.fcitx5.android.input.predict.AiSuggestionStripComponent
import org.fxboomk.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.utils.InputMethodUtil
import org.fxboomk.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val aiSuggestionStrip: AiSuggestionStripComponent by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val predictionSpaceBehavior by kbdPrefs.predictionSpaceBehavior
    private val predictionBackspaceBehavior by kbdPrefs.predictionBackspaceBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior
    private val langSwitchKeyLongPressBehavior by kbdPrefs.langSwitchKeyLongPressBehavior
    private val preferredVoiceInput by kbdPrefs.preferredVoiceInput
    private val floatingCandidatesMode by AppPrefs.getInstance().candidates.mode
    private val spaceSwipeVerticalBehavior by kbdPrefs.spaceSwipeVerticalBehavior

    private var backspaceSwipeState = Stopped
    private var voiceHoldActive = false

    private fun FcitxKeyAction.verticalArrowDelta(): Int? =
        when (act.lowercase()) {
            "up" -> -1
            "down" -> 1
            else -> null
        }

    private fun moveVisibleCandidateHighlight(delta: Int) {
        service.lifecycleScope.launch(Dispatchers.Main.immediate) {
            service.moveVisibleCandidateHighlight(delta)
        }
    }

    private fun offsetVisibleCandidatePage(delta: Int) {
        service.postFcitxJob {
            offsetCandidatePage(delta)
        }
    }

    private fun FcitxAPI.hasNativePredictionCandidatesVisible(): Boolean =
        service.hasVisibleNativeCandidates() &&
            clientPreeditCached.isEmpty() &&
            inputPanelCached.preedit.isEmpty()

    private fun FcitxAPI.hasPreedit(): Boolean =
        clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()

    private fun FcitxAPI.isRimeInputMethod(): Boolean =
        inputMethodEntryCached.addon == "rime" || inputMethodEntryCached.icon == "fcitx-rime"

    private suspend fun FcitxAPI.dismissPredictionCandidatesToToolbar(resetNativeCandidates: Boolean) {
        if (resetNativeCandidates) {
            reset()
        }
        service.lifecycleScope.launch(Dispatchers.Main.immediate) {
            service.inputView?.restoreToolbarAfterPredictionCancelled()
            aiSuggestionStrip.suppressAfterBackspace()
        }
    }

    private fun deleteTextAfterPredictionBackspace() {
        service.lifecycleScope.launch(Dispatchers.Main.immediate) {
            service.handleBackspaceDirectly()
        }
    }

    private fun restoreToolbarAfterCompositionCleared() {
        service.lifecycleScope.launch(Dispatchers.Main.immediate) {
            aiSuggestionStrip.suppressAfterBackspace()
            service.inputView?.restoreToolbarAfterPredictionCancelled()
        }
    }

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Commit the visible highlight before a literal symbol/text action.
            // Check for candidates in prediction mode (preedit empty but candidates available)
            val hasCandidates = horizontalCandidate.adapter.total > 0
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty() || hasCandidates) {
                val index = withContext(Dispatchers.Main.immediate) {
                    service.highlightedNativeCandidateIndex()
                }
                select(index ?: 0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker(
        behavior: LangSwitchLongPressBehavior = LangSwitchLongPressBehavior.Default
    ) {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context, behavior))
            }
        }
    }

    private fun preferredVoiceInputProviderAvailable(): Boolean =
        VoiceInputProviderManager.isProviderId(preferredVoiceInput) &&
            VoiceInputProviderManager.hasProvider(preferredVoiceInput, service)

    private fun switchToVoiceInput(): Boolean {
        val isPasswordField = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
        } ?: false
        if (isPasswordField) return false
        if (preferredVoiceInputProviderAvailable()) {
            return VoiceInputProviderManager.toggle(service, preferredVoiceInput)
        }
        val (id, subtype) = InputMethodUtil.findVoiceSubtype(preferredVoiceInput) ?: return false
        InputMethodUtil.switchInputMethod(service, id, subtype)
        return true
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                is FcitxKeyAction -> {
                    val delta = action.verticalArrowDelta()
                    when {
                        delta != null && service.hasVisibleCandidates() -> {
                            if (!action.up) {
                                moveVisibleCandidateHighlight(delta)
                            }
                        }
                        // A symbol forwarded while a candidate is highlighted — e.g. the "[" / "]"
                        // picked from the "k" / "l" long-press popup — should drive 以词定字 / forget
                        // on the highlighted candidate, like the physical keyboard, instead of acting
                        // on the engine's own candidate cursor.
                        !action.up && action.act.length == 1 && service.applyHighlightedCandidateActionKey(
                            action.act[0].code,
                            ctrl = action.states.ctrl,
                            alt = action.states.alt,
                            shift = action.states.shift,
                            meta = action.states.meta,
                        ) -> {}
                        else -> service.postFcitxJob {
                            sendKey(action.act, action.states.states, action.code, action.up)
                        }
                    }
                }
                is SymAction -> service.postFcitxJob {
                    when {
                        action.sym.keyCode == KeyEvent.KEYCODE_ESCAPE && aiSuggestionStrip.hasVisibleSuggestions() -> {
                            service.lifecycleScope.launch { aiSuggestionStrip.dismissVisibleSuggestions() }
                        }
                        action.sym.keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                            sendKey(action.sym, action.states)
                        }
                        action.sym.sym == FcitxKeyMapping.FcitxKey_BackSpace -> {
                            val preeditVisible = hasPreedit()
                            val nativePredictionCandidatesVisible = hasNativePredictionCandidatesVisible()
                            when (
                                predictionBackspaceAction(
                                    hasPreedit = preeditVisible,
                                    hasNativePredictionCandidatesVisible = nativePredictionCandidatesVisible,
                                    hasAiPredictionCandidatesVisible = aiSuggestionStrip.hasVisibleSuggestions(),
                                    hasAiExpandedCandidateWindowVisible =
                                        service.inputView?.isAiSuggestionExpandedWindowVisible() == true,
                                    isRimeInputMethod = isRimeInputMethod(),
                                    predictionBackspaceBehavior = predictionBackspaceBehavior,
                                )
                            ) {
                                PredictionBackspaceAction.SendToFcitx -> {
                                    if (!preeditVisible) {
                                        service.inputView?.handleAiBackspaceUiExit()
                                        service.lifecycleScope.launch { aiSuggestionStrip.suppressAfterBackspace() }
                                    }
                                    sendKey(action.sym, action.states)
                                    if (preeditVisible && isEmpty()) {
                                        restoreToolbarAfterCompositionCleared()
                                    }
                                }
                                PredictionBackspaceAction.DeleteText ->
                                    deleteTextAfterPredictionBackspace()
                                PredictionBackspaceAction.DismissCandidates ->
                                    dismissPredictionCandidatesToToolbar(nativePredictionCandidatesVisible)
                            }
                        }
                        action.sym.sym == FcitxKeyMapping.FcitxKey_space -> {
                            val aiPredictionVisible = !hasPreedit() && withContext(Dispatchers.Main.immediate) {
                                service.inputView?.hasVisibleAiSuggestions() == true
                            }
                            val nativePredictionVisible = hasNativePredictionCandidatesVisible()
                            when {
                                shouldCommitPredictionOnSpace(
                                    hasVisibleCandidates = service.hasVisibleCandidates(),
                                    hasNativePredictionCandidatesVisible = nativePredictionVisible,
                                    hasAiPredictionCandidatesVisible = aiPredictionVisible,
                                    predictionSpaceBehavior = predictionSpaceBehavior,
                                ) -> {
                                    val selected = withContext(Dispatchers.Main.immediate) {
                                        service.selectVisibleCandidateHighlight() ||
                                            (aiPredictionVisible &&
                                                service.inputView?.commitPrimaryAiSuggestion() == true)
                                    }
                                    if (!selected) {
                                        sendKey(action.sym, action.states)
                                    }
                                }
                                predictionSpaceBehavior == PredictionSpaceBehavior.CommitSpace &&
                                    (aiPredictionVisible || (isRimeInputMethod() && nativePredictionVisible)) -> {
                                    service.commitText(" ")
                                }
                                else -> sendKey(action.sym, action.states)
                            }
                        }
                        action.sym.sym == FcitxKeyMapping.FcitxKey_Up &&
                            service.hasVisibleCandidates() -> {
                            moveVisibleCandidateHighlight(-1)
                        }
                        action.sym.sym == FcitxKeyMapping.FcitxKey_Down &&
                            service.hasVisibleCandidates() -> {
                            moveVisibleCandidateHighlight(1)
                        }
                        else -> {
                            sendKey(action.sym, action.states)
                        }
                    }
                }
                is CommitAction -> service.postFcitxJob {
                    // 检查是否是数字副字符划动选择候选的特殊标记 "\0<digit>"
                    if (action.text.startsWith('\u0000') && action.text.length == 2) {
                        val digitChar = action.text[1]
                        if (digitChar.isDigit()) {
                            // 数字 1-9 对应索引 0-8，数字 0 对应索引 9
                            val candidateIndex = if (digitChar == '0') 9 else digitChar.digitToInt() - 1
                            select(candidateIndex)
                            return@postFcitxJob
                        }
                    }

                    // 正常的 CommitAction 处理
                    val text = if (action.followPunctuationMode) {
                        val isPassword = service.currentInputEditorInfo?.let {
                            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
                        } ?: false
                        resolveSwipePunctuation(action.text, currentIme(), isPassword)
                    } else action.text
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                        LangSwitchBehavior.SwitchToEnglish -> {
                            service.postFcitxJob {
                                switchToEnglishInputMode()
                            }
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker(langSwitchKeyLongPressBehavior)
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob {
                                reset()
                                restoreToolbarAfterCompositionCleared()
                            }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is SpaceSwipeVerticalAction -> when (spaceSwipeVerticalBehavior) {
                    SpaceSwipeVerticalBehavior.ArrowKeys -> {
                        if (!preeditState.isEmpty || service.hasVisibleCandidates()) {
                            moveVisibleCandidateHighlight(action.delta)
                        } else {
                            service.postFcitxJob {
                                val sym = if (action.delta > 0) {
                                    FcitxKeyMapping.FcitxKey_Down
                                } else {
                                    FcitxKeyMapping.FcitxKey_Up
                                }
                                sendKey(KeySym(sym), KeyStates.Virtual)
                            }
                        }
                    }
                    SpaceSwipeVerticalBehavior.CandidateRows -> {
                        if (!preeditState.isEmpty || service.hasVisibleCandidates()) {
                            if (floatingCandidatesMode == FloatingCandidatesMode.Always &&
                                service.hasVisibleCandidates()
                            ) {
                                offsetVisibleCandidatePage(action.delta)
                            } else if (horizontalCandidate.hasRowSwipeCandidates()) {
                                service.postFcitxJob {
                                    horizontalCandidate.shiftDisplayedCandidateRow(action.delta)
                                }
                            }
                        } else {
                            service.postFcitxJob {
                                val sym = if (action.delta > 0) {
                                    FcitxKeyMapping.FcitxKey_Down
                                } else {
                                    FcitxKeyMapping.FcitxKey_Up
                                }
                                sendKey(KeySym(sym), KeyStates.Virtual)
                            }
                        }
                    }
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        SpaceLongPressBehavior.VoiceInput -> {
                            val started = switchToVoiceInput()
                            voiceHoldActive = started && preferredVoiceInputProviderAvailable()
                        }
                        SpaceLongPressBehavior.SwitchToEnglish -> service.postFcitxJob {
                            switchToEnglishInputMode()
                        }
                    }
                }
                is VoiceInputHoldEnd -> {
                    if (voiceHoldActive) {
                        VoiceInputProviderManager.stop(service)
                        voiceHoldActive = false
                    }
                }
                else -> {}
            }
        }
    }
}
