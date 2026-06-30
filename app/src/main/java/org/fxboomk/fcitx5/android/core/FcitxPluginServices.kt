/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.core.data.PluginDescriptor
import org.fxboomk.fcitx5.android.utils.appContext
import timber.log.Timber

object FcitxPluginServices {

    const val PLUGIN_SERVICE_ACTION = "${BuildConfig.APPLICATION_ID}.plugin.SERVICE"
    private val hostManagedPluginServices = setOf("clipboard-filter")

    class PluginServiceConnection(
        private val pluginId: String,
        private val onDied: () -> Unit
    ) : ServiceConnection {
        private var messenger: Messenger? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service)
            Timber.d("Plugin connected: $pluginId")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            messenger = null
            Timber.d("Plugin disconnected: $pluginId")
        }

        // will never receive another connection
        override fun onBindingDied(name: ComponentName?) {
            messenger = null
            Timber.d("Plugin binding died: $pluginId")
            onDied.invoke()
        }

        override fun onNullBinding(name: ComponentName) {
            messenger = null
            Timber.d("Plugin null binding: $pluginId")
            onDied.invoke()
        }

        fun sendMessage(message: Message) {
            try {
                messenger?.send(Message.obtain(message))
            } catch (e: Throwable) {
                Timber.w("Cannot send message to plugin: $pluginId")
                Timber.w(e)
            }
        }
    }

    private val connections = mutableMapOf<String, PluginServiceConnection>()

    private fun unbindPluginConnection(
        name: String,
        connection: PluginServiceConnection
    ) {
        runCatching { appContext.unbindService(connection) }
            .onSuccess { Timber.d("Unbound plugin: $name") }
            .onFailure { Timber.w(it, "Cannot unbind plugin: $name") }
    }

    private fun connectPlugin(descriptor: PluginDescriptor) {
        val connection = PluginServiceConnection(descriptor.name) {
            disconnectPlugin(descriptor.name)
        }
        try {
            val result = appContext.bindService(
                Intent(PLUGIN_SERVICE_ACTION).apply {
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    setPackage(descriptor.packageName)
                },
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!result) throw Exception("Couldn't find service or not enough permission")
            connections[descriptor.name] = connection
            Timber.d("Bind to plugin: ${descriptor.name}")
        } catch (e: Exception) {
            Timber.w("Cannot bind to plugin: ${descriptor.name}")
            Timber.w(e)
        }
    }

    fun connectAll() {
        DataManager.getLoadedPlugins().forEach {
            if (it.name in hostManagedPluginServices) return@forEach
            if (it.hasService && !connections.containsKey(it.name)) {
                connectPlugin(it)
            }
        }
    }

    private fun disconnectPlugin(name: String) {
        connections.remove(name)?.also {
            unbindPluginConnection(name, it)
        }
    }

    fun disconnectAll() {
        connections.keys.toList().forEach(::disconnectPlugin)
    }

    fun sendMessage(message: Message) {
        connections.forEach { (_, conn) ->
            conn.sendMessage(message)
        }
    }
}
