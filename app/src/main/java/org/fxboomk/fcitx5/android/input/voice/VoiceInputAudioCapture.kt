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
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlin.concurrent.thread
import kotlin.math.sqrt

class VoiceInputAudioCapture(
    private val context: Context,
    private val config: Config = Config(),
    private val onPcm: (ByteArray, Int, Int, Long) -> Unit,
    private val onLevel: ((Int) -> Unit)? = null,
    private val onError: (String) -> Unit,
) {
    data class Config(
        val sampleRate: Int = 16000,
        val bitsPerSample: Int = 16,
        val channels: Int = 1,
    )

    companion object {
        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Volatile
    private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    fun start(): Boolean {
        if (!hasPermission(context)) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_no_permission))
            return false
        }
        if (config.bitsPerSample != 16 || config.channels != 1) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_invalid_audio_config))
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_minbuf, minBuffer))
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2,
            )
        } catch (e: SecurityException) {
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_construct, e.message))
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_not_init))
            return false
        }

        recorder = record
        running = true
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            running = false
            recorder = null
            record.release()
            onError(context.getString(org.fxboomk.fcitx5.android.R.string.voice_error_audio_start_failed, e.message))
            return false
        }

        val chunkBytes = config.sampleRate / 10 * config.bitsPerSample / 8 * config.channels
        val buffer = ByteArray(chunkBytes)
        val startedAt = System.currentTimeMillis()
        worker = thread(name = "voice-input-capture", isDaemon = true) {
            try {
                while (running && recorder != null) {
                    val read = recorder?.read(buffer, 0, chunkBytes) ?: break
                    if (read <= 0) continue
                    onPcm(buffer, 0, read, System.currentTimeMillis() - startedAt)
                    onLevel?.invoke(rmsOf(buffer, read))
                }
            } catch (e: Throwable) {
                Timber.w(e, "Voice input capture thread failed")
            }
        }
        return true
    }

    fun stop() {
        running = false
        val record = recorder
        recorder = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        worker?.let {
            try {
                it.join(300)
            } catch (_: InterruptedException) {
            }
        }
        worker = null
    }

    private fun rmsOf(buffer: ByteArray, length: Int): Int {
        if (length < 2) return 0
        val count = length / 2
        var sumSquares = 0.0
        var i = 0
        while (i < length - 1) {
            val lo = buffer[i].toInt() and 0xff
            val hi = buffer[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSquares += (sample * sample).toDouble()
            i += 2
        }
        return sqrt(sumSquares / count).toInt().coerceIn(0, 32767)
    }
}
