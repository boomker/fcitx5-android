/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import kotlinx.coroutines.runBlocking
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSearchTest {

    private fun entry(id: Int) = ClipboardEntry(id = id, text = "entry-$id", timestamp = id.toLong())

    @Test
    fun emptyQueryLoadsSelectedCategory() = runBlocking {
        val received = mutableListOf<Pair<ClipboardSearchCategory, String>>()
        val result = searchClipboardEntries(
            query = "  ",
            category = ClipboardSearchCategory.Favorites,
            fallbackFromLocalToAll = false,
            searchCategory = { category, query ->
                received += category to query
                listOf(entry(1))
            }
        )

        assertEquals(listOf(ClipboardSearchCategory.Favorites to ""), received)
        assertEquals(ClipboardSearchCategory.Favorites, result.category)
        assertFalse(result.usedAutomaticFallback)
        assertEquals(listOf(entry(1)), result.entries)
    }

    @Test
    fun localMatchesTakePriority() = runBlocking {
        var allCalled = false
        val result = searchClipboardEntries(
            query = "query",
            category = ClipboardSearchCategory.Local,
            fallbackFromLocalToAll = true,
            searchCategory = { category, _ ->
                if (category == ClipboardSearchCategory.Local) listOf(entry(1))
                else { allCalled = true; listOf(entry(2)) }
            }
        )

        assertFalse(allCalled)
        assertEquals(ClipboardSearchCategory.Local, result.category)
        assertFalse(result.usedAutomaticFallback)
        assertEquals(listOf(entry(1)), result.entries)
    }

    @Test
    fun allHistoryIsSearchedWhenLocalHasNoMatch() = runBlocking {
        val result = searchClipboardEntries(
            query = "query",
            category = ClipboardSearchCategory.Local,
            fallbackFromLocalToAll = true,
            searchCategory = { category, _ ->
                if (category == ClipboardSearchCategory.Local) emptyList() else listOf(entry(2))
            }
        )

        assertEquals(ClipboardSearchCategory.All, result.category)
        assertTrue(result.usedAutomaticFallback)
        assertEquals(listOf(entry(2)), result.entries)
    }

    @Test
    fun explicitCategoryDoesNotFallBack() = runBlocking {
        var callCount = 0
        val result = searchClipboardEntries(
            query = "query",
            category = ClipboardSearchCategory.Remote,
            fallbackFromLocalToAll = false,
            searchCategory = { category, _ ->
                callCount++
                assertEquals(ClipboardSearchCategory.Remote, category)
                emptyList()
            }
        )

        assertEquals(1, callCount)
        assertEquals(ClipboardSearchCategory.Remote, result.category)
        assertFalse(result.usedAutomaticFallback)
    }

    @Test
    fun queryIsTrimmedBeforeSearches() = runBlocking {
        val received = mutableListOf<String>()
        searchClipboardEntries(
            query = "  query  ",
            category = ClipboardSearchCategory.Local,
            fallbackFromLocalToAll = true,
            searchCategory = { _, query -> received += query; emptyList() }
        )

        assertEquals(listOf("query", "query"), received)
    }
}
