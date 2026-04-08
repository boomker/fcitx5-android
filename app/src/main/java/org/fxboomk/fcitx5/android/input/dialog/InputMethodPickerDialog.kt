/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.dialog

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.utils.InputMethodUtil
import splitties.dimensions.dp
import splitties.resources.styledDrawable
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.verticalLayoutManager
import splitties.views.topPadding

object InputMethodPickerDialog {
    suspend fun build(
        fcitx: FcitxAPI,
        service: FcitxInputMethodService,
        context: Context
    ): AlertDialog {
        val entries = InputMethodData.resolve(fcitx, service)
        val enabledIM = fcitx.inputMethodEntryCached.uniqueName
        val enabledIndex = entries.indexOfFirst { it.uniqueName == enabledIM }
        val dividerIndex = entries.indexOfFirst { it.ime }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.choose_input_method)
            .setView(context.recyclerView {
                layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
                // add some padding because AlertDialog's `titleDividerNoCustom` won't show up...
                // but why?
                // https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r48/core/res/res/layout/alert_dialog_title_material.xml#58
                topPadding = dp(4) // android.R.dimen.dialog_title_divider_material
                setPadding(dp(30), dp(0), dp(30), dp(4))
                layoutManager = verticalLayoutManager()
                adapter = InputMethodListAdapter(entries, enabledIndex) {
                    if (it.ime) {
                        val imeId = it.imeId ?: return@InputMethodListAdapter
                        val subtype = it.subtype
                        if (subtype != null) {
                            InputMethodUtil.switchInputMethod(service, imeId, subtype)
                        } else {
                            service.switchInputMethod(imeId)
                        }
                    } else {
                        service.lifecycleScope.launch { fcitx.activateIme(it.uniqueName) }
                    }
                    dialog.dismiss()
                }
                styledDrawable(android.R.attr.dividerHorizontal)?.let {
                    addItemDecoration(SingleDividerDecoration(it, dividerIndex))
                }
            })
            .setNeutralButton(R.string.input_methods) { _, _ ->
                AppUtil.launchMainToInputMethodList(context)
            }
            .create()
        return dialog
    }
}
