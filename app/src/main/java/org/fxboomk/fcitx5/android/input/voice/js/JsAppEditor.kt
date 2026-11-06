/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** Owns only this app's temporary composition, never the current/new editor's text. */
internal class JsAppEditor(
    private val connection: InputConnection,
    private val isCurrent: () -> Boolean,
    private val requirePermission: (String) -> Unit,
) {
    private var preview = false
    private var originalSelection = ""
    private var originalStart = -1
    private var originalEnd = -1

    fun call(method: String, text: String): String? {
        check(isCurrent()) { "Editor session ended" }
        require(text.length <= 65536) { "Text is too long" }
        when (method) {
            "getSelection" -> {
                requirePermission("editor.read_selection")
                return connection.getSelectedText(0)?.take(65536)?.toString().orEmpty()
            }
            "getText" -> {
                requirePermission("editor.read_text")
                return connection.getTextBeforeCursor(21845, 0)?.toString().orEmpty() +
                    connection.getSelectedText(0)?.take(21845)?.toString().orEmpty() +
                    connection.getTextAfterCursor(21845, 0)?.toString().orEmpty()
            }
            "setComposition" -> {
                requirePermission("editor.composition")
                if (!preview) {
                    originalSelection = connection.getSelectedText(0)?.toString().orEmpty()
                    require(originalSelection.length <= 65536) { "Selection is too long" }
                    val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
                    originalStart = extracted?.let { it.startOffset + it.selectionStart } ?: -1
                    originalEnd = extracted?.let { it.startOffset + it.selectionEnd } ?: -1
                }
                check(connection.setComposingText(text, 1)) { "Editor rejected preview" }
                preview = true
            }
            "clearComposition" -> { requirePermission("editor.composition"); clearPreview() }
            "commit" -> {
                requirePermission("editor.commit")
                check(connection.commitText(text, 1)) { "Editor rejected text" }
                connection.finishComposingText()
                preview = false
            }
            else -> error("Unsupported editor operation")
        }
        return null
    }

    fun clearPreview() {
        if (!preview) return
        if (isCurrent()) {
            connection.setComposingText(originalSelection, 1)
            connection.finishComposingText()
            if (originalStart >= 0 && originalEnd >= 0) connection.setSelection(originalStart, originalEnd)
        }
        preview = false
    }
}
