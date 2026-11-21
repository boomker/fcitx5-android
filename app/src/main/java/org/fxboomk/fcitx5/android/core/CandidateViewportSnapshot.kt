/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.core

/** Native candidate identities and symbol groups from one presentation revision. */
data class CandidateViewportSnapshot(
    val revision: Long,
    val foldingEnabled: Boolean,
    val total: Int,
    val candidates: Array<CandidateWord>,
) {
    companion object {
        val Empty = CandidateViewportSnapshot(0, false, 0, emptyArray())
    }
}
