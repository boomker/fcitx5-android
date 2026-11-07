/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.predict

import org.fxboomk.fcitx5.android.R

internal enum class AiCandidateAction(val label: String, val titleRes: Int) {
    Polish("润", R.string.ai_clip_long_form),
    Translate("译", R.string.ai_clip_translate),
    Reply("答", R.string.ai_clip_question_answer),
    Settings("调", R.string.llm_quick_settings),
}
