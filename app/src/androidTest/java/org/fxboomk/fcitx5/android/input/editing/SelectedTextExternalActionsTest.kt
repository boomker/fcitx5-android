/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import android.app.SearchManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SelectedTextExternalActionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wholeWebLinksOpenDirectlyAndBareDomainsGainHttps() {
        mapOf(
            " https://example.com/path?q=a%20b#part " to "https://example.com/path?q=a%20b#part",
            "http://127.0.0.1:8080/" to "http://127.0.0.1:8080/",
            "HTTPS://example.com/path" to "https://example.com/path",
            "www.example.com/path" to "https://www.example.com/path",
            "example.com" to "https://example.com",
        ).forEach { (selected, expected) ->
            assertEquals(expected, selectedTextLinkUri(selected)?.toString())
            val intent = selectedTextBrowserIntent(context, selected)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(expected, intent.dataString)
            assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test
    fun textContainingLinksAndNonWebSchemesRemainSearchQueries() {
        listOf("搜索 https://example.com", "example.com hello", "line\nexample.com", "javascript:alert(1)",
            "mailto:user@example.com", "file:///test.txt", "https://", "https://example.com/bad%", "")
            .forEach { assertNull(it, selectedTextLinkUri(it)) }
    }

    @Test
    fun searchUsesBrowserOwnSearchOrAnEncodedWebQuery() {
        val query = "中文 & a+b?#"
        val intent = selectedTextBrowserIntent(context, query)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        if (intent.action == Intent.ACTION_WEB_SEARCH) {
            assertEquals(query, intent.getStringExtra(SearchManager.QUERY))
            assertNotNull(intent.`package`)
        } else {
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https", intent.data?.scheme)
            assertEquals(query, intent.data?.getQueryParameter("q"))
        }
        // Link opening and searching must use the same default browser package.
        assertEquals(selectedTextBrowserIntent(context, "https://example.com").`package`, intent.`package`)
    }

    @Suppress("DEPRECATION")
    @Test
    fun shareChooserPreservesExactSelectedText() {
        val text = "  selected text\n中文\nhttps://example.com  "
        val chooser = selectedTextShareIntent(text)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val target = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals("text/plain", target.type)
        assertEquals(text, target.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(target.component)
        assertNull(target.`package`)
    }
}
