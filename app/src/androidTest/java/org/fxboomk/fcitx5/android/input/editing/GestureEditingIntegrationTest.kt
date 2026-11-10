/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.text.InputType
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import androidx.preference.PreferenceManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fxboomk.fcitx5.android.input.predict.AiSuggestionStripComponent
import org.fxboomk.fcitx5.android.input.predict.LlmClient
import org.fxboomk.fcitx5.android.input.predict.LlmPredictionBackend
import org.fxboomk.fcitx5.android.input.predict.LlmPredictor
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import org.fxboomk.fcitx5.android.input.predict.LlmOutputMode
import org.fxboomk.fcitx5.android.input.predict.LlmTaskMode
import org.fxboomk.fcitx5.android.input.status.StatusAreaWindow
import org.fxboomk.fcitx5.android.input.status.StatusAreaAdapter
import org.fxboomk.fcitx5.android.input.status.StatusAreaEntry
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardWindow
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.keyboard.MacroAction
import org.fxboomk.fcitx5.android.input.keyboard.MacroStep
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.keyboard.BaseKeyboard
import org.fxboomk.fcitx5.android.input.keyboard.KeyView
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeIdleBehavior
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise real IME dispatch and an Android editor, including selection across finger lifts. */
class GestureEditingIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density

    @Test
    fun spaceSwipeEntersSelectionAndStrokesExtendShrinkAndDeleteRealText() = withEditor { editor, service ->
        enter(service, -1)
        assertEquals(20, editor.selectionStart)
        assertEquals(20, editor.selectionEnd)
        val pad = touchpad(service)
        stroke(pad, 48f, 0f)
        await { editor.selectionStart != editor.selectionEnd }
        val firstLength = kotlin.math.abs(editor.selectionEnd - editor.selectionStart)
        assertEquals(3, firstLength)
        stroke(pad, 32f, 0f)
        await { kotlin.math.abs(editor.selectionEnd - editor.selectionStart) == 5 }
        stroke(pad, -16f, 0f)
        await { kotlin.math.abs(editor.selectionEnd - editor.selectionStart) == 4 }
        assertTrue(touchpadOrNull(service) === pad)
        val before = editor.text.length
        onMain { pad.buttons.last().performClick() }
        await { editor.text.length == before - 4 }
        assertEquals(editor.selectionStart, editor.selectionEnd)
        assertTrue(touchpadOrNull(service) === pad)
        onMain { pad.buttons.first().performClick() }
        await { touchpadOrNull(service) == null }
    }

    @Test
    fun cursorMovesByCharacterAndLineThenSwitchesToSelectionWithoutStuckShift() = withEditor { editor, service ->
        enter(service, 1)
        val pad = touchpad(service)
        assertEquals(4, pad.buttons.size)
        stroke(pad, -32f, 0f)
        await { editor.selectionStart == 18 }
        assertEquals(18, editor.selectionEnd)
        stroke(pad, 0f, 32f)
        await { editor.selectionStart != 18 }
        assertEquals(editor.selectionStart, editor.selectionEnd)
        onMain { pad.buttons[1].performClick() }
        await { touchpadOrNull(service)?.buttons?.size == 3 }
        val selectionPad = touchpad(service)
        stroke(selectionPad, -32f, 0f)
        await { editor.selectionStart != editor.selectionEnd }
        onMain { selectionPad.buttons.first().performClick() }
        await { touchpadOrNull(service) == null }
        // A plain arrow after exit must collapse the selection rather than extending it.
        onMain { service.sendGestureEditingDirection(KeyEvent.KEYCODE_DPAD_RIGHT, false) }
        await { editor.selectionStart == editor.selectionEnd }
    }

    @Test
    fun eachSingleChoiceRoutesBothDirectionsToItsConfiguredMode() = withEditor { _, service ->
        val mappings = mapOf(
            SpaceSwipeIdleBehavior.SelectionThenCursor to (GestureEditingMode.Selection to GestureEditingMode.Cursor),
            SpaceSwipeIdleBehavior.CursorThenSelection to (GestureEditingMode.Cursor to GestureEditingMode.Selection),
            SpaceSwipeIdleBehavior.SelectionOnly to (GestureEditingMode.Selection to GestureEditingMode.Selection),
            SpaceSwipeIdleBehavior.CursorOnly to (GestureEditingMode.Cursor to GestureEditingMode.Cursor),
        )
        mappings.forEach { (behavior, modes) ->
            onMain { AppPrefs.getInstance().keyboard.spaceSwipeIdleBehavior.setValue(behavior) }
            listOf(-1 to modes.first, 1 to modes.second).forEach { (direction, expectedMode) ->
                enter(service, direction)
                val pad = touchpad(service)
                assertEquals(if (expectedMode == GestureEditingMode.Selection) 3 else 4, pad.buttons.size)
                onMain { pad.buttons.first().performClick() }
                await { touchpadOrNull(service) == null }
            }
        }
    }

    @Test
    fun appActionsOpenBothTouchpadModesAndTheStatusMenu() = withEditor { editor, service ->
        val original = editor.text.toString()
        listOf("text_selection" to 3, "cursor_movement" to 4).forEach { (actionId, buttons) ->
            onMain { service.inputView!!.executeButtonAction(actionId) }
            await { touchpadOrNull(service)?.buttons?.size == buttons }
            val pad = touchpad(service)
            assertEquals(original, editor.text.toString())
            onMain { pad.buttons.first().performClick() }
            await { touchpadOrNull(service) == null }
        }
        onMain { service.inputView!!.executeButtonAction("more") }
        await {
            val inputView = service.inputView!!
            val wmField = inputView.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
            val wm = wmField.get(inputView) as org.fxboomk.fcitx5.android.input.wm.InputWindowManager
            wm.currentWindowOrNull() is StatusAreaWindow
        }
        onMain { service.inputView!!.executeButtonAction("clipboard_search") }
        await { service.inputView!!.clipboardSearchActive }
        assertEquals(original, editor.text.toString())
        onMain { service.inputView!!.closeClipboardSearch() }
        onMain { service.inputView!!.executeButtonAction("input_method_picker") }
        val dialogField = FcitxInputMethodService::class.java.getDeclaredField("showingDialog").apply { isAccessible = true }
        await { (dialogField.get(service) as? android.app.Dialog)?.isShowing == true }
        onMain { (dialogField.get(service) as android.app.Dialog).dismiss() }
        onMain { service.inputView!!.executeButtonAction("hide_keyboard") }
        await { !service.isInputViewShown }
    }

    @Test
    fun statusAreaGestureButtonsOpenTheirPanelsWithoutReturningToKeyboard() = withEditor { editor, service ->
        val wm = windowManager(service)
        val original = editor.text.toString()
        listOf("text_selection" to GestureEditingMode.Selection, "cursor_movement" to GestureEditingMode.Cursor)
            .forEach { (actionId, mode) ->
                onMain { service.inputView!!.executeButtonAction("more") }
                await {
                    val status = wm.currentWindowOrNull() as? StatusAreaWindow
                    val adapter = status?.view?.adapter as? StatusAreaAdapter
                    adapter?.entries?.any {
                        it is StatusAreaEntry.ActionEntry && it.buttonAction.id == actionId
                    } == true
                }
                onMain {
                    val status = wm.currentWindowOrNull() as StatusAreaWindow
                    val adapter = status.view.adapter as StatusAreaAdapter
                    val entry = adapter.entries.filterIsInstance<StatusAreaEntry.ActionEntry>()
                        .single { it.buttonAction.id == actionId }
                    adapter.onItemClick(status.view, entry)
                }
                await { (wm.currentWindowOrNull() as? GestureEditingWindow)?.mode == mode }
                instrumentation.waitForIdleSync()
                onMain {
                    assertEquals(mode, (wm.currentWindowOrNull() as GestureEditingWindow).mode)
                    assertEquals(original, editor.text.toString())
                    assertTrue(editor.hasFocus())
                    wm.attachWindow(KeyboardWindow)
                }
            }
    }

    @Test
    fun bothGesturePanelHeadersUndoRedoAndOpenClipboardWhileKeepingEditorFocus() = withEditor { editor, service ->
        val wm = windowManager(service)
        listOf("text_selection", "cursor_movement").forEach { actionId ->
            val original = editor.text.toString()
            onMain { service.currentInputConnection!!.commitText("123", 1) }
            await { editor.text.toString() != original }
            val inserted = editor.text.toString()
            onMain { service.inputView!!.executeButtonAction(actionId) }
            await { wm.currentWindowOrNull() is GestureEditingWindow }
            lateinit var window: GestureEditingWindow
            lateinit var buttons: List<ToolButton>
            onMain {
                window = wm.currentWindowOrNull() as GestureEditingWindow
                buttons = window.onCreateBarExtension().allViews.filterIsInstance<ToolButton>().toList()
                assertEquals(listOf(R.string.undo, R.string.redo, R.string.clipboard).map(context::getString),
                    buttons.map { it.contentDescription.toString() })
                buttons.forEach {
                    assertTrue(it.image.drawable != null)
                    assertEquals(it.contentDescription, it.tooltipText)
                }
                buttons[0].performClick()
            }
            await { editor.text.toString() == original }
            onMain {
                assertTrue(wm.currentWindowOrNull() === window)
                assertTrue(editor.hasFocus())
                buttons[1].performClick()
            }
            await { editor.text.toString() == inserted }
            onMain {
                assertTrue(wm.currentWindowOrNull() === window)
                assertTrue(editor.hasFocus())
                buttons[2].performClick()
            }
            await { wm.currentWindowOrNull() is ClipboardWindow }
            onMain {
                assertTrue(editor.hasFocus())
                assertEquals(inserted, editor.text.toString())
                wm.attachWindow(KeyboardWindow)
            }
        }
    }

    @Test
    fun gesturePanelTitlesAndCaptionsUseTheSameShortNames() = withEditor { _, service ->
        val wm = windowManager(service)
        GestureEditingMode.entries.forEach { mode ->
            val window = openGesturePanel(service, mode)
            val label = context.getString(if (mode == GestureEditingMode.Selection) R.string.text_selection else R.string.cursor_movement)
            onMain {
                assertEquals(label, window.title)
                assertEquals(label, context.getString(if (mode == GestureEditingMode.Selection)
                    R.string.gesture_text_selection_mode else R.string.gesture_cursor_mode))
                assertEquals(1, window.onCreateView().allViews.filterIsInstance<TextView>().count { it.text.toString() == label })
                // One label is the visible title bar; the other is the caption in the touchpad.
                assertEquals(2, service.inputView!!.allViews.filterIsInstance<TextView>().count { it.isShown && it.text.toString() == label })
            }
            clickHeaderBack(service)
            await { wm.currentWindowOrNull() is KeyboardWindow }
        }
    }

    @Test
    fun clipboardHeaderBackRestoresBothGesturePanelsAndTheirWorkingTouchpads() = withEditor { editor, service ->
        val wm = windowManager(service)
        val original = editor.text.toString()
        GestureEditingMode.entries.forEach { mode ->
            onMain { editor.setSelection(20) }
            await { service.currentInputSelection.start == 20 && service.currentInputSelection.end == 20 }
            val window = openGesturePanel(service, mode)
            val pad = touchpad(service)
            stroke(pad, 20f, 0f)
            await { editor.selectionEnd == 21 }
            val selection = editor.selectionStart to editor.selectionEnd
            openPanelClipboard(service, window)
            clickHeaderBack(service, context.getString(R.string.back_to_panel, window.title))
            await { wm.currentWindowOrNull() === window && touchpadOrNull(service) === pad && pad.isShown }
            onMain {
                assertEquals(selection, editor.selectionStart to editor.selectionEnd)
                assertTrue(editor.hasFocus())
                assertEquals(original, editor.text.toString())
            }
            stroke(pad, 20f, 0f)
            await { editor.selectionEnd == 22 }
            assertEquals(if (mode == GestureEditingMode.Selection) 20 else 22, editor.selectionStart)
            clickHeaderBack(service)
            await { wm.currentWindowOrNull() is KeyboardWindow }
        }
    }

    @Test
    fun nestedSelectionAndClipboardHeadersReturnThroughEachPreviousPanel() = withEditor { editor, service ->
        val wm = windowManager(service)
        val original = editor.text.toString()
        val cursor = openGesturePanel(service, GestureEditingMode.Cursor)
        val cursorPad = touchpad(service)
        stroke(cursorPad, -20f, 0f)
        await { editor.selectionStart == 19 && editor.selectionEnd == 19 }
        onMain { cursorPad.buttons[1].performClick() }
        await {
            (wm.currentWindowOrNull() as? GestureEditingWindow)?.mode == GestureEditingMode.Selection &&
                touchpadOrNull(service)?.let { it.isShown && it.width > 0 && it.height > 0 } == true
        }
        val selection = wm.currentWindowOrNull() as GestureEditingWindow
        val selectionPad = touchpad(service)
        stroke(selectionPad, 20f, 0f)
        await { editor.selectionStart == 19 && editor.selectionEnd == 20 }
        openPanelClipboard(service, selection)
        clickHeaderBack(service, context.getString(R.string.back_to_panel, selection.title))
        await { wm.currentWindowOrNull() === selection && touchpadOrNull(service) === selectionPad && selectionPad.isShown }
        stroke(selectionPad, 20f, 0f)
        await { editor.selectionStart == 19 && editor.selectionEnd == 21 }
        clickHeaderBack(service, context.getString(R.string.back_to_panel, cursor.title))
        await { wm.currentWindowOrNull() === cursor && touchpadOrNull(service) === cursorPad && cursorPad.isShown }
        stroke(cursorPad, -20f, 0f)
        await { editor.selectionStart == editor.selectionEnd }
        clickHeaderBack(service)
        await { wm.currentWindowOrNull() is KeyboardWindow }
        val beforeArrow = editor.selectionEnd
        onMain { service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT) }
        await { editor.selectionStart == editor.selectionEnd && editor.selectionEnd != beforeArrow }
        onMain {
            assertTrue(editor.hasFocus())
            assertEquals(original, editor.text.toString())
        }
    }

    @Test
    fun explicitGestureExitAndDirectClipboardBackReturnToKeyboardWithoutOldParents() = withEditor { editor, service ->
        val wm = windowManager(service)
        openGesturePanel(service, GestureEditingMode.Cursor)
        onMain { touchpad(service).buttons[1].performClick() }
        await { (wm.currentWindowOrNull() as? GestureEditingWindow)?.mode == GestureEditingMode.Selection }
        onMain { touchpad(service).buttons.first().performClick() }
        await { wm.currentWindowOrNull() is KeyboardWindow }
        onMain { service.inputView!!.executeButtonAction("clipboard") }
        await { wm.currentWindowOrNull() is ClipboardWindow }
        clickHeaderBack(service)
        await { wm.currentWindowOrNull() is KeyboardWindow }
        onMain { assertTrue(editor.hasFocus()) }
    }

    @Test
    fun consecutiveAppActionsInMacrosKeepTheDeclaredWindowOrder() = withEditor { _, service ->
        val inputView = service.inputView!!
        val wmField = inputView.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
        val wm = wmField.get(inputView) as org.fxboomk.fcitx5.android.input.wm.InputWindowManager
        val execute = BaseKeyboard::class.java.getDeclaredMethod("executeMacro", MacroAction::class.java).apply { isAccessible = true }
        listOf(
            listOf("text_selection", "clipboard") to ClipboardWindow::class.java,
            listOf("cursor_movement", "cursor_move") to TextEditingWindow::class.java,
            listOf("more", "cursor_move") to TextEditingWindow::class.java,
            listOf("clipboard", "text_selection") to GestureEditingWindow::class.java,
        ).forEach { (actions, expected) ->
            onMain { wm.attachWindow(KeyboardWindow) }
            await { inputView.allViews.filterIsInstance<BaseKeyboard>().any { it.isShown } }
            onMain {
                val keyboard = inputView.allViews.filterIsInstance<BaseKeyboard>().first { it.isShown }
                execute.invoke(keyboard, MacroAction(actions.map { MacroStep.AppAction(it) }))
            }
            instrumentation.waitForIdleSync()
            await { expected.isInstance(wm.currentWindowOrNull()) }
            instrumentation.waitForIdleSync()
            onMain { assertTrue(expected.isInstance(wm.currentWindowOrNull())) }
        }
    }

    @Test
    fun fastFingerMotionMovesCursorAndSelectionFartherInTheRealEditor() {
        GestureEditingMode.entries.forEach { mode -> withEditor { editor, service ->
            enter(service, if (mode == GestureEditingMode.Selection) -1 else 1)
            val pad = touchpad(service)
            stroke(pad, 64f, 0f, stepDelayMs = 80L)
            await { editor.selectionEnd != 20 }
            val slow = editor.selectionEnd - 20
            assertEquals(4, slow)
            onMain { editor.setSelection(20) }
            await { service.currentInputSelection.start == 20 && service.currentInputSelection.end == 20 }
            stroke(pad, 64f, 0f, stepDelayMs = 1L)
            await { editor.selectionEnd != 20 }
            val fast = editor.selectionEnd - 20
            assertTrue("Fast finger motion must travel farther than the same slow stroke", fast > slow)
            assertTrue(fast <= slow * 4)
            if (mode == GestureEditingMode.Cursor) assertEquals(editor.selectionStart, editor.selectionEnd)
            else assertEquals(20, editor.selectionStart)
        } }
    }

    @Test
    fun actionsPopupUsesIconsAndKeepsEditorFocus() = withEditor { editor, service ->
        enter(service, -1)
        val pad = touchpad(service)
        stroke(pad, 16f, 0f)
        await { editor.selectionStart != editor.selectionEnd }
        lateinit var popup: PopupWindow
        onMain {
            pad.buttons[1].performClick()
            popup = actionsPopup(service)
        }
        await { actionButtons(popup).all { it.width > 0 } }
        onMain {
            assertTrue(!popup.isFocusable)
            assertTrue(editor.hasFocus())
            val buttons = actionButtons(popup)
            assertEquals(9, buttons.size)
            assertTrue(buttons.none { it.contentDescription == context.getString(android.R.string.selectAll) })
            val grid = popup.contentView as LinearLayout
            assertEquals(3, grid.childCount)
            repeat(3) { row ->
                val cells = grid.getChildAt(row) as LinearLayout
                assertEquals(3, cells.childCount)
                repeat(3) { column ->
                    assertEquals(cells.getChildAt(column).width, cells.getChildAt(column).height)
                }
            }
            assertEquals(popup.width, popup.height)
            buttons.forEach { button ->
                assertTrue(button.isEnabled)
                assertTrue(button.imageView.parent === button)
                assertTrue(button.textView.parent == null)
                assertTrue(button.width >= context.resources.displayMetrics.density * 48)
            }
            popup.dismiss()
        }
    }

    @Test
    fun passwordEditorInfoDisablesCopyCutAndSearch() = withEditor { editor, service ->
        enter(service, -1)
        val pad = touchpad(service)
        stroke(pad, 16f, 0f)
        await { editor.selectionStart != editor.selectionEnd }
        onMain {
            val info = requireNotNull(service.currentInputEditorInfo)
            val originalType = info.inputType
            try {
                // Exercise the capability guard without changing the device's secure IME policy.
                info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                pad.buttons[1].performClick()
                val popup = actionsPopup(service)
                val buttons = actionButtons(popup)
                listOf(0, 1, 3, 4, 5, 6, 7, 8).forEach { index -> assertTrue(!buttons[index].isEnabled) }
                assertTrue(buttons[2].isEnabled)
                popup.dismiss()
            } finally {
                info.inputType = originalType
            }
        }
    }

    @Test
    fun favoriteActionAddsExactSelectionWithoutChangingTheEditor() = withEditor { editor, service ->
        val text = " Gesture favorite ${java.util.UUID.randomUUID()}\n中文 "
        setEditorTextAndEnterSelection(editor, service, text)
        try {
            onMain {
                touchpad(service).buttons[1].performClick()
                actionButtons(actionsPopup(service)).single { button ->
                    button.contentDescription == context.getString(R.string.add_to_favorites)
                }.performClick()
            }
            await { runBlocking { ClipboardManager.search(text.trim(), ClipboardSearchCategory.Favorites).entries.any { it.text == text } } }
            val entries = runBlocking { ClipboardManager.search(text.trim(), ClipboardSearchCategory.Favorites).entries }
                .filter { it.text == text }
            assertEquals(1, entries.size)
            assertTrue(entries.single().pinned)
            assertEquals(text, editor.text.toString())
            assertEquals(0, editor.selectionStart)
            assertEquals(text.length, editor.selectionEnd)
        } finally {
            runBlocking {
                ClipboardManager.search(text.trim(), ClipboardSearchCategory.Favorites).entries
                    .filter { it.text == text }.forEach { ClipboardManager.delete(it.id) }
            }
        }
    }

    @Test
    fun searchAndShareActionsDispatchBrowserAndSystemChooser() = withEditor { editor, service ->
        val selections = listOf("https://example.com/test?q=中文", "a search query 中文")
        selections.forEach { text ->
            setEditorTextAndEnterSelection(editor, service, text)
            val sent = captureActivityIntent {
                touchpad(service).buttons[1].performClick()
                actionButtons(actionsPopup(service)).single { it.contentDescription == context.getString(R.string.search) }.performClick()
            }
            assertEquals(selectedTextBrowserIntent(context, text).action, sent.action)
            assertEquals(selectedTextBrowserIntent(context, text).`package`, sent.`package`)
            assertTrue("A default browser must handle this device's search action", !sent.`package`.isNullOrBlank())
            if (text.startsWith("https://")) assertEquals(text, sent.dataString)
        }
        val text = " Share selection\n中文 "
        setEditorTextAndEnterSelection(editor, service, text)
        val sent = captureActivityIntent {
            touchpad(service).buttons[1].performClick()
            actionButtons(actionsPopup(service)).single { it.contentDescription == context.getString(R.string.share) }.performClick()
        }
        assertEquals(Intent.ACTION_CHOOSER, sent.action)
        @Suppress("DEPRECATION")
        val target = requireNotNull(sent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("text/plain", target.type)
        assertEquals(text, target.getStringExtra(Intent.EXTRA_TEXT))
    }

    private fun setEditorTextAndEnterSelection(editor: EditText, service: FcitxInputMethodService, text: String) {
        onMain {
            touchpadOrNull(service)?.buttons?.first()?.performClick()
            editor.setText(text)
            editor.setSelection(0, text.length)
        }
        // Replacing editor contents restarts the IME and intentionally closes the prior touchpad.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        await { service.currentInputSelection.start == 0 && service.currentInputSelection.end == text.length }
        enter(service, -1)
    }

    private fun captureActivityIntent(block: () -> Unit): Intent {
        var sent: Intent? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                sent = Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            onMain(block)
            return requireNotNull(sent)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun selectedAiActionsSendOnlySelectionAndReplaceOnlyThatRangeAfterConfirm() {
        SelectedTextAiAction.entries.forEach { action ->
            withEditor { editor, service -> withCandidateBackend(service) { component, backend ->
                enter(service, -1)
                stroke(touchpad(service), 48f, 0f)
                await { editor.selectionStart != editor.selectionEnd }
                val original = editor.text.toString()
                val start = minOf(editor.selectionStart, editor.selectionEnd)
                val end = maxOf(editor.selectionStart, editor.selectionEnd)
                val selected = original.substring(start, end)
                onMain {
                    touchpad(service).buttons[1].performClick()
                    actionButtons(actionsPopup(service)).single { it.contentDescription == context.getString(action.titleRes) }.performClick()
                }
                await { backend.request != null }
                val request = requireNotNull(backend.request)
                assertEquals(selected, request.beforeCursor)
                assertEquals("", request.recentCommittedText)
                assertEquals("", request.historyText)
                assertEquals(when (action) {
                    SelectedTextAiAction.Translate -> LlmTaskMode.Translate
                    SelectedTextAiAction.Polish -> LlmTaskMode.Completion
                    SelectedTextAiAction.BriefReply -> LlmTaskMode.QuestionAnswer
                }, request.taskMode)
                assertEquals(when (action) {
                    SelectedTextAiAction.Translate -> LlmOutputMode.Suggestions
                    SelectedTextAiAction.Polish -> LlmOutputMode.LongForm
                    SelectedTextAiAction.BriefReply -> LlmOutputMode.BriefAnswer
                }, request.outputMode)
                val answer = "processed selection"
                backend.result.complete(listOf(answer))
                await { component.currentPresentationState().singleTextCommitText == answer }
                assertEquals(original, editor.text.toString())
                onMain { assertTrue(component.commitPrimarySuggestion()) }
                await { editor.text.toString() == original.substring(0, start) + answer + original.substring(end) }
            } }
        }
    }

    @Test
    fun movingSelectionCancelsTheSelectedAiRequestAndRejectsLateCommit() = withEditor { editor, service ->
        withCandidateBackend(service) { component, backend ->
            enter(service, -1)
            stroke(touchpad(service), 32f, 0f)
            await { editor.selectionStart != editor.selectionEnd }
            val original = editor.text.toString()
            val selected = original.substring(minOf(editor.selectionStart, editor.selectionEnd), maxOf(editor.selectionStart, editor.selectionEnd))
            onMain { assertTrue(service.inputView!!.requestSelectedTextAiAction(SelectedTextAiAction.Polish, selected)) }
            await { backend.request != null }
            onMain { editor.setSelection(0) }
            await { service.currentInputSelection.start == 0 && service.currentInputSelection.end == 0 }
            backend.result.complete(listOf("must not insert"))
            instrumentation.waitForIdleSync()
            onMain { assertTrue(!component.commitPrimarySuggestion()) }
            assertEquals(original, editor.text.toString())
        }
    }

    @Test
    fun staleAiResultClickCannotInsertIntoChangedSelection() = withEditor { editor, service ->
        withCandidateBackend(service) { component, backend ->
            enter(service, -1)
            stroke(touchpad(service), 32f, 0f)
            await { editor.selectionStart != editor.selectionEnd }
            val original = editor.text.toString()
            val selected = original.substring(minOf(editor.selectionStart, editor.selectionEnd), maxOf(editor.selectionStart, editor.selectionEnd))
            onMain { assertTrue(service.inputView!!.requestSelectedTextAiAction(SelectedTextAiAction.Polish, selected)) }
            await { backend.request != null }
            backend.result.complete(listOf("stale result"))
            await { component.currentPresentationState().singleTextCommitText == "stale result" }
            onMain { editor.setSelection(0) }
            await { service.currentInputSelection.start == 0 && service.currentInputSelection.end == 0 }
            onMain { component.commitSuggestionFromUi("stale result", fromExpandedArea = true) }
            assertEquals(original, editor.text.toString())
        }
    }

    @Test
    fun collapsingAiPanelCancelsSelectedRequestWithoutRevivingLateResults() = withEditor { editor, service ->
        withCandidateBackend(service) { component, backend ->
            enter(service, -1)
            stroke(touchpad(service), 32f, 0f)
            await { editor.selectionStart != editor.selectionEnd }
            val original = editor.text.toString()
            val selected = original.substring(minOf(editor.selectionStart, editor.selectionEnd), maxOf(editor.selectionStart, editor.selectionEnd))
            onMain { assertTrue(service.inputView!!.requestSelectedTextAiAction(SelectedTextAiAction.Translate, selected)) }
            await { backend.request != null }
            onMain { assertTrue(component.collapsePanel()) }
            backend.result.complete(listOf("late result"))
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue(!component.hasVisibleSuggestions())
                assertTrue(!component.isPanelVisible())
                assertTrue(!component.commitPrimarySuggestion())
            }
            assertEquals(original, editor.text.toString())
        }
    }

    @Test
    fun failedSelectedAiRequestRestoresModesAndShowsAnErrorWithoutEditingText() = withEditor { editor, service ->
        withCandidateBackend(service) { component, backend ->
            enter(service, -1)
            stroke(touchpad(service), 32f, 0f)
            await { editor.selectionStart != editor.selectionEnd }
            val original = editor.text.toString()
            val previous = component.currentPresentationState()
            val selected = original.substring(minOf(editor.selectionStart, editor.selectionEnd), maxOf(editor.selectionStart, editor.selectionEnd))
            onMain { assertTrue(service.inputView!!.requestSelectedTextAiAction(SelectedTextAiAction.Translate, selected)) }
            await { backend.request != null }
            backend.result.completeExceptionally(IllegalStateException("Simulated model failure"))
            await { component.currentPresentationState().errorMessage != null }
            val state = component.currentPresentationState()
            assertEquals(previous.isTranslateEnabled, state.isTranslateEnabled)
            assertEquals(previous.isQuestionAnswerEnabled, state.isQuestionAnswerEnabled)
            assertEquals(previous.isLongFormEnabled, state.isLongFormEnabled)
            val field = AiSuggestionStripComponent::class.java.getDeclaredField("selectionRequest").apply { isAccessible = true }
            assertTrue(field.get(component) == null)
            assertEquals(original, editor.text.toString())
        }
    }

    @Test
    fun disabledAiCandidatesRouteSelectionActionsToJsAssistantPermissionScreen() {
        SelectedTextAiAction.entries.forEach { action -> withEditor { editor, service ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val originalEnabled = prefs.all[LlmPrefs.KEY_ENABLED]
            val permissionPrefs = context.getSharedPreferences("js_app_permissions", android.content.Context.MODE_PRIVATE)
            val permissionKey = "js:llm-assistant/llm_assistant"
            val permissions = permissionPrefs.getStringSet(permissionKey, null)?.toSet()
            try {
                onMain {
                    prefs.edit().putBoolean(LlmPrefs.KEY_ENABLED, false).commit()
                    permissionPrefs.edit().remove(permissionKey).commit()
                }
                enter(service, -1)
                stroke(touchpad(service), 32f, 0f)
                await { editor.selectionStart != editor.selectionEnd }
                onMain {
                    touchpad(service).buttons[1].performClick()
                    actionButtons(actionsPopup(service)).single { it.contentDescription == context.getString(action.titleRes) }.performClick()
                }
                await { service.jsAppInputConnection != null && service.jsAppDismiss != null }
                val showing = FcitxInputMethodService::class.java.getDeclaredField("showingDialog").apply { isAccessible = true }
                onMain {
                    val dialog = showing.get(service) as android.app.Dialog
                    assertTrue(dialog.isShowing)
                    assertEquals("JsAppDialog", dialog.javaClass.simpleName)
                    dialog.dismiss()
                }
                assertEquals("abcdefghijklmno\npqrstuvwxyzabcde\nfghijklmnopqrst\nuvwxyzabcdefghi", editor.text.toString())
            } finally { onMain {
                service.jsAppDismiss?.invoke()
                val edit = prefs.edit()
                if (originalEnabled is Boolean) edit.putBoolean(LlmPrefs.KEY_ENABLED, originalEnabled) else edit.remove(LlmPrefs.KEY_ENABLED)
                edit.commit()
                val permissionsEdit = permissionPrefs.edit()
                if (permissions == null) permissionsEdit.remove(permissionKey) else permissionsEdit.putStringSet(permissionKey, permissions)
                permissionsEdit.commit()
            } }
        } }
    }

    private class SelectionBackend : LlmPredictionBackend {
        @Volatile var request: LlmPredictor.Request? = null
        val result = CompletableDeferred<List<String>>()
        override suspend fun predict(config: LlmPrefs.Config, request: LlmPredictor.Request, onPartialText: ((String) -> Unit)?): LlmClient.PredictionResponse {
            this.request = request
            return LlmClient.PredictionResponse(result.await(), "")
        }
    }

    private fun withCandidateBackend(service: FcitxInputMethodService, block: (AiSuggestionStripComponent, SelectionBackend) -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val keys = listOf(LlmPrefs.KEY_ENABLED, LlmPrefs.KEY_AUTO_PREDICT_ENABLED, LlmPrefs.KEY_RUNTIME,
            LlmPrefs.KEY_PROVIDER, LlmPrefs.KEY_BASE_URL, LlmPrefs.KEY_ENABLED_BASE_URLS, LlmPrefs.KEY_MODEL,
            LlmPrefs.KEY_PREDICTION_DISPLAY_MODE)
        val originals = prefs.all.filterKeys { it in keys }
        val field = service.inputView!!.javaClass.getDeclaredField("aiSuggestionStrip").apply { isAccessible = true }
        val component = field.get(service.inputView) as AiSuggestionStripComponent
        val delegateField = AiSuggestionStripComponent::class.java.getDeclaredField("predictor\$delegate").apply { isAccessible = true }
        val createdField = AiSuggestionStripComponent::class.java.getDeclaredField("predictorCreated").apply { isAccessible = true }
        val originalDelegate = delegateField.get(component)
        val originalCreated = createdField.getBoolean(component)
        val backend = SelectionBackend()
        lateinit var predictor: LlmPredictor
        onMain {
            component.dismissVisibleSuggestions()
            predictor = LlmPredictor(context, remoteBackend = backend, localBackend = backend)
            delegateField.set(component, lazyOf(predictor))
            createdField.setBoolean(component, true)
            prefs.edit().putBoolean(LlmPrefs.KEY_ENABLED, true).putBoolean(LlmPrefs.KEY_AUTO_PREDICT_ENABLED, false)
                .putString(LlmPrefs.KEY_RUNTIME, LlmPrefs.Runtime.Remote.value)
                .putString(LlmPrefs.KEY_PROVIDER, LlmPrefs.Provider.Custom.value)
                .putString(LlmPrefs.KEY_BASE_URL, "https://example.com/v1")
                .putStringSet(LlmPrefs.KEY_ENABLED_BASE_URLS, setOf("https://example.com/v1"))
                .putString(LlmPrefs.KEY_MODEL, "test-selection-model")
                .putString(LlmPrefs.KEY_PREDICTION_DISPLAY_MODE, LlmPrefs.PredictionDisplayMode.FloatingWindow.value).commit()
        }
        try { block(component, backend) }
        finally { onMain {
            component.dismissVisibleSuggestions()
            predictor.close()
            val edit = prefs.edit()
            keys.forEach { edit.remove(it) }
            originals.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            edit.commit()
            delegateField.set(component, originalDelegate)
            createdField.setBoolean(component, originalCreated)
        } }
    }

    private fun windowManager(service: FcitxInputMethodService): InputWindowManager {
        val inputView = requireNotNull(service.inputView)
        return inputView.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
            .get(inputView) as InputWindowManager
    }

    private fun openGesturePanel(service: FcitxInputMethodService, mode: GestureEditingMode): GestureEditingWindow {
        onMain { service.inputView!!.executeButtonAction(if (mode == GestureEditingMode.Selection) "text_selection" else "cursor_movement") }
        val wm = windowManager(service)
        await {
            (wm.currentWindowOrNull() as? GestureEditingWindow)?.mode == mode &&
                touchpadOrNull(service)?.let { it.isShown && it.width > 0 && it.height > 0 } == true
        }
        // A laid-out touchpad can still move during Android's initial IME insets animation.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        return wm.currentWindowOrNull() as GestureEditingWindow
    }

    private fun openPanelClipboard(service: FcitxInputMethodService, window: GestureEditingWindow) {
        await {
            window.onCreateBarExtension().allViews.filterIsInstance<ToolButton>().any {
                it.isShown && it.width > 0 && it.contentDescription == context.getString(R.string.clipboard)
            }
        }
        onMain {
            window.onCreateBarExtension().allViews.filterIsInstance<ToolButton>()
                .single { it.isShown && it.contentDescription == context.getString(R.string.clipboard) }.performClick()
        }
        await { windowManager(service).currentWindowOrNull() is ClipboardWindow }
    }

    private fun clickHeaderBack(
        service: FcitxInputMethodService,
        description: String = context.getString(R.string.back_to_keyboard)
    ) {
        fun button() = service.inputView?.allViews?.filterIsInstance<ToolButton>()?.singleOrNull {
            it.isShown && it.width > 0 && it.height > 0 && it.contentDescription == description
        }
        await { button() != null }
        onMain { requireNotNull(button()).performClick() }
    }

    private fun actionsPopup(service: FcitxInputMethodService): PopupWindow {
        val inputView = requireNotNull(service.inputView)
        val wmField = inputView.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
        val wm = wmField.get(inputView) as org.fxboomk.fcitx5.android.input.wm.InputWindowManager
        val window = wm.currentWindowOrNull() as GestureEditingWindow
        return GestureEditingWindow::class.java.getDeclaredField("actionsPopup").apply { isAccessible = true }
            .get(window) as PopupWindow
    }

    private fun actionButtons(popup: PopupWindow) = popup.contentView.allViews.filterIsInstance<TextEditingButton>().toList()

    private fun withEditor(block: (EditText, FcitxInputMethodService) -> Unit) {
        val prefs = AppPrefs.getInstance().keyboard
        val oldIdle = prefs.spaceSwipeIdleBehavior.getValue()
        val oldHaptic = prefs.hapticOnRepeat.getValue()
        val activity = instrumentation.startActivitySync(
            Intent(context, GestureEditingTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as GestureEditingTestActivity
        lateinit var editor: EditText
        try {
            onMain {
                prefs.spaceSwipeIdleBehavior.setValue(SpaceSwipeIdleBehavior.SelectionThenCursor)
                prefs.hapticOnRepeat.setValue(false)
                editor = EditText(activity).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    setText("abcdefghijklmno\npqrstuvwxyzabcde\nfghijklmnopqrst\nuvwxyzabcdefghi")
                    textSize = 18f
                    setSingleLine(false)
                }
                activity.setContentView(editor)
                editor.requestFocus()
                editor.setSelection(20)
            }
            await { editor.isShown && editor.hasWindowFocus() }
            onMain {
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await { runningService()?.inputView?.isShown == true }
            val service = requireNotNull(runningService())
            await { editor.isShown && editor.hasWindowFocus() && service.currentInputEditorInfo?.fieldId == editor.id }
            onMain { service.postFcitxJob { reset() } }
            await { !service.hasVisibleCandidates() }
            block(editor, service)
        } finally {
            onMain {
                prefs.spaceSwipeIdleBehavior.setValue(oldIdle)
                prefs.hapticOnRepeat.setValue(oldHaptic)
                activity.finish()
            }
        }
    }

    private fun enter(service: FcitxInputMethodService, direction: Int) {
        val field = BaseKeyboard::class.java.getDeclaredField("spaceKeys").apply { isAccessible = true }
        fun spaceKey(): KeyView? = service.inputView?.allViews?.filterIsInstance<BaseKeyboard>()
            ?.flatMap { (field.get(it) as Collection<*>).filterIsInstance<KeyView>().asSequence() }
            ?.firstOrNull { it.isShown && it.width > 0 }
        await { spaceKey() != null }
        // Android's IME insets animation can still move an already laid-out space key.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val space = requireNotNull(spaceKey())
        swipe(space, 0f, direction * 60f * density)
        await { touchpadOrNull(service) != null }
    }

    private fun touchpad(service: FcitxInputMethodService) = requireNotNull(touchpadOrNull(service))

    private fun touchpadOrNull(service: FcitxInputMethodService) =
        service.inputView?.allViews?.filterIsInstance<GestureEditingUi.TouchpadView>()?.firstOrNull()

    private fun stroke(view: View, dx: Float, dy: Float, stepDelayMs: Long = 40L) =
        swipe(view, dx * density, dy * density, center = false, stepDelayMs = stepDelayMs)

    private fun swipe(view: View, dx: Float, dy: Float, center: Boolean = true, stepDelayMs: Long = 40L) {
        val location = IntArray(2)
        onMain { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + if (center) view.height / 2f else 80f * density
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = 1f; size = 1f }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(properties), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        repeat(6) { step ->
            SystemClock.sleep(stepDelayMs)
            send(MotionEvent.ACTION_MOVE, x + dx * (step + 1) / 6f, y + dy * (step + 1) / 6f)
        }
        send(MotionEvent.ACTION_UP, x + dx, y + dy)
        instrumentation.waitForIdleSync()
    }

    private fun runningService(): FcitxInputMethodService? {
        // Test-only inspection of the actual service created by Android in the instrumented process.
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            onMain { result = condition() }
            if (result) return
            SystemClock.sleep(50)
        }
        var result = false
        onMain { result = condition() }
        assertTrue("Timed out waiting for editor / IME state", result)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
