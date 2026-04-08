/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.dialog

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.utils.inputMethodManager

data class InputMethodData(
    val uniqueName: String,
    val name: String,
    val ime: Boolean,
    val secondaryName: String? = null,
    val imeId: String? = null,
    val subtype: InputMethodSubtype? = null
) {
    companion object {
        suspend fun resolve(fcitx: FcitxAPI, context: Context): List<InputMethodData> {
            val imm = context.inputMethodManager
            val enabled = fcitx.enabledIme()
                .map { InputMethodData(it.uniqueName, it.displayName, false) }
                .toMutableList()

            enabled += imm.enabledInputMethodList
                .filter { it.packageName != context.packageName }
                .flatMap { imi ->
                    val imeLabel = imi.loadLabel(context.packageManager).toString()
                    val subtypes = imm.getEnabledInputMethodSubtypeList(imi, true)
                    if (subtypes.isEmpty()) {
                        listOf(InputMethodData(imi.id, imeLabel, true, imeId = imi.id))
                    } else {
                        subtypes.map { subtype ->
                            val subtypeLabel = subtype.getDisplayName(
                                context,
                                imi.packageName,
                                imi.serviceInfo.applicationInfo
                            ).toString().ifBlank { imeLabel }
                            InputMethodData(
                                uniqueName = "${imi.id}:${subtype.hashCode()}",
                                name = subtypeLabel,
                                ime = true,
                                secondaryName = imeLabel,
                                imeId = imi.id,
                                subtype = subtype
                            )
                        }
                    }
                }
            return enabled.toList()
        }
    }
}
