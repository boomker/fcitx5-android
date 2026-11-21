/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.fxboomk.fcitx5.android.core.CandidateViewportSnapshot
import org.fxboomk.fcitx5.android.core.CandidateWord

internal data class CandidateViewportProbe(
    val candidates: Array<CandidateWord>,
    val firstSymbolIndex: Int,
    val needsMoreCandidates: Boolean,
) {
    val firstPageIndices: IntArray
        get() = candidates.map { it.nativeIndex }.toIntArray()
}

/** Measure the proposed first page without publishing preview identities to a clickable row. */
internal fun probeCandidateViewport(
    snapshot: CandidateViewportSnapshot,
    availableWidth: Int,
    normalizeRow: (Array<CandidateWord>) -> Array<CandidateWord>,
    measureCandidateWidth: (CandidateWord) -> Int,
): CandidateViewportProbe {
    val words = snapshot.candidates.mapIndexed { index, candidate ->
        if (candidate.nativeIndex >= 0) candidate else candidate.copy(nativeIndex = index)
    }
    if (!snapshot.foldingEnabled) {
        return CandidateViewportProbe(normalizeRow(words.toTypedArray()), -1, false)
    }

    val ordinary = words.filter { it.symbolSourceId < 0 }
    val ordinaryRow = normalizeRow(ordinary.toTypedArray())
    val visibleSources = ordinaryRow.mapTo(HashSet()) { it.nativeIndex }
    val groups = words.filter { it.symbolSourceId >= 0 }.groupBy { it.symbolSourceId }
    var row = ordinaryRow
    var firstSymbolIndex = -1

    val orderedGroups = groups.values.sortedBy { group ->
        val source = ordinary.indexOfFirst { it.nativeIndex == group.first().symbolSourceId }
        if (source >= 0) source + 1 else words.takeWhile { it !== group.first() }
            .count { it.symbolSourceId < 0 }
    }
    for (group in orderedGroups) {
        val originalHead = group.first()
        val source = ordinary.indexOfFirst { it.nativeIndex == originalHead.symbolSourceId }
        if (source >= 0 && originalHead.symbolSourceId !in visibleSources) continue
        val insertion = if (source >= 0) source + 1 else words.takeWhile { it !== originalHead }
            .count { it.symbolSourceId < 0 }
        val groupSize = maxOf(group.size, group.maxOf { it.symbolCount })
        val head = originalHead.copy(
            comment = if (groupSize > 1) "…" else "",
            displayComment = null,
            spaceBetweenComment = false,
        )
        // The existing horizontal row keeps an oversized first item in one bounded slot.
        if (measureCandidateWidth(head) > availableWidth && !(ordinaryRow.isEmpty() && insertion == 0)) continue
        val proposal = ordinary.toMutableList().apply { add(insertion, head) }
        val proposedRow = normalizeRow(proposal.toTypedArray())
        if (proposedRow.none { it.nativeIndex == head.nativeIndex && it.symbolSourceId >= 0 }) continue
        row = proposedRow
        firstSymbolIndex = head.nativeIndex
        break
    }

    val incomplete = snapshot.total < 0 || words.size < snapshot.total
    val allKnownOrdinaryFit = row.count { it.symbolSourceId < 0 } == ordinary.size
    // A cut through deferred groups can hide the head belonging to an earlier visible source.
    val endsInsideSymbols = words.lastOrNull()?.symbolSourceId?.let { it >= 0 } == true
    val consumesBoundaryRoot = row.any { it.symbolSourceId < 0 && it.nativeIndex == words.lastOrNull()?.nativeIndex }
    return CandidateViewportProbe(
        row,
        firstSymbolIndex,
        incomplete && (allKnownOrdinaryFit || endsInsideSymbols || consumesBoundaryRoot),
    )
}
