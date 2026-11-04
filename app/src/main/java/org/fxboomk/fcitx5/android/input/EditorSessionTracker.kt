/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input

/** Main-thread editor lifetime. A same-editor restart keeps its asynchronous work valid. */
internal class EditorSessionTracker {
    data class Editor(val packageName: String?, val fieldId: Int, val inputType: Int, val imeOptions: Int)
    class Token internal constructor(private val owner: EditorSessionTracker, private val generation: Long) {
        fun isCurrent(): Boolean = owner.active && owner.generation == generation
    }

    private var generation = 0L
    private var active = false
    private var editor: Editor? = null

    /** Returns true when an existing editor's work must be cancelled. */
    fun start(editor: Editor, restarting: Boolean): Boolean {
        val changed = !active || !restarting || this.editor != editor
        if (changed) generation++
        active = true
        this.editor = editor
        return changed
    }

    fun capture(): Token? = if (active) Token(this, generation) else null

    fun end() {
        generation++
        active = false
        editor = null
    }
}
