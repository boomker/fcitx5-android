/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

/** Records actual first-row slots; paging cannot turn a later row into the first page. */
internal class FirstPageCandidateTracker {
    private var indices: Set<Int>? = null

    fun record(visibleIndices: Set<Int>, isFirstPage: Boolean) {
        if (isFirstPage) indices = visibleIndices.toSet()
    }

    fun clear() {
        indices = null
    }

    fun contains(nativeIndex: Int, nativePageHasPrevious: Boolean = false): Boolean? =
        if (nativePageHasPrevious) false else indices?.contains(nativeIndex)
}
