/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlin.concurrent.thread

// Mic capture for the IME's voice-input AIDL bridge.
// onPcm is invoked on the recording thread. Implementations must not block.
class VoiceInputAudioCapture(
    private val context: Context,
    private val config: Config = Config(),
    private val onPcm: (ByteArray, Int, Int, Long) -> Unit,
    private val onLevel: ((Int) -> Unit)? = null,
    private val onError: (String) -> Unit,
) {
    data class Config(
        val sampleRate: Int = DEFAULT_SAMPLE_RATE,
        val bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
        val channels: Int = DEFAULT_CHANNELS,
    )

    companion object {
        private const val TAG = "FcitxVoiceInput"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_BITS_PER_SAMPLE = 16
        const val DEFAULT_CHANNELS = 1

        /** How long [stop] waits for the recording thread to observe `running = false`. */
        private const val WORKER_JOIN_TIMEOUT_MS = 300L

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false

    fun start(): Boolean {
        if (!hasPermission(context)) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_no_permission))
            return false
        }
        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_minbuf, minBuf))
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: SecurityException) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_construct, e.message))
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_not_init))
            return false
        }
        recorder = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release(); recorder = null; running = false
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_start_failed, e.message))
            return false
        }

        // 100 ms chunks ⇒ low latency for partials, modest binder pressure.
        val chunkSamples = config.sampleRate / 10
        val chunkBytes = chunkSamples * config.bitsPerSample / 8 * config.channels
        val buf = ByteArray(chunkBytes)
        val startedAt = System.currentTimeMillis()

        worker = thread(name = "voice-input-capture", isDaemon = true) {
            var lastLevelLogAt = 0L
            try {
                while (running && recorder != null) {
                    val read = recorder?.read(buf, 0, chunkBytes) ?: break
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE
                        ) {
                            Timber.w("AudioRecord.read error=$read")
                            break
                        }
                        continue
                    }
                    // read() blocks, so running may have been cleared while we waited.
                    // Re-check before delivering, otherwise audio from a stopped session
                    // leaks into the next session's pre-roll buffer.
                    if (!running) break
                    val pts = System.currentTimeMillis() - startedAt
                    onPcm(buf, 0, read, pts)
                    val rms = rmsOf(buf, read)
                    if (pts - lastLevelLogAt >= 1000L) {
                        lastLevelLogAt = pts
                        Log.i(TAG, "capture level rms=$rms read=$read pts=$pts")
                    }
                    onLevel?.invoke(rms)
                }
            } catch (e: Throwable) {
                Timber.w(e, "capture thread crashed")
            }
        }
        Timber.i("VoiceInputAudioCapture started: config=$config minBuf=$minBuf chunkBytes=$chunkBytes")
        return true
    }

    fun stop() {
        running = false
        val rec = recorder
        recorder = null
        try { rec?.stop() } catch (t: Throwable) { Timber.w(t, "AudioRecord.stop() failed") }
        try { rec?.release() } catch (t: Throwable) { Timber.w(t, "AudioRecord.release() failed") }
        val thread = worker
        if (thread != null) {
            try {
                thread.join(WORKER_JOIN_TIMEOUT_MS)
            } catch (t: InterruptedException) {
                Timber.w(t, "Interrupted while joining capture thread")
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                // Keep the reference so a subsequent stop() can join it again; dropping it
                // here would let a still-running worker keep pushing PCM into the next
                // session's pre-roll buffer.
                Timber.w("Capture thread still alive after ${WORKER_JOIN_TIMEOUT_MS}ms join")
            } else {
                worker = null
            }
        }
    }

    private fun rmsOf(buf: ByteArray, len: Int): Int {
        if (len < 2) return 0
        val count = len / 2
        var sumSq = 0.0
        var i = 0
        while (i < len - 1) {
            val lo = buf[i].toInt() and 0xff
            val hi = buf[i + 1].toInt()
            val s = (hi shl 8) or lo
            sumSq += (s * s).toDouble()
            i += 2
        }
        return Math.sqrt(sumSq / count).toInt().coerceIn(0, 32767)
    }
}
