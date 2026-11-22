/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import org.fxboomk.fcitx5.android.core.CandidateWord

/** Cap the first two slots together when large text would otherwise hide the recovery hint. */
internal fun fallbackCandidateRowWidthLimit(
    candidates: Array<CandidateWord>,
    availableWidth: Int,
    dividerWidth: Int,
    measureCandidateWidth: (CandidateWord) -> Int,
): Int {
    val fallback = candidates.firstOrNull { it.isFallback } ?: return Int.MAX_VALUE
    val first = candidates.firstOrNull { it !== fallback } ?: return Int.MAX_VALUE
    val divider = dividerWidth.coerceAtLeast(0)
    val pairWidth = measureCandidateWidth(first).coerceAtLeast(0).toLong() +
        measureCandidateWidth(fallback).coerceAtLeast(0) + divider.toLong() * 2
    return if (pairWidth > availableWidth) {
        ((availableWidth - divider * 2) / 2).coerceAtLeast(1)
    } else Int.MAX_VALUE
}

/** Reserve the second visible slot only while the engine requests the default recovery hint. */
internal fun placeFallbackCandidateInRow(
    candidates: Array<CandidateWord>,
    availableWidth: Int,
    dividerWidth: Int,
    maxCandidateCount: Int,
    measureCandidateWidth: (CandidateWord) -> Int,
): Array<CandidateWord> {
    val fallback = candidates.firstOrNull { it.isFallback } ?: return candidates
    val divider = dividerWidth.coerceAtLeast(0)
    if (availableWidth <= 0 || maxCandidateCount <= 0) return emptyArray()
    if (maxCandidateCount == 1 || availableWidth <= divider * 2 + 1) return arrayOf(fallback)
    val pairWidthLimit = fallbackCandidateRowWidthLimit(
        candidates, availableWidth, divider, measureCandidateWidth,
    )
    val fallbackWidth = measureCandidateWidth(fallback).coerceIn(0, pairWidthLimit) + divider
    // Oversized first and recovery candidates each retain a bounded, scrollable slot.
    var usedWidth = fallbackWidth.coerceAtMost(availableWidth)
    val row = ArrayList<CandidateWord>()
    for (candidate in candidates) {
        if (candidate === fallback) continue
        if (row.size + 1 >= maxCandidateCount) break
        val widthLimit = if (row.isEmpty()) pairWidthLimit else Int.MAX_VALUE
        val width = measureCandidateWidth(candidate).coerceIn(0, widthLimit) + divider
        if (usedWidth + width > availableWidth) break
        row.add(candidate)
        usedWidth += width
    }
    row.add(minOf(1, row.size), fallback)
    return row.toTypedArray()
}

/** Width caps for the exact displayed row; short items keep their natural width. */
internal fun protectedCandidateRowWidthLimits(
    candidates: Array<CandidateWord>,
    availableWidth: Int,
    dividerWidth: Int,
    measureCandidateWidth: (CandidateWord) -> Int,
): IntArray {
    if (candidates.isEmpty()) return IntArray(0)
    val widths = candidates.map { measureCandidateWidth(it).coerceAtLeast(0) }
    val budget = (availableWidth.toLong() - dividerWidth.coerceAtLeast(0).toLong() * candidates.size)
        .coerceAtLeast(candidates.size.toLong())
    if (widths.sumOf { it.toLong() } <= budget) return IntArray(candidates.size) { Int.MAX_VALUE }
    var low = 1
    var high = widths.maxOrNull()?.coerceAtLeast(1) ?: 1
    while (low < high) {
        val middle = low + (high - low + 1) / 2
        if (widths.sumOf { minOf(it, middle).toLong() } <= budget) low = middle else high = middle - 1
    }
    return IntArray(candidates.size) { low }
}

