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
) {
    fun textWithComment(): String {
        return buildString {
            append(text)
            if (comment.isNotBlank()) {
                if (spaceBetweenComment) {
                    append(" ")
                }
                append(comment)
            }
        }
    }

    companion object {
        val Empty = CandidateWord("", "", "", false)
    }
}
