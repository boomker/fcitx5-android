/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Intent
import android.view.View
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
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
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams

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

    private val editLayoutButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_keyboard_24, theme).apply {
            contentDescription = context.getString(R.string.edit_text_keyboard_layout)
            setOnClickListener { editCurrentLayout() }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            add(editLayoutButton, lParams(dp(40), dp(40)))
        }
    }

    override val title: String get() = context.getString(R.string.status_area_keyboard_arrangement)

    override fun onCreateView(): View = ui

    override fun onCreateBarExtension(): View = barExtension

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
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun editCurrentLayout() {
        val current = fcitx.runImmediately { inputMethodEntryCached }
        if (!supports(current)) {
            onImeUpdate(current)
            return
        }
        context.startActivity(Intent(context, TextKeyboardLayoutEditorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_PROFILE,
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue())
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_LAYOUT, current.uniqueName)
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_DISPLAY_NAME, current.displayName)
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_LAYOUT_VARIANT,
                ShuangpinKeyboardLayouts.read(current.uniqueName).name)
        })
    }

    companion object {
        fun supports(ime: InputMethodEntry): Boolean =
            ime.addon == "pinyin" && ShuangpinKeyboardLayouts.supports(ime.uniqueName)
    }
}
