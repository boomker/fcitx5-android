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
    fun automaticFallbackCombinesTextAndMediaWithoutLosingMediaIdentity() = runBlocking {
        val textEntry = entry(1).copy(timestamp = 100)
        val mediaEntry = entry(2).copy(
            text = "content://clipboard/document/2",
            type = "application/pdf",
            timestamp = 200
        )
        val result = searchClipboardEntries(
            query = "document",
            category = ClipboardSearchCategory.Local,
            fallbackFromLocalToAll = true,
            searchCategory = { category, _ ->
                when (category) {
                    ClipboardSearchCategory.Local -> emptyList()
                    ClipboardSearchCategory.All -> mergeClipboardSearchEntries(
                        textEntries = listOf(textEntry),
                        mediaEntries = listOf(mediaEntry)
                    )
                    else -> error("Unexpected category: $category")
                }
            }
        )

        assertEquals(ClipboardSearchCategory.All, result.category)
        assertTrue(result.usedAutomaticFallback)
        assertEquals(listOf(2, 1), result.entries.map(ClipboardEntry::id))
        assertTrue(result.entries.first().isUriEntry())
        assertEquals("application/pdf", result.entries.first().type)
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
    fun explicitMediaCategoryDoesNotFallBack() = runBlocking {
        var callCount = 0
        val result = searchClipboardEntries(
            query = "document",
            category = ClipboardSearchCategory.Media,
            fallbackFromLocalToAll = true,
            searchCategory = { category, _ ->
                callCount++
                assertEquals(ClipboardSearchCategory.Media, category)
                emptyList()
            }
        )

        assertEquals(1, callCount)
        assertEquals(ClipboardSearchCategory.Media, result.category)
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

    @Test
    fun pinnedStateOnlyBlocksImplicitDismissals() {
        assertTrue(shouldDismissClipboardSearch(false, ClipboardSearchDismissReason.ResultClick))
        assertFalse(shouldDismissClipboardSearch(true, ClipboardSearchDismissReason.ResultClick))
        assertTrue(shouldDismissClipboardSearch(false, ClipboardSearchDismissReason.Explicit))
        assertTrue(shouldDismissClipboardSearch(true, ClipboardSearchDismissReason.Explicit))
    }

    @Test
    fun pinnedTextCommitAppendsNewline() {
        val entry = entry(1).copy(text = "first\nsecond")

        assertEquals("first\nsecond", clipboardSearchCommitText(entry, pinned = false))
        assertEquals("first\nsecond\n", clipboardSearchCommitText(entry, pinned = true))
    }

    @Test
    fun pinnedUriCommitPreservesUri() {
        val entry = entry(1).copy(text = "content://clipboard/document/1")

        assertEquals(entry.text, clipboardSearchCommitText(entry, pinned = true))
    }

    @Test
    fun mediaSearchIncludesImagesAndMatchesFileNamesIgnoringCase() {
        val entries = listOf(
            entry(1).copy(text = "content://clipboard/1", type = "application/pdf"),
            entry(2).copy(text = "content://clipboard/2", type = "image/png"),
            entry(3).copy(type = "text/plain")
        )
        val names = mapOf(1 to "Meeting.PDF", 2 to "meeting.png", 3 to "notes.txt")

        val results = searchMediaEntries(entries, "meeting") { names[it.id] }

        assertEquals(listOf(2, 1), results.map(ClipboardEntry::id))
    }

    @Test
    fun emptyMediaSearchReturnsAllMediaNewestFirst() {
        val entries = listOf(
            entry(1).copy(text = "content://clipboard/1", type = "application/pdf", timestamp = 100),
            entry(2).copy(text = "content://clipboard/2", type = "image/jpeg", timestamp = 300),
            entry(3).copy(text = "content://clipboard/3", type = "application/zip", timestamp = 200)
        )

        val results = searchMediaEntries(entries, "  ") { "file-${it.id}" }

        assertEquals(listOf(2, 3, 1), results.map(ClipboardEntry::id))
    }

    @Test
    fun mediaSearchIgnoresTextEntriesEvenWhenFileNameMatches() {
        val entries = listOf(
            entry(1).copy(text = "plain text"),
            entry(2).copy(text = "file://clipboard/2", type = "application/pdf")
        )

        val results = searchMediaEntries(entries, "meeting") { "meeting-${it.id}" }

        assertEquals(listOf(2), results.map(ClipboardEntry::id))
    }
}
