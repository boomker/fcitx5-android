/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.core

data class CandidateWord @JvmOverloads constructor(
    val label: String,
    val text: String,
    val comment: String,
    val spaceBetweenComment: Boolean = true,
    /** Default recovery placement hint; adaptive corrections retain their native rank. */
    val isFallback: Boolean = false,
    /** Manually deferred recovery: omit it from the initial row, keep it in later candidates. */
    val isDeferredFallback: Boolean = false,
    /** Original engine index when a UI row changes candidate order. */
    val nativeIndex: Int = -1,
    /** Presentation-only comment; [comment] retains the native candidate metadata. */
    val displayComment: String? = null,
    /** Folded symbol action represented as its own expanded candidate. */
    val selectionActionId: Int = -1,
    /** Native head text checked before selecting a projected folded symbol. */
    val actionSourceText: String? = null,
    /** Source identity within a [CandidateViewportSnapshot]; -1 for ordinary candidates. */
    val symbolSourceId: Int = -1,
    /** Complete symbol group size, including choices folded behind the visible head. */
    val symbolCount: Int = 0,
) {
    fun textWithComment(): String {
        val visibleComment = displayComment ?: comment
        return buildString {
            append(text)
            if (visibleComment.isNotBlank()) {
                if (spaceBetweenComment) {
                    append(" ")
                }
                append(visibleComment)
            }
        }
    }

    companion object {
        val Empty = CandidateWord("", "", "", false)
    }
}
