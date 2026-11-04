/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice

import android.os.IBinder
import timber.log.Timber

/** Owned on Main. Binder threads only enqueue work through [attach]'s dispatcher. */
internal class VoiceProviderDeathLink {
    private data class Link(val binder: IBinder, val recipient: IBinder.DeathRecipient)
    private var current: Link? = null

    fun attach(binder: IBinder, dispatch: (() -> Unit) -> Unit, onDeath: () -> Unit) {
        clear()
        lateinit var link: Link
        val recipient = IBinder.DeathRecipient {
            dispatch {
                // Check when the posted callback runs: the user may have rebound since
                // binderDied was delivered. Each registration has its own identity.
                if (current === link) {
                    clear()
                    onDeath()
                }
            }
        }
        link = Link(binder, recipient)
        current = link
        try {
            binder.linkToDeath(recipient, 0)
        } catch (failure: Exception) {
            clear()
            throw failure
        }
    }

    fun clear() {
        val previous = current
        current = null
        if (previous != null) {
            runCatching { previous.binder.unlinkToDeath(previous.recipient, 0) }
                .onFailure { Timber.w(it, "unlink voice provider death") }
        }
    }
}
