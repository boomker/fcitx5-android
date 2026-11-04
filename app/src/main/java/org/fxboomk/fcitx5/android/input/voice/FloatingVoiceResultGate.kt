/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

import androidx.annotation.MainThread
import org.fxboomk.fcitx5.android.input.EditorSessionTracker
import java.util.UUID

/** The optional broadcast fallback must prove both its voice request and its editor lifetime. */
@MainThread
internal class FloatingVoiceResultGate(private val newToken: () -> String = { UUID.randomUUID().toString() }) {
    private data class Session(val id: String, val editor: EditorSessionTracker.Token)
    private var active: Session? = null
    val currentToken: String? get() = active?.id

    fun begin(editor: EditorSessionTracker.Token): String {
        val id = newToken()
        require(id.isNotEmpty())
        active = Session(id, editor)
        return id
    }

    fun deliver(id: String?, final: Boolean, action: () -> Unit): Boolean {
        val session = active ?: return false
        if (id != session.id || !session.editor.isCurrent()) return false
        // Consume before calling application code: that code may start another voice session.
        if (final) active = null
        action()
        return true
    }

    fun clear() { active = null }
}
