/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.common.ipc

object VoiceInputIpc {
    // Appended to the main app id to form the bind action, e.g.
    //   org.fcitx.fcitx5.android.fxliang.plugin.VOICE_INPUT
    // Plugins declare an <intent-filter> on their provider Service with
    // <action android:name="${mainApplicationId}.plugin.VOICE_INPUT" />.
    const val SERVICE_ACTION_SUFFIX = ".plugin.VOICE_INPUT"

    const val COMMIT_ACTION_SUFFIX = ".plugin.VOICE_INPUT_COMMIT"
    const val PARTIAL_ACTION_SUFFIX = ".plugin.VOICE_INPUT_PARTIAL"
    const val START_FLOATING_ACTION_SUFFIX = ".plugin.VOICE_INPUT_FLOATING"
    const val EXTRA_COMMIT_TEXT = "commitText"
    const val EXTRA_PARTIAL_TEXT = "partialText"
    // Optional FloatingService only; the AIDL configure/callback interfaces are unchanged.
    const val EXTRA_FLOATING_SESSION_TOKEN = "voiceSessionToken"
    const val EXTRA_HOST_PACKAGE = "voiceHostPackage"
    const val META_FLOATING_SESSION_TOKEN = "org.fcitx.fcitx5.android.voice.FLOATING_SESSION_TOKEN"

    // Bundle keys recognized by IVoiceInputProvider.configure.
    // Providers must tolerate missing keys and use their own defaults.
    object ConfigKeys {
        // int — PCM sample rate. Default 16000.
        const val SAMPLE_RATE = "sampleRate"

        // int — bits per sample. Default 16.
        const val BITS_PER_SAMPLE = "bitsPerSample"

        // int — channel count. Default 1.
        const val CHANNELS = "channels"

        // long — VAD / endpoint silence threshold in milliseconds before the
        // provider declares a segment final. Default is provider-specific.
        const val SILENCE_MS = "silenceMs"

        // String — preferred recognition language hint (BCP-47), best effort.
        const val LANGUAGE = "language"
    }

    object ErrorCodes {
        const val UNKNOWN = 0
        const val NOT_AVAILABLE = 1
        const val MODEL_LOAD_FAILED = 2
        const val DECODE_FAILED = 3
        const val INVALID_AUDIO = 4
    }
}
