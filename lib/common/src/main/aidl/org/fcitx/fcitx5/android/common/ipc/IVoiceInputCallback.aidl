// SPDX-License-Identifier: LGPL-2.1-or-later
// SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
package org.fcitx.fcitx5.android.common.ipc;

// Events delivered from a voice input provider back to the IME.
// All oneway so the engine thread never blocks on UI.
oneway interface IVoiceInputCallback {
    // Provider acknowledged the session and is ready to accept feedAudio.
    void onReady();

    // Optional volume / RMS hint for UI animation. Range: 0..32767.
    void onVolumeLevel(int rms);

    // Streaming partial hypothesis for the current segment.
    void onPartialResult(String text);

    // VAD/endpoint fired: segment finalized. text may be empty if nothing
    // was decoded. The IME decides whether to commit directly, push as a
    // candidate, or replace previous segment text.
    void onSegmentFinal(String text);

    // Session has ended (after stopSession / endStream / cancelSession or
    // engine-side failure that already reported via onError).
    void onSessionEnded();

    // code is provider-defined; message should be human-readable English.
    void onError(int code, String message);
}
