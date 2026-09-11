/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry

enum class ClipboardSearchCategory {
    All,
    Favorites,
    Local,
    Remote,
    Media
}

enum class ClipboardSearchDismissReason {
    ResultClick,
    Explicit
}

fun shouldDismissClipboardSearch(
    pinned: Boolean,
    reason: ClipboardSearchDismissReason
): Boolean = !pinned || reason == ClipboardSearchDismissReason.Explicit

fun clipboardSearchCommitText(entry: ClipboardEntry, pinned: Boolean): String =
    if (pinned && !entry.isUriEntry()) "${entry.text}\n" else entry.text

fun searchMediaEntries(
    entries: List<ClipboardEntry>,
    query: String,
    resolveFileName: (ClipboardEntry) -> String?
): List<ClipboardEntry> {
    val normalizedQuery = query.trim()
    return entries.asSequence()
        .filter(ClipboardEntry::isUriEntry)
        .filter {
            normalizedQuery.isEmpty() ||
                resolveFileName(it)?.contains(normalizedQuery, ignoreCase = true) == true
        }
        .sortedByDescending(ClipboardEntry::timestamp)
        .toList()
}

fun mergeClipboardSearchEntries(
    textEntries: List<ClipboardEntry>,
    mediaEntries: List<ClipboardEntry>
): List<ClipboardEntry> = (textEntries + mediaEntries)
    .sortedByDescending(ClipboardEntry::timestamp)

data class ClipboardSearchResult(
    val category: ClipboardSearchCategory,
    val usedAutomaticFallback: Boolean,
    val entries: List<ClipboardEntry>
)

suspend fun searchClipboardEntries(
    query: String,
    category: ClipboardSearchCategory,
    fallbackFromLocalToAll: Boolean,
    searchCategory: suspend (ClipboardSearchCategory, String) -> List<ClipboardEntry>
): ClipboardSearchResult {
    val normalizedQuery = query.trim()
    val entries = searchCategory(category, normalizedQuery)
    if (category != ClipboardSearchCategory.Local || !fallbackFromLocalToAll || entries.isNotEmpty()) {
        return ClipboardSearchResult(category, usedAutomaticFallback = false, entries)
    }
    return ClipboardSearchResult(
        category = ClipboardSearchCategory.All,
        usedAutomaticFallback = true,
        entries = searchCategory(ClipboardSearchCategory.All, normalizedQuery)
    )
}
