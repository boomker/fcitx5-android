// SPDX-License-Identifier: LGPL-2.1-or-later
// SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
package org.fcitx.fcitx5.android.common.ipc;

import android.os.Bundle;
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback;

// Voice input provider exposed by a plugin app.
// Audio is recorded by the IME (foreground) and pushed to the provider as PCM,
// so the plugin never needs FOREGROUND_SERVICE_MICROPHONE for this path.
interface IVoiceInputProvider {
    // Whether the provider can serve a session right now (e.g. model loaded).
    // Sync so the IME can pick a fallback if false.
    boolean isAvailable();

    // Preferred session parameters. Recognized keys live in
    // VoiceInputIpc.ConfigKeys. The IME should pass the same Bundle back to
    // configure() and use it for microphone capture.
    Bundle getPreferredConfig();

    // Optional session parameters before startSession.
    // Recognized keys live in VoiceInputIpc.ConfigKeys.
    // oneway: returns nothing.
    oneway void configure(in Bundle params);

    // Begin a recognition session. cb receives partial / segment / error events.
    // Must be called before feedAudio.
    oneway void startSession(IVoiceInputCallback cb);

    // Push PCM audio. Format and rate are agreed in configure() (default:
    // 16 kHz mono PCM_16, little-endian). offset/len index into pcm.
    // ptsMs is the presentation timestamp of the first sample, monotonic
    // within a session; providers may ignore it.
    oneway void feedAudio(in byte[] pcm, int offset, int len, long ptsMs);

    // Signal that no more audio will be fed; finalize any pending segment.
    // The provider will emit a final onSegmentFinal (if any text) and then
    // onSessionEnded.
    oneway void endStream();

    // Abort the current session immediately. No further callbacks except
    // onSessionEnded.
    oneway void cancelSession();

    // Tear down the session and free engine resources tied to it.
    oneway void stopSession();
}
