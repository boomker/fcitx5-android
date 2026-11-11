/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.graphics.drawable.GradientDrawable
import android.view.View
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.dependency.context
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dependency.inputView
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditComponent : UniqueComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val inputView by manager.inputView()
    private var currentIme: InputMethodEntry? = null
    private var currentPanel = FcitxEvent.InputPanelEvent.Data()

    /**
     * Control whether this PreeditComponent should display preedit text.
     * Set to false when preedit is displayed elsewhere (e.g., in floating CandidatesView).
     */
    var shouldDisplay = true

    internal val ui by lazy {
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        val bkgColor =
            if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor
        PreeditRowUi(context, theme, setupTextView = {
            horizontalPadding = dp(8)
        }, availableWidth = { inputView.keyboardView.width }).apply {
            syllables.onSelect = { action, panel ->
                val expectedIme = currentIme?.uniqueName
                service.postFcitxJob {
                    val actualIme = currentIme()
                    if (actualIme.uniqueName == expectedIme &&
                        ShuangpinKeyboardLayouts.isCompact(actualIme) && inputPanelCached == panel &&
                        inputPanelCached.tabs.any { it == action }
                    ) {
                        triggerCandidateListTabAction(action.id)
                    }
                }
            }
            root.background = GradientDrawable().apply {
                setColor(bkgColor)
                setStroke(1, theme.dividerColor)
                val radius = context.dp(9).toFloat()
                cornerRadii = floatArrayOf(
                    radius, radius,
                    radius, radius,
                    0f, 0f,
                    0f, 0f
                )
            }
            // TODO make it customizable
            root.alpha = 0.8f
            root.visibility = View.INVISIBLE
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        currentPanel = data
        ui.update(data, ShuangpinKeyboardLayouts.isCompact(currentIme))
        // Only show if shouldDisplay is true
        ui.root.visibility = if (shouldDisplay && ui.visible) View.VISIBLE else View.INVISIBLE
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        if (currentIme?.uniqueName != ime.uniqueName) {
            // Do not pair the previous input method's filter IDs with the new one.
            currentPanel = FcitxEvent.InputPanelEvent.Data()
        }
        currentIme = ime
        ui.update(currentPanel, ShuangpinKeyboardLayouts.isCompact(ime))
        ui.root.visibility = if (shouldDisplay && ui.visible) View.VISIBLE else View.INVISIBLE
    }
}
