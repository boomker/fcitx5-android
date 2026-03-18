/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.action

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.annotation.DrawableRes
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil

/**
 * Represents a configurable button action that can be used in Kawaii Bar, Status Area, or keyboard.
 */
sealed class ButtonAction {
    /**
     * Unique identifier for this button action.
     */
    abstract val id: String

    /**
     * Default icon resource for this button.
     */
    @get:DrawableRes
    abstract val defaultIcon: Int

    /**
     * Default label string resource for this button.
     */
    abstract val defaultLabelRes: Int

    /**
     * Execute the action.
     * @param context Android context
     * @param service Input method service
     * @param fcitx Fcitx connection
     * @param windowManager Window manager for attaching/detaching windows
     * @param view The view that triggered this action (for popup menus, etc.)
     * @param onActionComplete Callback to be invoked after action is completed
     */
    abstract fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View? = null,
        onActionComplete: (() -> Unit)? = null
    )

    /**
     * Check if this button should be active (highlighted).
     * @param service Input method service
     * @return true if the button should be shown as active
     */
    open fun isActive(service: FcitxInputMethodService): Boolean = false

    /**
     * Long press action for this button, if different from short press.
     * @param context Android context
     * @param service Input method service
     * @param fcitx Fcitx connection
     * @param windowManager Window manager
     * @param view The view that triggered this action
     */
    open fun onLongPress(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View
    ) {
        // Default: no long press action
    }

    companion object {
        /**
         * Get a ButtonAction by its ID.
         * @param id The button ID
         * @return The corresponding ButtonAction, or null if not found
         */
        fun fromId(id: String): ButtonAction? = allActions.find { it.id == id }

        /**
         * All available button actions.
         */
        val allActions = listOf(
            UndoAction,
            RedoAction,
            CursorMoveAction,
            FloatingToggleAction,
            ClipboardAction,
            ThemeAction,
            InputMethodOptionsAction,
            ReloadConfigAction,
            VirtualKeyboardAction,
            OneHandedKeyboardAction,
            MoreAction
        )

        /**
         * Button actions available for Kawaii Bar.
         */
        val kawaiiBarActions = listOf(
            UndoAction,
            RedoAction,
            CursorMoveAction,
            FloatingToggleAction,
            ClipboardAction,
            MoreAction
        )

        /**
         * Button actions available for Status Area.
         */
        val statusAreaActions = listOf(
            ThemeAction,
            InputMethodOptionsAction,
            ReloadConfigAction,
            VirtualKeyboardAction,
            OneHandedKeyboardAction
        )

        /**
         * All actions that can be added to either section.
         */
        val allConfigurableActions = kawaiiBarActions + statusAreaActions
    }
}

// Kawaii Bar Actions

data object UndoAction : ButtonAction() {
    override val id = "undo"
    override val defaultIcon = R.drawable.ic_baseline_undo_24
    override val defaultLabelRes = R.string.undo

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true)
    }
}

data object RedoAction : ButtonAction() {
    override val id = "redo"
    override val defaultIcon = R.drawable.ic_baseline_redo_24
    override val defaultLabelRes = R.string.redo

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)
    }
}

data object CursorMoveAction : ButtonAction() {
    override val id = "cursor_move"
    override val defaultIcon = R.drawable.ic_cursor_move
    override val defaultLabelRes = R.string.text_editing

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        windowManager.attachWindow(TextEditingWindow())
    }
}

data object FloatingToggleAction : ButtonAction() {
    override val id = "floating_toggle"
    override val defaultIcon = R.drawable.ic_floating_toggle_24
    override val defaultLabelRes = R.string.floating_keyboard

    override fun isActive(service: FcitxInputMethodService): Boolean {
        return service.inputView?.isFloating == true
    }

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        val inputView = service.inputView ?: return
        if (inputView.isAdjustingMode) {
            inputView.exitAdjustingMode()
        } else {
            inputView.toggleFloatingMode()
        }
        onActionComplete?.invoke()
    }

    override fun onLongPress(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View
    ) {
        service.inputView?.enterAdjustingMode()
    }
}

data object ClipboardAction : ButtonAction() {
    override val id = "clipboard"
    override val defaultIcon = R.drawable.ic_clipboard
    override val defaultLabelRes = R.string.clipboard

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        windowManager.attachWindow(ClipboardWindow())
    }
}

data object MoreAction : ButtonAction() {
    override val id = "more"
    override val defaultIcon = R.drawable.ic_baseline_more_horiz_24
    override val defaultLabelRes = R.string.status_area

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        // More button opens Status Area - handled specially in KawaiiBarComponent
        // This is a placeholder for completeness
    }
}

// Status Area Actions

data object ThemeAction : ButtonAction() {
    override val id = "theme"
    override val defaultIcon = R.drawable.ic_baseline_palette_24
    override val defaultLabelRes = R.string.theme

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToThemeList(context)
    }
}

data object InputMethodOptionsAction : ButtonAction() {
    override val id = "input_method_options"
    override val defaultIcon = R.drawable.ic_baseline_language_24
    override val defaultLabelRes = R.string.input_method_options

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        fcitx.runImmediately { inputMethodEntryCached }.let {
            AppUtil.launchMainToInputMethodConfig(context, it.uniqueName, it.displayName)
        }
    }
}

data object ReloadConfigAction : ButtonAction() {
    override val id = "reload_config"
    override val defaultIcon = R.drawable.ic_baseline_sync_24
    override val defaultLabelRes = R.string.reload_config

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        fcitx.launchOnReady { f ->
            f.reloadConfig()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SubtypeManager.syncWith(f.enabledIme())
            }
            service.lifecycleScope.launch {
                android.widget.Toast.makeText(service, R.string.done, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

data object VirtualKeyboardAction : ButtonAction() {
    override val id = "virtual_keyboard"
    override val defaultIcon = R.drawable.ic_baseline_keyboard_24
    override val defaultLabelRes = R.string.virtual_keyboard

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        AppUtil.launchMainToKeyboard(context)
    }
}

data object OneHandedKeyboardAction : ButtonAction() {
    override val id = "one_handed_keyboard"
    override val defaultIcon = R.drawable.ic_baseline_keyboard_tab_24
    override val defaultLabelRes = R.string.one_handed_keyboard

    override fun execute(
        context: Context,
        service: FcitxInputMethodService,
        fcitx: FcitxConnection,
        windowManager: InputWindowManager,
        view: View?,
        onActionComplete: (() -> Unit)?
    ) {
        service.toggleOneHandKeyboard()
    }
}
