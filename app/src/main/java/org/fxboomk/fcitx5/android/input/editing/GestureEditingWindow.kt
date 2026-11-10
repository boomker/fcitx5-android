/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.InputFeedbacks
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.action.ClipboardAction
import org.fxboomk.fcitx5.android.input.action.RedoAction
import org.fxboomk.fcitx5.android.input.action.UndoAction
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.config.KeyIconThemeStore
import org.fxboomk.fcitx5.android.input.dependency.fcitx
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import timber.log.Timber
import kotlin.math.abs

class GestureEditingWindow(
    val mode: GestureEditingMode,
    returnWindow: InputWindow.ExtendedInputWindow<*>? = null
) : InputWindow.ExtendedInputWindow<GestureEditingWindow>(returnWindow) {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val hapticOnRepeat by AppPrefs.getInstance().keyboard.hapticOnRepeat
    private val buttonRipple by ThemeManager.prefs.keyRippleEffect
    private val buttonBorder by ThemeManager.prefs.keyBorder
    private val buttonRadius by ThemeManager.prefs.textEditingButtonRadius
    private var actionsPopup: PopupWindow? = null

    private val ui by lazy {
        GestureEditingUi(context, theme, mode, buttonRipple, buttonBorder, buttonRadius.toFloat(), ::move).apply {
            exitButton.setOnClickListener { windowManager.attachWindow(KeyboardWindow) }
            switchButton.setOnClickListener {
                windowManager.attachWindow(GestureEditingWindow(GestureEditingMode.Selection, this@GestureEditingWindow))
            }
            actionsButton.setOnClickListener { showActions() }
            pasteButton.setOnClickListener {
                service.currentInputConnection?.performContextMenuAction(android.R.id.paste)
            }
            deleteButton.setOnClickListener { service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
            refreshIcons()
        }
    }

    private fun move(steps: GestureMovementAccumulator.Movement) {
        actionsPopup?.dismiss()
        fun send(keyCode: Int, count: Int) {
            repeat(abs(count)) {
                service.sendGestureEditingDirection(keyCode, mode == GestureEditingMode.Selection)
                if (hapticOnRepeat) InputFeedbacks.hapticFeedback(ui.root)
            }
        }
        if (steps.horizontal != 0) {
            send(if (steps.horizontal > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, steps.horizontal)
        }
        if (steps.vertical != 0) {
            send(if (steps.vertical > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, steps.vertical)
        }
    }

    private fun showActions() {
        actionsPopup?.dismiss()
        val password = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
        } ?: false
        val selectedText = if (password) "" else {
            service.currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
        }
        val columns = 3
        val cellSize = minOf(context.dp(168), ui.root.width) / columns
        val popupWidth = cellSize * columns
        val buttons = mutableListOf<TextEditingButton>()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.keyBackgroundColor)
            elevation = context.dp(8).toFloat()
        }
        fun action(label: Int, icon: Int, enabled: Boolean = true, block: () -> Unit) {
            buttons += TextEditingButton(context, theme, buttonRipple, buttonBorder, buttonRadius.toFloat(), true).apply {
                setIcon(icon)
                contentDescription = context.getString(label)
                TooltipCompat.setTooltipText(this, contentDescription)
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
                setOnClickListener {
                    actionsPopup?.dismiss()
                    block()
                }
            }
        }
        action(android.R.string.copy, R.drawable.ic_baseline_content_copy_24, selectedText.isNotEmpty()) {
            service.currentInputConnection?.performContextMenuAction(android.R.id.copy)
        }
        action(android.R.string.cut, R.drawable.ic_baseline_content_cut_24, selectedText.isNotEmpty()) {
            service.currentInputConnection?.performContextMenuAction(android.R.id.cut)
        }
        action(android.R.string.paste, R.drawable.ic_baseline_content_paste_24) {
            service.currentInputConnection?.performContextMenuAction(android.R.id.paste)
        }
        action(R.string.search, R.drawable.ic_baseline_search_24, selectedText.isNotEmpty()) {
            runCatching { service.startActivity(selectedTextBrowserIntent(service, selectedText)) }
                .onFailure { Timber.w(it, "Unable to open selected text in browser") }
        }
        action(R.string.add_to_favorites, R.drawable.ic_baseline_push_pin_24, selectedText.isNotBlank()) {
            ClipboardManager.launch {
                val saved = runCatching { ClipboardManager.addFavoriteText(selectedText) }.getOrElse {
                    Timber.w(it, "Unable to favorite selected text")
                    null
                }
                withContext(Dispatchers.Main.immediate) {
                    Toast.makeText(service, if (saved != null) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        action(R.string.share, R.drawable.ic_baseline_share_24, selectedText.isNotEmpty()) {
            runCatching { service.startActivity(selectedTextShareIntent(selectedText)) }
                .onFailure { Timber.w(it, "Unable to share selected text") }
        }
        val sensitive = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.PasswordOrSensitive)
        } ?: true
        SelectedTextAiAction.entries.forEach { aiAction ->
            action(aiAction.titleRes, aiAction.iconRes, !sensitive && selectedText.isNotBlank()) {
                service.inputView?.requestSelectedTextAiAction(aiAction, selectedText)
            }
        }
        buttons.chunked(columns).forEach { group ->
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                group.forEach { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            }, LinearLayout.LayoutParams(-1, cellSize))
        }
        val popupHeight = cellSize * columns
        val location = IntArray(2).also(ui.root::getLocationInWindow)
        actionsPopup = PopupWindow(content, popupWidth, popupHeight, false).apply {
            setBackgroundDrawable(ColorDrawable(theme.keyBackgroundColor))
            isOutsideTouchable = true
            elevation = context.dp(8).toFloat()
            setOnDismissListener { actionsPopup = null }
            showAtLocation(
                ui.root,
                Gravity.TOP or Gravity.START,
                location[0] + (ui.root.width - popupWidth) / 2,
                location[1] + ui.root.height - ui.exitButton.height - popupHeight - context.dp(8)
            )
        }
    }

    private val onKeyIconThemeChanged: () -> Unit = { ui.refreshIcons() }

    override fun onCreateView(): View = ui.root

    private val barExtension by lazy {
        context.horizontalLayout {
            listOf(UndoAction, RedoAction, ClipboardAction).forEach { action ->
                add(ToolButton(context, action.defaultIcon, theme).apply {
                    contentDescription = context.getString(action.defaultLabelRes)
                    TooltipCompat.setTooltipText(this, contentDescription)
                    setOnClickListener {
                        actionsPopup?.dismiss()
                        action.execute(context, service, fcitx, windowManager, this)
                    }
                }, lParams(dp(40), dp(40)))
            }
        }
    }

    override fun onCreateBarExtension(): View = barExtension

    override val title: String
        get() = context.getString(
            if (mode == GestureEditingMode.Selection) R.string.gesture_text_selection_mode
            else R.string.gesture_cursor_mode
        )

    override fun enterAnimation(lastWindow: InputWindow) = null

    override fun exitAnimation(nextWindow: InputWindow) = null

    override fun onAttached() {
        ui.root.cancelGesture()
        KeyIconThemeStore.addOnChangedListener(onKeyIconThemeChanged)
    }

    override fun onDetached() {
        ui.root.cancelGesture()
        actionsPopup?.dismiss()
        KeyIconThemeStore.removeOnChangedListener(onKeyIconThemeChanged)
    }
}
