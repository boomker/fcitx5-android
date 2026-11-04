/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback

/** Binder callbacks only enqueue; all validation and state changes happen on Main. */
internal class SessionBoundVoiceCallback(
    private val dispatch: (() -> Unit) -> Unit,
    private val isCurrent: () -> Boolean,
    private val delegate: IVoiceInputCallback
) : IVoiceInputCallback.Stub() {
    fun guardedBy(guard: () -> Boolean) =
        SessionBoundVoiceCallback(dispatch, { isCurrent() && guard() }, delegate)

    private fun deliver(block: () -> Unit) = dispatch { if (isCurrent()) block() }
    override fun onReady() = deliver { delegate.onReady() }
    override fun onVolumeLevel(rms: Int) = deliver { delegate.onVolumeLevel(rms) }
    override fun onPartialResult(text: String?) = deliver { delegate.onPartialResult(text) }
    override fun onSegmentFinal(text: String?) = deliver { delegate.onSegmentFinal(text) }
    override fun onSessionEnded() = deliver { delegate.onSessionEnded() }
    override fun onError(code: Int, message: String?) = deliver { delegate.onError(code, message) }
}
