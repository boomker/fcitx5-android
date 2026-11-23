/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.widget.SearchView
import androidx.preference.PreferenceViewHolder
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchComposingTest {
    @Test
    fun rebindingPreservesCurrentCodeComposition() =
        assertCompositionSurvivesRebinding(listOf("n", "ni", "ni hao"))

    @Test
    fun rebindingPreservesHighlightedCandidateComposition() =
        assertCompositionSurvivesRebinding(listOf("你", "你好"))

    @Test
    fun candidateCanStillBeExplicitlyCommittedAfterRebinding() = withSearch { preference, holder, input, connection ->
        assertTrue(connection.setComposingText("ni hao", 1))
        preference.onBindViewHolder(holder)
        assertTrue(connection.commitText("你好", 1))
        preference.onBindViewHolder(holder)

        assertEquals("你好", input.text.toString())
        assertEquals("你好", preference.query)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(input.text))
        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(input.text))
    }

    @Test
    fun bindingAppliesADifferentSavedQuery() = withSearch { preference, holder, input, connection ->
        assertTrue(connection.setComposingText("ni", 1))
        preference.query = "theme"
        preference.onBindViewHolder(holder)

        assertEquals("theme", input.text.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(input.text))
    }

    private fun assertCompositionSurvivesRebinding(values: List<String>) =
        withSearch { preference, holder, input, connection ->
            val prefix = "theme "
            assertTrue(connection.commitText(prefix, 1))
            for (value in values) {
                assertTrue(connection.setComposingText(value, 1))
                val text = input.text
                val cursor = prefix.length + value.length / 2
                input.setSelection(cursor)

                repeat(2) {
                    // Search results refresh and rebind this same preference row.
                    preference.onBindViewHolder(holder)

                    assertSame(text, input.text)
                    assertEquals(prefix + value, input.text.toString())
                    assertEquals(prefix + value, preference.query)
                    assertEquals(prefix.length, BaseInputConnection.getComposingSpanStart(input.text))
                    assertEquals(prefix.length + value.length, BaseInputConnection.getComposingSpanEnd(input.text))
                    assertEquals(cursor, input.selectionStart)
                    assertEquals(cursor, input.selectionEnd)
                }
            }
        }

    private fun withSearch(
        block: (SettingsSearchPreference, PreferenceViewHolder, EditText, InputConnection) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                androidx.appcompat.R.style.Theme_AppCompat
            )
            val preference = SettingsSearchPreference(context).apply {
                onQueryChanged = { query = it }
            }
            val row = LayoutInflater.from(context)
                .inflate(preference.layoutResource, FrameLayout(context), false)
            val holder = PreferenceViewHolder.createInstanceForTests(row)
            preference.onBindViewHolder(holder)
            assertTrue(row.findViewById<View>(R.id.settings_search_placeholder).performClick())
            val searchView = row.findViewById<SearchView>(R.id.settings_search_view)
            val input = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
            val connection = requireNotNull(input.onCreateInputConnection(EditorInfo()))
            block(preference, holder, input, connection)
        }
    }
}
