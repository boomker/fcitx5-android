// SPDX-License-Identifier: LGPL-2.1-or-later
// SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
package org.fcitx.fcitx5.android.common.ipc;

import android.os.Bundle;
import org.fcitx.fcitx5.android.common.ipc.IVoiceInputCallback;

// Voice input provider exposed by a plugin app. Audio is recorded by the IME
// and pushed to the provider as PCM, so plugin apps do not need their own
// foreground microphone session for this path.
interface IVoiceInputProvider {
    boolean isAvailable();
    Bundle getPreferredConfig();
    oneway void configure(in Bundle params);
    oneway void startSession(IVoiceInputCallback cb);
    oneway void feedAudio(in byte[] pcm, int offset, int len, long ptsMs);
    oneway void endStream();
    oneway void cancelSession();
    oneway void stopSession();
}
