/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.core.os.bundleOf
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs

class PrefixListDialogFragment : DialogFragment() {

    private lateinit var editText: EditText
    private val prefs = AppPrefs.getInstance().advanced

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val currentPrefixes = prefs.allowedPluginPrefixes.getValue().toMutableSet()

        editText = EditText(context).apply {
            hint = getString(R.string.allowed_plugin_prefixes_add_hint)
            setText(currentPrefixes.joinToString("\n"))
            setLineSpacing(8f, 1f)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(editText)
        }

        editText.addTextChangedListener {
            // Validate input
        }

        return AlertDialog.Builder(context)
            .setTitle(R.string.allowed_plugin_prefixes_dialog_title)
            .setMessage(R.string.allowed_plugin_prefixes_dialog_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newPrefixes = editText.text.toString()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                prefs.allowedPluginPrefixes.setValue(newPrefixes)
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(UPDATED_KEY to true)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val TAG = "PrefixListDialogFragment"
        const val REQUEST_KEY = "allowed_plugin_prefixes_updated"
        const val UPDATED_KEY = "updated"
    }
}
