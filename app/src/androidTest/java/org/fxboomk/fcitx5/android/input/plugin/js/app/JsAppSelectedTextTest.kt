/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js.app

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsAppSelectedTextTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun selectedResultPreviewAndConfirmationReplaceOnlyTheOriginalRange() = onMain {
        val editor = EditText(context).apply { setText("prefix source suffix"); setSelection(7, 13) }
        val bridge = bridge(editor)
        bridge.call("setComposition", "result")
        assertEquals("prefix result suffix", editor.text.toString())
        bridge.call("commit", "result")
        assertEquals("prefix result suffix", editor.text.toString())
    }

    @Test
    fun clearingAPreviewRestoresTheOriginalSelectionAndAllowsRegeneration() = onMain {
        val editor = EditText(context).apply { setText("prefix source suffix"); setSelection(7, 13) }
        val bridge = bridge(editor)
        bridge.call("setComposition", "result")
        bridge.call("clearComposition", "")
        assertEquals("prefix source suffix", editor.text.toString())
        assertEquals(7, editor.selectionStart)
        assertEquals(13, editor.selectionEnd)
        bridge.call("setComposition", "new result")
        assertEquals("prefix new result suffix", editor.text.toString())
    }

    @Test
    fun changedSameEditorSelectionCannotReceiveALateResult() = onMain {
        val editor = EditText(context).apply { setText("prefix source suffix"); setSelection(7, 13) }
        val bridge = bridge(editor)
        editor.setSelection(14, 20)
        rejected { bridge.call("setComposition", "late result") }
        assertEquals("prefix source suffix", editor.text.toString())
    }

    @Test
    fun changedSelectionOrContextAfterPreviewCannotBeCommittedOrRestored() = onMain {
        val editor = EditText(context).apply { setText("prefix source suffix"); setSelection(7, 13) }
        val bridge = bridge(editor)
        bridge.call("setComposition", "preview")
        editor.setSelection(0, 6)
        rejected { bridge.call("commit", "late result") }
        bridge.clearPreview()
        assertEquals("prefix preview suffix", editor.text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(editor.text))
        requireNotNull(editor.onCreateInputConnection(EditorInfo())).commitText("typed", 1)
        assertEquals("typed preview suffix", editor.text.toString())
    }

    @Test
    fun selectedInputIsNeverSilentlyTruncated() = onMain {
        val editor = EditText(context).apply { setText("x".repeat(65537)); setSelection(0, 65537) }
        try {
            bridge(editor, "x".repeat(65537))
            fail("Expected selection length rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("too long"))
        }
    }

    private fun bridge(editor: EditText, expected: String = "source") = JsAppEditor(
        requireNotNull(editor.onCreateInputConnection(EditorInfo())), { true }, {}, expectedSelection = expected
    )

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("Expected selection change rejection")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("changed"))
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
