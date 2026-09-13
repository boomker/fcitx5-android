/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.daemon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fxboomk.fcitx5.android.FcitxApplication
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.Fcitx
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxLifecycle
import org.fxboomk.fcitx5.android.core.lifeCycleScope
import org.fxboomk.fcitx5.android.core.whenReady
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon.connect
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon.disconnect
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.notificationManager
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manage the singleton instance of [Fcitx]
 *
 * To use fcitx, client should call [connect] to obtain a [FcitxConnection],
 * and call [disconnect] on client destroyed. Client should not leak the instance of [FcitxAPI],
 * and must use [FcitxConnection] to access fcitx functionalities.
 *
 * The instance of [Fcitx] always exists. Once started it keeps running even when all
 * clients are gone (e.g. user switched to another IME), so the next keyboard pull-up
 * reuses the warm instance instead of paying the full native startup and Rime engine
 * initialization again. Use [restartFcitx] or [stopFcitx] to stop it explicitly.
 *
 * Functions are thread-safe in this class.
 */
object FcitxDaemon {

    private val realFcitx by lazy { Fcitx(appContext) }

    // don't leak fcitx instance
    private val fcitxImpl by lazy { object : FcitxAPI by realFcitx {} }

    private fun mkConnection(name: String) = object : FcitxConnection {

        private inline fun <T> ensureConnected(block: () -> T) =
            if (name in clients)
                block()
            else throw IllegalStateException("$name is disconnected")

        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = ensureConnected {
            runBlocking(realFcitx.lifeCycleScope.coroutineContext) {
                block(fcitxImpl)
            }
        }

        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = ensureConnected {
            realFcitx.lifecycle.whenReady { block(fcitxImpl) }
        }

        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
            ensureConnected {
                if (realFcitx.isReady)
                    realFcitx.lifeCycleScope.launch {
                        block(fcitxImpl)
                    }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realFcitx.lifecycle.lifecycleScope

    }

    private val lock = ReentrantLock()

    private val clients = mutableMapOf<String, FcitxConnection>()

    /**
     * Create a connection
     */
    fun connect(name: String): FcitxConnection = lock.withLock {
        if (name in clients)
            return@withLock clients.getValue(name)
        if (realFcitx.lifecycle.currentState == FcitxLifecycle.State.STOPPED) {
            Timber.d("FcitxDaemon start fcitx")
            realFcitx.start()
        }
        val new = mkConnection(name)
        clients[name] = new
        return@withLock new
    }

    /**
     * Dispose the connection
     */
    fun disconnect(name: String): Unit = lock.withLock {
        if (name !in clients)
            return
        clients -= name
        // Keep the engine warm after the last client disconnects. Restarting it
        // on the next pull-up re-runs data sync, native startup and the Rime
        // engine init (including its maintenance/deploy check), which used to
        // leave the keyboard visible but not ready for seconds. Explicit stop
        // paths: [restartFcitx], [stopFcitx], and process death.
        Timber.d("FcitxDaemon disconnect $name, ${clients.size} client(s) left")
    }

    /**
     * Restart fcitx instance while keep the clients connected
     */
    fun restartFcitx() = lock.withLock {
        val id = RESTART_ID++
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(appContext.getString(R.string.fcitx_daemon))
            .setContentText(appContext.getString(R.string.restarting_fcitx))
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build().let { appContext.notificationManager.notify(id, it) }
        realFcitx.stop()
        realFcitx.start()
        FcitxApplication.getInstance().coroutineScope.launch {
            // cancel notification on ready
            realFcitx.lifecycle.whenReady {
                appContext.notificationManager.cancel(id)
            }
        }
    }

    /**
     * Stop fcitx instance regardless of connected clients.
     * Should only be used before importing user configuration files,
     * then the App must be restarted as soon as possible.
     *
     * This method blocks until fully stopped.
     */
    fun stopFcitx() {
        realFcitx.stop()
    }

    /**
     * Stop fcitx instance.
     * Should only be used when it has been stopped **AND** user data importing failed.
     */
    fun startFcitx() {
        realFcitx.start()
    }

    /**
     * Whether the fcitx instance is currently running (READY), regardless of clients.
     */
    fun isRunning(): Boolean = realFcitx.isReady

    /**
     * Save the state of the running fcitx instance, regardless of connected clients.
     * Since the instance stays warm after the last client disconnects, shutdown
     * handling can no longer rely on a client being present to persist state.
     * No-op when the instance is not running.
     */
    fun saveIfRunning() = lock.withLock {
        if (realFcitx.isReady) {
            runBlocking(realFcitx.lifeCycleScope.coroutineContext) {
                realFcitx.save()
            }
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getText(R.string.fcitx_daemon),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            appContext.notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Reuse a connection for remote service
     */
    fun getFirstConnectionOrNull() = clients.firstNotNullOfOrNull { it.value }


    private const val CHANNEL_ID = "fcitx-daemon"
    private var RESTART_ID = 0

}