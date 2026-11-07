/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.plugin.js

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import androidx.preference.EditTextPreference

/** A fixed mask represents an existing credential without reading it back into the editor. */
internal class JsPluginSecretPreference(context: Context, hasSavedSecret: () -> Boolean) : EditTextPreference(context) {
    private var showingSavedSecret = false
    private var edited = false

    init {
        setOnBindEditTextListener { editor ->
            editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            showingSavedSecret = hasSavedSecret()
            edited = false
            editor.setText(if (showingSavedSecret) MASK else "")
            editor.setSelection(0, editor.text.length)
            editor.addTextChangedListener(object : TextWatcher {
                private var replacingMask = false
                private var inserted = ""

                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!replacingMask && showingSavedSecret && !edited) {
                        inserted = text?.subSequence(start, start + count)?.toString().orEmpty()
                    }
                }

                override fun afterTextChanged(text: Editable?) {
                    if (replacingMask) return
                    if (showingSavedSecret && !edited) {
                        edited = true
                        // A partial edit or append replaces the whole mask, never a part of a key.
                        replacingMask = true
                        try {
                            text?.replace(0, text.length, inserted)
                            editor.setSelection(editor.text.length)
                        } finally {
                            replacingMask = false
                        }
                    } else edited = true
                }
            })
        }
    }

    override fun callChangeListener(newValue: Any?): Boolean {
        // Accepting the untouched mask must not overwrite or clear the encrypted credential.
        if (showingSavedSecret && !edited) return false
        return super.callChangeListener(newValue)
    }

    companion object {
        private const val MASK = "••••••••"
    }
}
