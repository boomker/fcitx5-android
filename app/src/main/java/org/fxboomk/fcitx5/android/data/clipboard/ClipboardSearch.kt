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
    Remote
}

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
