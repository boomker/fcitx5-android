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
    private var allowReconnects = true

    class PluginServiceConnection(
        private val pluginId: String,
        private val onDisconnected: () -> Unit
    ) : ServiceConnection {
        private var messenger: Messenger? = null
        val isConnected: Boolean
            get() = messenger != null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service)
            Timber.d("Plugin connected: $pluginId")
        }

        // may re-connect in the future
        override fun onServiceDisconnected(name: ComponentName) {
            messenger = null
            Timber.d("Plugin disconnected: $pluginId")
            onDisconnected.invoke()
        }

        // will never receive another connection
        override fun onBindingDied(name: ComponentName?) {
            messenger = null
            Timber.d("Plugin binding died: $pluginId")
            onDisconnected.invoke()
        }

        override fun onNullBinding(name: ComponentName) {
            messenger = null
            Timber.d("Plugin null binding: $pluginId")
            onDisconnected.invoke()
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

    private fun shouldReconnect(descriptor: PluginDescriptor): Boolean =
        allowReconnects && DataManager.getLoadedPlugins().any {
            it.name == descriptor.name && it.packageName == descriptor.packageName && it.hasService
        }

    private fun dropConnection(
        name: String,
        expected: PluginServiceConnection? = null,
        unbind: Boolean
    ) {
        val current = connections[name] ?: return
        if (expected != null && current !== expected) return
        connections.remove(name)
        if (unbind) {
            runCatching { appContext.unbindService(current) }
                .onFailure { Timber.w(it, "Cannot unbind plugin: $name") }
            Timber.d("Unbound plugin: $name")
        }
    }

    private fun connectPlugin(descriptor: PluginDescriptor) {
        dropConnection(descriptor.name, unbind = true)
        lateinit var connection: PluginServiceConnection
        connection = PluginServiceConnection(descriptor.name) {
            dropConnection(descriptor.name, expected = connection, unbind = false)
            if (shouldReconnect(descriptor)) {
                Timber.d("Reconnecting plugin: ${descriptor.name}")
                connectPlugin(descriptor)
            }
        }
        try {
            val intent = Intent(PLUGIN_SERVICE_ACTION).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage(descriptor.packageName)
            }
            val result = appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!result) throw Exception("Couldn't find service or not enough permission")
            connections[descriptor.name] = connection
            Timber.d("Bind to plugin: ${descriptor.name}")
        } catch (e: Exception) {
            dropConnection(descriptor.name, expected = connection, unbind = true)
            Timber.w("Cannot bind to plugin: ${descriptor.name}")
            Timber.w(e)
        }
    }

    fun connectAll() {
        allowReconnects = true
        DataManager.getLoadedPlugins().forEach {
            if (it.name in hostManagedPluginServices) return@forEach
            if (it.hasService && connections[it.name]?.isConnected != true) {
                connectPlugin(it)
            }
        }
    }

    private fun disconnectPlugin(name: String) {
        dropConnection(name, unbind = true)
    }

    fun disconnectAll() {
        allowReconnects = false
        connections.forEach { (name, connection) ->
            runCatching { appContext.unbindService(connection) }
                .onFailure { Timber.w(it, "Cannot unbind plugin: $name") }
            Timber.d("Unbound plugin: $name")
        }
        connections.clear()
    }

    fun sendMessage(message: Message) {
        connections.forEach { (_, conn) ->
            conn.sendMessage(message)
        }
    }
}
