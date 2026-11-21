/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.expanded

import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import timber.log.Timber

class CandidatesPagingSource(
    val fcitx: FcitxConnection,
    val total: Int,
    val offset: Int,
    private val excludedIndices: Set<Int> = emptySet(),
) :
    PagingSource<Int, CandidateWord>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CandidateWord> {
        // use candidate index for key, null means load from beginning (with offset)
        val startIndex = params.key ?: offset
        val pageSize = params.loadSize
        Timber.d("getExpandedCandidates(offset=$startIndex, limit=$pageSize, firstHiddenIndex=$offset)")
        val candidates = fcitx.runOnReady {
            getExpandedCandidates(startIndex, pageSize, offset, excludedIndices.toIntArray())
        }
        val prevKey = if (startIndex > offset) (startIndex - pageSize).coerceAtLeast(offset) else null
        val endIndex = startIndex + candidates.size
        // The expanded presentation can include folded alternatives beyond the native total.
        val nextKey = if (candidates.size < pageSize) null else endIndex
        val remaining = candidates.mapIndexedNotNull { index, candidate ->
            val nativeIndex = candidate.nativeIndex.takeIf { it >= 0 } ?: (startIndex + index)
            candidate.copy(nativeIndex = nativeIndex).takeUnless { nativeIndex in excludedIndices }
        }
        return LoadResult.Page(remaining, prevKey, nextKey)
    }

    // always reload from beginning
    override fun getRefreshKey(state: PagingState<Int, CandidateWord>) = null

}
