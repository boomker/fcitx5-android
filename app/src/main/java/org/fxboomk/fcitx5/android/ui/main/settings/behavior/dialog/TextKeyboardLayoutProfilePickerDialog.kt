/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.utils.toast

object TextKeyboardLayoutProfilePickerDialog {

    fun build(
        context: Context,
        onProfileSelected: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ): AlertDialog {
        val current = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
            AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
        ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

        val profiles = UserConfigFiles.listTextKeyboardLayoutProfiles().toMutableList().apply {
            if (current !in this) add(current)
        }
        val sortedProfiles = profiles
            .distinct()
            .sortedWith(compareBy({ it != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
        val labels = sortedProfiles.map { profile -> context.displayProfile(profile) }.toTypedArray()
        val selected = sortedProfiles.indexOf(current).coerceAtLeast(0)

        return AlertDialog.Builder(context)
            .setTitle(R.string.text_keyboard_layout_file_select_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val target = sortedProfiles.getOrNull(which) ?: return@setSingleChoiceItems
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(target)
                ConfigProviders.provider = ConfigProviders.provider
                context.toast(
                    context.getString(
                        R.string.text_keyboard_layout_file_select_summary,
                        context.displayProfile(target)
                    )
                )
                onProfileSelected?.invoke(target)
                dialog.dismiss()
            }
            .setOnDismissListener { onDismiss?.invoke() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun Context.displayProfile(profile: String): String {
        return if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            getString(R.string.default_)
        } else {
            profile
        }
    }
}
