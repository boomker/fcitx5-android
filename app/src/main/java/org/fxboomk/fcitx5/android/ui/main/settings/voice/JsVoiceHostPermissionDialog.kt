/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.voice

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceHostPermissions
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders

internal object JsVoiceHostPermissionDialog {
    fun build(context: Context, spec: JsVoiceProviders.Spec, onChanged: () -> Unit): AlertDialog {
        // The IME service context carries the keyboard theme, which is not a Material
        // Components theme; wrap it so MaterialAlertDialogBuilder can resolve its attrs.
        val themed = ContextThemeWrapper(context, R.style.Theme_FcitxAppTheme)
        val builder = MaterialAlertDialogBuilder(themed)
            .setTitle(R.string.js_voice_network_title)
            .setMessage(context.getString(
                R.string.js_voice_network_message, spec.label, spec.hosts.sorted().joinToString("\n"),
            ))
            .setNegativeButton(android.R.string.cancel, null)
        if (JsVoiceHostPermissions.isApproved(spec.id, spec.hosts)) {
            builder.setPositiveButton(R.string.js_voice_network_revoke) { _, _ ->
                JsVoiceHostPermissions.revoke(spec.id)
                onChanged()
            }
        } else {
            builder.setPositiveButton(R.string.js_voice_network_approve) { _, _ ->
                JsVoiceHostPermissions.approve(spec.id, spec.hosts)
                onChanged()
            }
        }
        return builder.create()
    }
}
