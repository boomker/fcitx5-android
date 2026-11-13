/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.view.View
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.config.ConfigChangeListener
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.dependency.fcitx
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

internal class KeyboardArrangementWindow(
    private var ime: InputMethodEntry,
    private val onLayoutSelected: (() -> Unit)? = null,
) : InputWindow.ExtendedInputWindow<KeyboardArrangementWindow>(), InputBroadcastReceiver {
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private var attached = false

    private val ui by lazy { KeyboardArrangementPickerUi(context, theme, ::select) }
    private val onLayoutChanged: ConfigChangeListener = { if (attached) ui.show(ime) }

    override val title: String get() = context.getString(R.string.status_area_keyboard_arrangement)

    override fun onCreateView(): View = ui

    override fun onAttached() {
        attached = true
        ConfigProviders.addTextKeyboardLayoutListener(onLayoutChanged)
        onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })
    }

    override fun onDetached() {
        attached = false
        ConfigProviders.removeTextKeyboardLayoutListener(onLayoutChanged)
        ui.clearPreviews()
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        if (!supports(ime)) {
            // Post until attachWindow has finished installing this window.
            ui.post {
                if (windowManager.isAttached(this)) windowManager.attachWindow(KeyboardWindow)
            }
            return
        }
        this.ime = ime
        ui.show(ime)
    }

    private fun select(previewIme: InputMethodEntry, layout: ShuangpinKeyboardLayout) {
        val current = fcitx.runImmediately { inputMethodEntryCached }
        if (current.uniqueName != previewIme.uniqueName || !supports(current)) {
            onImeUpdate(current)
            return
        }
        ShuangpinKeyboardLayouts.write(current.uniqueName, layout)
        // The real keyboard is detached while this panel is visible.
        (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards()
        ui.setSelected(layout)
        onLayoutSelected?.invoke()
    }

    companion object {
        fun supports(ime: InputMethodEntry): Boolean =
            ime.addon == "pinyin" && ShuangpinKeyboardLayouts.supports(ime.uniqueName)
    }
}
