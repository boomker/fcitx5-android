/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs

/** Logcat tag of the failure snapshots, so they can be lined up with the IME service's lifecycle logs. */
internal const val ENGINE_DIAGNOSTIC_TAG = "EngineStateDiagnostic"

/**
 * Describe the engine state right after a failed candidate lookup. Call it inside the same
 * connection block that typed the input, before any reset, so the snapshot is not stale.
 *
 * - currentIme differing from expectedIme, or matching cachedIme while the composition is empty,
 *   suggests that the test's input context lost the active slot (deactivate/activate from the
 *   real input method service).
 * - 18keyPref=true means the app prefers the compact layout for this scheme, so a mode key sent
 *   by the real keyboard can switch the engine to grouped input, which disables corrections.
 */
internal suspend fun FcitxAPI.engineStateDiagnostics(expectedIme: String, candidates: List<CandidateWord>): String {
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    val compact = AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue()
    return buildString {
        append("time=").append(time)
        append(", expectedIme=").append(expectedIme)
        append(", currentIme=").append(currentIme().uniqueName)
        append(", cachedIme=").append(inputMethodEntryCached.uniqueName)
        append(", 18keyPref=").append(expectedIme in compact)
        append(", clientPreedit='").append(clientPreeditCached.toString()).append('\'')
        append(", panelPreedit='").append(inputPanelCached.preedit.toString()).append('\'')
        append(", empty=").append(isEmpty())
        append(", candidates(").append(candidates.size).append(")=")
        append(candidates.take(20).map { word ->
            buildString {
                append(word.text)
                if (word.comment.isNotEmpty()) append(word.comment)
                if (word.isFallback) append("[fallback]")
                if (word.isDeferredFallback) append("[deferred]")
            }
        })
    }
}

/** Print a snapshot to logcat as well; the instrumentation output itself has no timestamps. */
internal fun reportEngineState(message: String) {
    Log.e(ENGINE_DIAGNOSTIC_TAG, message)
}
