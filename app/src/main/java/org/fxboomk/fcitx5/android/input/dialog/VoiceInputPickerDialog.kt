/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.dialog

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.voice.VoiceInputChoices
import org.fxboomk.fcitx5.android.input.plugin.js.JsPluginCatalog
import org.fxboomk.fcitx5.android.ui.main.settings.plugin.js.JsPluginSettingsActivity
import org.fxboomk.fcitx5.android.utils.styledColorOrDefault
import android.graphics.Color
import android.content.res.ColorStateList

internal object VoiceInputPickerDialog {
    fun build(context: Context, onSelected: (String) -> Unit): AlertDialog {
        val entries = VoiceInputChoices.list(context)
        val current = AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()
        // The IME service context is not a Material Components/AppCompat theme.
        // MaterialAlertDialogBuilder throws unless we wrap it explicitly.
        val themed = ContextThemeWrapper(context, R.style.Theme_FcitxAppTheme)
        val builder = MaterialAlertDialogBuilder(themed)
        // Resolve colors explicitly: the IME theme chain does not define every Material
        // color attr under some OEM themes, and a state-list XML would fail to inflate.
        val checkedColor = builder.context.styledColorOrDefault(
            android.R.attr.colorControlActivated,
            builder.context.styledColorOrDefault(android.R.attr.colorAccent, Color.BLUE),
        )
        val uncheckedColor = builder.context.styledColorOrDefault(
            android.R.attr.colorControlNormal,
            builder.context.styledColorOrDefault(android.R.attr.textColorSecondary, Color.GRAY),
        )
        val choiceTint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(),
            ),
            intArrayOf(checkedColor, uncheckedColor, uncheckedColor),
        )
        val labels = entries.map { it.label + "\n" + it.source }
        val adapter = object : ArrayAdapter<String>(builder.context, android.R.layout.simple_list_item_single_choice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as CheckedTextView).apply {
                    // Platform single-choice rows can inherit legacy/OEM control colors.
                    // Bind every state to the dialog's Material 3 color roles explicitly.
                    checkMarkTintList = choiceTint
                    isSingleLine = false
                    maxLines = 4
                    text = SpannableString(labels[position]).apply {
                        val start = entries[position].label.length + 1
                        setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        val secondaryColor = runCatching {
                            MaterialColors.getColor(
                                builder.context,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                                MaterialColors.getColor(builder.context, android.R.attr.textColorSecondary, 0),
                            )
                        }.getOrElse {
                            MaterialColors.getColor(
                                builder.context,
                                android.R.attr.textColorSecondary,
                                android.graphics.Color.GRAY,
                            )
                        }
                        setSpan(
                            ForegroundColorSpan(secondaryColor),
                            start,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
        }
        return builder.setTitle(R.string.choose_voice_engine)
            .setSingleChoiceItems(adapter, entries.indexOfFirst { it.id == current }) { dialog, index ->
                val id = entries[index].id
                if (id != current) {
                    AppPrefs.getInstance().keyboard.preferredVoiceInput.setValue(id)
                    onSelected(id)
                }
                dialog.dismiss()
            }
            .setNeutralButton(R.string.js_voice_plugins_settings) { dialog, _ ->
                dialog.dismiss()
                val plugin = JsPluginCatalog.specOf(current) ?: return@setNeutralButton
                context.startActivity(JsPluginSettingsActivity.intent(context, plugin.id).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
