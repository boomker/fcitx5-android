/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js.app

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import org.fxboomk.fcitx5.android.input.cursor.CursorRange

/** Edit the panel directly: its non-focusable window never becomes the system IME target. */
internal class JsAppInputConnection(private val editor: EditText) : InputConnectionWrapper(
    requireNotNull(editor.onCreateInputConnection(EditorInfo())), false
) {
    val selection: CursorRange
        get() = CursorRange().apply {
            update(editor.selectionStart.coerceAtLeast(0), editor.selectionEnd.coerceAtLeast(0))
        }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // BaseInputConnection sends through ViewRoot/IMM, which requires a focused window.
        // Dispatch to this editor instead; unhandled keys must never reach the original app.
        editor.dispatchKeyEvent(event)
        return true
    }

    fun updateComposition(text: CharSequence, cursor: Int) {
        if (text.isEmpty() && BaseInputConnection.getComposingSpanStart(editor.text) < 0) return
        beginBatchEdit()
        try {
            setComposingText(text, 1)
            val start = BaseInputConnection.getComposingSpanStart(editor.text)
            if (start >= 0 && cursor >= 0) {
                val position = (start + cursor).coerceIn(start, minOf(start + text.length, editor.text.length))
                setSelection(position, position)
            }
        } finally { endBatchEdit() }
    }

    fun offsetSelection(startOffset: Int, endOffset: Int) {
        val range = selection
        val start = offsetByCharacters(range.start, startOffset)
        val end = offsetByCharacters(range.end, endOffset)
        if (start <= end) editor.setSelection(start, end)
    }

    fun deleteSelection() {
        if (selection.isNotEmpty()) commitText("", 1)
    }

    fun cancelSelection() {
        val end = selection.end
        editor.setSelection(end)
    }

    private fun offsetByCharacters(position: Int, offset: Int): Int {
        val text = editor.text
        val index = Character.codePointCount(text, 0, position)
        val count = Character.codePointCount(text, 0, text.length)
        return Character.offsetByCodePoints(text, 0, (index + offset).coerceIn(0, count))
    }

    companion object {
        /** Loading, permission prompts and read-only replies must consume keys locally too. */
        fun blocked(context: Context): InputConnection = object : BaseInputConnection(View(context), true) {
            override fun sendKeyEvent(event: KeyEvent) = true
            override fun commitText(text: CharSequence?, newCursorPosition: Int) = false
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
        }
    }
}
