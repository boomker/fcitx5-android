/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import androidx.annotation.DrawableRes
import android.graphics.drawable.Drawable
import android.icu.text.BreakIterator
import android.os.Build
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.input.action.ButtonAction

sealed class StatusAreaEntry(
    val label: String,
    @DrawableRes
    val icon: Int,
    val iconText: String? = null,
    val active: Boolean,
    val customIcon: Drawable? = null,
    // Text rendered inside the circle when no icon is set; falls back to the
    // first character of label.
    val glyph: String? = null
) {
    /**
     * Status Area entry backed by a ButtonAction
     */
    class ActionEntry(
        val buttonAction: ButtonAction,
        label: String,
        icon: Int,
        iconText: String? = null,
        customIcon: Drawable? = null,
        active: Boolean = false,
        val longPressAction: LongPressActionType? = null
    ) : StatusAreaEntry(label, icon, iconText, active, customIcon) {
        enum class LongPressActionType {
            EnterAdjustingMode
        }
    }

    class Android(label: String, icon: Int, val type: Type, active: Boolean = false) :
        StatusAreaEntry(label, icon, null, active) {
        enum class Type {
            InputMethod,
            ReloadConfig,
            Keyboard,
            ThemeList,
            OneHandKeyboard
        }
    }

    class Fcitx(
        val action: Action,
        label: String,
        icon: Int,
        active: Boolean,
        glyph: String? = null
    ) : StatusAreaEntry(label, icon, null, active, glyph = glyph)

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
            val selector = selectorParts(it)
            return Fcitx(
                it,
                selector?.first ?: it.shortText,
                drawableFromIconName(it.icon),
                active,
                glyph = selector?.second?.let { value -> firstCharacter(value) }
            )
        }

        /**
         * A multi-valued selector action (one carrying a menu) composes its
         * shortText as "name <arrow> current value". A plain toggle instead
         * reads "current <arrow> next" and must keep the raw text.
         */
        private fun selectorParts(action: Action): Pair<String, String>? {
            if (action.menu.isNullOrEmpty()) return null
            val index = firstArrowIndex(action.shortText)
            if (index <= 0) return null
            val name = action.shortText.substring(0, index).trim()
            val value = action.shortText.substring(index + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return null
            return name to value
        }

        /**
         * Arrow characters schemas embed in switch labels to separate the
         * switch name from its value; they are not consistent about which one.
         */
        private val ARROWS = charArrayOf('→', '➜')

        fun firstArrowIndex(text: String): Int {
            var first = -1
            for (arrow in ARROWS) {
                val index = text.indexOf(arrow)
                if (index >= 0 && (first < 0 || index < first)) first = index
            }
            return first
        }

        fun firstCharacter(s: String): String {
            if (s.isEmpty()) return ""
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val iterator = BreakIterator.getCharacterInstance()
                iterator.setText(s)
                s.substring(iterator.first(), iterator.next())
            } else {
                s.substring(0, s.offsetByCodePoints(0, 1))
            }
        }
    }
}
