/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.plugin.js

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import androidx.preference.ListPreference
import androidx.preference.ListPreferenceDialogFragmentCompat
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.modified.fixDialogMargin

private const val ARG_RESET_VALUE = "reset_value"

/** Plugin settings save through their change listeners instead of Preference persistence. */
class JsPluginEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {
    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        arguments?.getString(ARG_RESET_VALUE)?.let { value ->
            builder.setNeutralButton(R.string.default_) { _, _ ->
                val target = preference as EditTextPreference
                if (target.callChangeListener(value)) target.text = value
            }
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        fixDialogMargin(view)
    }

    companion object {
        fun newInstance(key: String, resetValue: String?) = JsPluginEditTextPreferenceDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_KEY, key)
                if (resetValue != null) putString(ARG_RESET_VALUE, resetValue)
            }
        }
    }
}

class JsPluginListPreferenceDialogFragment : ListPreferenceDialogFragmentCompat() {
    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)
        arguments?.getString(ARG_RESET_VALUE)?.let { value ->
            builder.setNeutralButton(R.string.default_) { _, _ ->
                val target = preference as ListPreference
                if (target.callChangeListener(value)) target.value = value
            }
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)
        fixDialogMargin(view)
    }

    companion object {
        fun newInstance(key: String, resetValue: String?) = JsPluginListPreferenceDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_KEY, key)
                if (resetValue != null) putString(ARG_RESET_VALUE, resetValue)
            }
        }
    }
}
