// SPDX-License-Identifier: LGPL-2.1-or-later
// SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
package org.fcitx.fcitx5.android.common.ipc;

// Events delivered from a voice input provider back to the IME.
oneway interface IVoiceInputCallback {
    void onReady();
    void onVolumeLevel(int rms);
    void onPartialResult(String text);
    void onSegmentFinal(String text);
    void onSessionEnded();
    void onError(int code, String message);
}
