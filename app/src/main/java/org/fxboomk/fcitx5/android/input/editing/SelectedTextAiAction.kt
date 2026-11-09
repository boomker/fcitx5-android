/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.editing

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.predict.AiCandidateAction

internal enum class SelectedTextAiAction(
    val titleRes: Int,
    val iconRes: Int,
    val candidateAction: AiCandidateAction,
    val assistantPurpose: String,
) {
    Translate(R.string.gesture_action_translate, R.drawable.ic_baseline_translate_24, AiCandidateAction.Translate, "translate"),
    Polish(R.string.gesture_action_polish, R.drawable.ic_baseline_auto_awesome_24, AiCandidateAction.Polish, "polish"),
    BriefReply(R.string.gesture_action_brief_reply, R.drawable.ic_baseline_chat_24, AiCandidateAction.Reply, "short"),
}
