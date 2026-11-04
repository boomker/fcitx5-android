/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import kotlinx.coroutines.channels.Channel

/** Ordered admission, bounded in packets, retained bytes and PCM duration. */
internal class JsVoiceCommandQueue(sampleRate: Int) {
    private val channel = Channel<JsVoiceCommand>(128)
    private val maxAudio = sampleRate.toLong() * 2 * 3
    private var bytes = 0L
    private var audio = 0L
    private var closed = false
    private var stopping = false

    private fun JsVoiceCommand.weight(): Long = when (this) {
        is JsVoiceCommand.Audio -> pcm.size.toLong()
        is JsVoiceCommand.Message -> 2L * (text.length + (binaryBase64?.length ?: 0))
        is JsVoiceCommand.Fail -> 2L * message.length
        JsVoiceCommand.Stop -> 0L
    } + 64

    @Synchronized fun offer(command: JsVoiceCommand): Boolean {
        if (closed) return false
        if (command is JsVoiceCommand.Audio && stopping) return true
        val weight = command.weight()
        val pcm = (command as? JsVoiceCommand.Audio)?.pcm?.size?.toLong() ?: 0
        if (bytes + weight > 4L * 1024 * 1024 || audio + pcm > maxAudio) return false
        if (!channel.trySend(command).isSuccess) return false
        bytes += weight
        audio += pcm
        if (command === JsVoiceCommand.Stop) stopping = true
        return true
    }

    suspend fun receive(): JsVoiceCommand = channel.receive().also { command ->
        synchronized(this) {
            if (!closed) {
                bytes -= command.weight()
                audio -= (command as? JsVoiceCommand.Audio)?.pcm?.size?.toLong() ?: 0
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        bytes = 0
        audio = 0
        channel.cancel()
    }
}
