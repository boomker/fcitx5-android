/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

class VoiceInputPermissionActivity : Activity() {
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VoiceInputProviderManager.hasAudioPermission(this)) {
            deliverResult(granted = true)
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        deliverResult(granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        if (!resultDelivered && isFinishing) {
            VoiceInputProviderManager.onAudioPermissionResult(granted = false)
            resultDelivered = true
        }
        super.onDestroy()
    }

    private fun deliverResult(granted: Boolean) {
        if (resultDelivered) return
        resultDelivered = true
        VoiceInputProviderManager.onAudioPermissionResult(granted)
        finish()
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
    }
}
