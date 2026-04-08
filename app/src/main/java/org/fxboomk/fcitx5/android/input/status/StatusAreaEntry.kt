/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import androidx.annotation.DrawableRes
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.input.action.ButtonAction

sealed class StatusAreaEntry(
    val label: String,
    @DrawableRes
    val icon: Int,
    val active: Boolean
) {
    /**
     * Status Area entry backed by a ButtonAction
     */
    class ActionEntry(
        val buttonAction: ButtonAction,
        label: String,
        icon: Int,
        active: Boolean = false,
        val longPressAction: LongPressActionType? = null
    ) : StatusAreaEntry(label, icon, active) {
        enum class LongPressActionType {
            EnterAdjustingMode
        }
    }

    class Android(label: String, icon: Int, val type: Type, active: Boolean = false) :
        StatusAreaEntry(label, icon, active) {
        enum class Type {
            InputMethod,
            ReloadConfig,
            Keyboard,
            ThemeList,
            OneHandKeyboard
        }
    }

    class Fcitx(val action: Action, label: String, icon: Int, active: Boolean) :
        StatusAreaEntry(label, icon, active)

    companion object {
        private fun drawableFromIconName(icon: String) = when (icon) {
            // androidkeyboard
            "tools-check-spelling" -> R.drawable.ic_baseline_spellcheck_24
            // fcitx5-chinese-addons
            "fcitx-chttrans-active" -> R.drawable.ic_fcitx_status_chttrans_trad
            "fcitx-chttrans-inactive" -> R.drawable.ic_fcitx_status_chttrans_simp
            "fcitx-punc-active" -> R.drawable.ic_fcitx_status_punc_active
            "fcitx-punc-inactive" -> R.drawable.ic_fcitx_status_punc_inactive
            "fcitx-fullwidth-active" -> R.drawable.ic_fcitx_status_fullwidth_active
            "fcitx-fullwidth-inactive" -> R.drawable.ic_fcitx_status_fullwidth_inactive
            "fcitx-remind-active" -> R.drawable.ic_fcitx_status_prediction_active
            "fcitx-remind-inactive" -> R.drawable.ic_fcitx_status_prediction_inactive
            // fcitx5-unikey
            "document-edit" -> R.drawable.ic_baseline_edit_24
            "character-set" -> R.drawable.ic_baseline_text_format_24
            "edit-find" -> R.drawable.ic_baseline_search_24
            // fallback
            "" -> 0
            else -> {
                if (icon.endsWith("-inactive")) {
                    R.drawable.ic_baseline_code_off_24
                } else {
                    R.drawable.ic_baseline_code_24
                }
            }
        }

        fun fromAction(it: Action): Fcitx {
            val active = it.icon.endsWith("-active") || it.isChecked
            return Fcitx(it, it.shortText, drawableFromIconName(it.icon), active)
        }
    }
}