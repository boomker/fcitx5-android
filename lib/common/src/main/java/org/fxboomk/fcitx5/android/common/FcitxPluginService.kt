/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.common

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.widget.Toast

abstract class FcitxPluginService : Service() {

    private lateinit var messenger: Messenger

    open val handler: Handler = Handler(Looper.getMainLooper())
    protected open val stopOnUnbind: Boolean = true

    override fun onCreate() {
        super.onCreate()
    }

    override fun onBind(intent: Intent): IBinder? {
        // Verify host app build type matches plugin build type
        val hostPackageName = intent.`package` ?: applicationContext.packageName
        val isDebugPlugin = packageName.contains(".debug")
        val isDebugHost = hostPackageName.contains(".debug")
        if (isDebugPlugin != isDebugHost) {
            val msg = if (isDebugPlugin) {
                "Debug plugin requires Debug host app. Please install Debug version."
            } else {
                "Release plugin requires Release host app. Please install Release version."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return null
        }
        messenger = Messenger(handler)
        start()
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (stopOnUnbind) {
            stop()
        }
        return false
    }

    abstract fun start()

    abstract fun stop()
}
