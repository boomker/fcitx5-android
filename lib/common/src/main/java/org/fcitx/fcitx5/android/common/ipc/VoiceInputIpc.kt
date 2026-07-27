/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.ipc

object VoiceInputIpc {
    const val SERVICE_ACTION_SUFFIX = ".plugin.VOICE_INPUT"
    const val START_FLOATING_ACTION_SUFFIX = ".plugin.VOICE_INPUT_FLOATING"

    object ConfigKeys {
        const val SAMPLE_RATE = "sampleRate"
        const val BITS_PER_SAMPLE = "bitsPerSample"
        const val CHANNELS = "channels"
        const val SILENCE_MS = "silenceMs"
        const val LANGUAGE = "language"
    }

    object ErrorCodes {
        const val UNKNOWN = 0
        const val NOT_AVAILABLE = 1
        const val MODEL_LOAD_FAILED = 2
        const val DECODE_FAILED = 3
        const val INVALID_AUDIO = 4
        const val CANCELLED = 5
    }
}