/** Keep the best abbreviation in score order if visible, otherwise reserve the final slot. */
internal fun placeAbbreviationCandidateInRow(
    candidates: Array<CandidateWord>,
    availableWidth: Int,
    dividerWidth: Int,
    maxCandidateCount: Int,
    measureCandidateWidth: (CandidateWord) -> Int,
): Array<CandidateWord> {
    val best = candidates.firstOrNull { it.isBestAbbreviation }
        ?: return placeFallbackCandidateInRow(
            candidates, availableWidth, dividerWidth, maxCandidateCount, measureCandidateWidth,
        )
    if (availableWidth <= 0 || maxCandidateCount <= 0) return emptyArray()
    val fallback = candidates.firstOrNull { it.isFallback }
    val divider = dividerWidth.coerceAtLeast(0)
    val capacity = minOf(maxCandidateCount, (availableWidth / (divider + 1)).coerceAtLeast(1))
    if (capacity == 1) return arrayOf(fallback ?: best)

    val natural = if (fallback != null) {
        placeFallbackCandidateInRow(candidates, availableWidth, divider, capacity, measureCandidateWidth)
    } else {
        var occupied = 0L
        candidates.takeWhile { candidate ->
            val width = measureCandidateWidth(candidate).coerceAtLeast(0).toLong() + divider
            val fits = occupied == 0L || occupied + width <= availableWidth
            if (fits) occupied += width
            fits
        }.take(capacity).toTypedArray()
    }
    if (natural.any { it === best }) return natural

    val first = candidates.firstOrNull { it !== best && it !== fallback }
    val required = buildList {
        if (first != null && capacity >= if (fallback != null) 3 else 2) add(first)
        fallback?.let(::add)
        add(best)
    }.toTypedArray()
    val caps = protectedCandidateRowWidthLimits(required, availableWidth, divider, measureCandidateWidth)
    val widthOf: (CandidateWord) -> Int = { candidate ->
        val requiredIndex = required.indexOfFirst { it === candidate }
        val cap = caps.getOrNull(requiredIndex) ?: Int.MAX_VALUE
        measureCandidateWidth(candidate).coerceIn(0, cap) + divider
    }
    var occupied = widthOf(best).toLong() + (fallback?.let(widthOf) ?: 0)
    val row = ArrayList<CandidateWord>()
    val reservedCount = if (fallback != null) 2 else 1
    for (candidate in candidates) {
        if (candidate === best || candidate === fallback) continue
        if (row.size + reservedCount >= capacity) break
        val width = widthOf(candidate)
        if (occupied + width > availableWidth) break
        row.add(candidate)
        occupied += width
    }
    if (fallback != null && row.isEmpty()) return arrayOf(best, fallback)
    fallback?.let { row.add(minOf(1, row.size), it) }
    row.add(best)
    return row.toTypedArray()
}

/** A new first page follows current native ranking; only later pages skip the shown hint. */
internal fun nativeCandidatesAfterConsumedFallbacks(
    candidates: Array<CandidateWord>,
    indexOffset: Int,
    excludedIndices: Set<Int>,
    isInitialRow: Boolean = indexOffset == 0,
): Array<CandidateWord> {
    if (isInitialRow) {
        if (candidates.none { it.isDeferredFallback || it.isAbbreviation && !it.isBestAbbreviation }) return candidates
        return candidates.mapIndexedNotNull { index, candidate ->
            val nativeIndex = candidate.nativeIndex.takeIf { it >= 0 } ?: (indexOffset + index)
            candidate.copy(nativeIndex = nativeIndex).takeUnless {
                it.isDeferredFallback || it.isAbbreviation && !it.isBestAbbreviation
            }
        }.toTypedArray()
    }
    if (excludedIndices.isEmpty()) return candidates
    return candidates.mapIndexedNotNull { index, candidate ->
        val nativeIndex = candidate.nativeIndex.takeIf { it >= 0 } ?: (indexOffset + index)
        candidate.copy(nativeIndex = nativeIndex).takeUnless { nativeIndex in excludedIndices }
    }.toTypedArray()
}

internal data class NativeCandidateContinuation(
    val nextOffset: Int,
    val excludedIndices: Set<Int>,
)

/** Keep gaps available to paging while excluding later items the current row already shows. */
internal fun nativeCandidateContinuation(
    start: Int,
    displayed: Array<CandidateWord>,
    excludedIndices: Set<Int>,
): NativeCandidateContinuation {
    val consumed = displayed.mapIndexedTo(HashSet()) { index, candidate ->
        candidate.nativeIndex.takeIf { it >= 0 } ?: (start + index)
    }
    consumed.addAll(excludedIndices)
    var next = start
    while (next in consumed) next++
    return NativeCandidateContinuation(next, consumed.filterTo(HashSet()) { it >= next })
}

/** The first unseen engine candidate; a recovery item can be consumed ahead of it. */
internal fun nextNativeCandidateOffset(
    start: Int,
    displayed: Array<CandidateWord>,
    excludedIndices: Set<Int>,
): Int = nativeCandidateContinuation(start, displayed, excludedIndices).nextOffset

/** Map the engine cursor only when rendering; keep its original value for navigation. */
internal fun displayedNativeCandidateIndex(
    nativeIndex: Int,
    candidates: Array<CandidateWord>,
    indexOffset: Int,
): Int {
    if (candidates.isEmpty()) return -1
    val mapped = candidates.indexOfFirst { it.nativeIndex >= 0 && it.nativeIndex == nativeIndex }
    return mapped.takeIf { it >= 0 } ?: (nativeIndex - indexOffset).coerceIn(candidates.indices)
}
