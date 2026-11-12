/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.modified

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.use
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import org.fxboomk.fcitx5.android.R

class MyListPreferenceDialogFragment : ListPreferenceDialogFragmentCompat() {
    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val p = preference as ListPreference
        builder.setNeutralButton(R.string.default_) { _, _ ->
            p.restore()
        }
        super.onPrepareDialogBuilder(builder)
        if (p is EntryEnabledListPreference) {
            val dialogContext = builder.context
            val singleChoiceItemLayout = dialogContext.obtainStyledAttributes(
                null, AppCompatR.styleable.AlertDialog, AppCompatR.attr.alertDialogStyle, 0
            ).use {
                it.getResourceId(AppCompatR.styleable.AlertDialog_singleChoiceItemLayout, 0)
            }
            val adapter = object : ArrayAdapter<CharSequence>(
                dialogContext, singleChoiceItemLayout, android.R.id.text1, p.entries
            ) {
                override fun areAllItemsEnabled(): Boolean = false

                override fun isEnabled(position: Int): Boolean =
                    p.isEntryEnabled(p.entryValues[position].toString())

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val enabled = isEnabled(position)
                    return super.getView(position, convertView, parent).apply {
                        isEnabled = enabled
                    }
                }
            }
            builder.setSingleChoiceItems(adapter, p.findIndexOfValue(p.value)) { dialog, index ->
                val value = p.entryValues[index].toString()
                if (p.callChangeListener(value)) {
                    p.value = value
                    dialog.dismiss()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val p = preference as? EntryEnabledListPreference ?: return
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled =
            p.isEntryEnabled(p.defaultEntryValue)
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        fixDialogMargin(view)
    }

    companion object {
        fun newInstance(key: String): MyListPreferenceDialogFragment {
            val fragment = MyListPreferenceDialogFragment()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }
    }
}
