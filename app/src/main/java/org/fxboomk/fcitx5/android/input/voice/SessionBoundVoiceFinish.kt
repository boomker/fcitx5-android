/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

/** May run after draining audio; every operation belongs to the captured session. */
internal class SessionBoundVoiceFinish(
    private val isCurrent: () -> Boolean,
    private val endStream: () -> Unit,
    private val armWatchdog: () -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    fun complete() {
        if (!isCurrent()) return
        try {
            endStream()
        } catch (failure: Exception) {
            if (isCurrent()) onFailure(failure)
            return
        }
        if (isCurrent()) armWatchdog()
    }
}
