/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fxboomk.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fxboomk.fcitx5.android.common.ipc.IFcitxRemoteService
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.core.reloadPinyinDict
import org.fxboomk.fcitx5.android.core.reloadQuickPhrase
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.utils.Const
import org.fxboomk.fcitx5.android.utils.desc
import org.fxboomk.fcitx5.android.utils.descEquals
import timber.log.Timber
import java.util.PriorityQueue

class FcitxRemoteService : Service() {

    private val clipboardTransformerLock = Mutex()

    private val scope = MainScope() + CoroutineName("FcitxRemoteService")

    private val clipboardTransformers =
        PriorityQueue<IClipboardEntryTransformer>(3, compareByDescending { it.priority })

    private fun transformClipboard(source: String): String {
        var result = source
        clipboardTransformers.forEach {
            try {
                result = it.transform(result)!!
            } catch (e: Exception) {
                Timber.w("Exception while calling clipboard transformer '${it.desc}'")
                Timber.w(e)
            }
        }
        return result
    }

    private suspend fun updateClipboardManager() = clipboardTransformerLock.withLock {
        ClipboardManager.transformer =
            if (clipboardTransformers.isEmpty()) null else ::transformClipboard
        Timber.d("All clipboard transformers: ${clipboardTransformers.joinToString { it.desc }}")
    }

    private fun getCallingPackages(): Set<String> {
        val uid = Binder.getCallingUid()
        return packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
    }

    private fun getLoadedPluginPackages(): Set<String> {
        return DataManager.getLoadedPlugins()
            .mapTo(mutableSetOf()) { it.packageName }
    }

    /**
     * Check if the calling package is allowed based on IPC compatibility mode
     */
    private fun isCallerAllowed(callingPackages: Set<String>): Boolean {
        if (callingPackages.isEmpty()) return false

        // Always allow self
        if (callingPackages.contains(packageName)) return true

        // Allow loaded plugin packages from this installation.
        if (callingPackages.any { it in getLoadedPluginPackages() }) return true

        // Check if allowing original plugins (both release and debug)
        return AppPrefs.getInstance().advanced.allowOriginalPlugins.getValue() &&
                callingPackages.any {
                    it == "org.fxboomk.fcitx5.android" || it == "org.fxboomk.fcitx5.android.debug"
                }
    }

    private val binder = object : IFcitxRemoteService.Stub() {
        override fun getVersionName(): String = Const.versionName

        override fun getPid(): Int = Process.myPid()

        override fun getLoadedPlugins(): MutableMap<String, String> =
            DataManager.getLoadedPlugins().map {
                it.packageName to it.versionName
            }.let { mutableMapOf<String, String>().apply { putAll(it) } }

        override fun restartFcitx() {
            FcitxDaemon.restartFcitx()
        }

        override fun registerClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            val callingPackages = getCallingPackages()
            if (!isCallerAllowed(callingPackages)) {
                Timber.w("Rejected clipboard transformer registration from $callingPackages (allowOriginalPlugins=${AppPrefs.getInstance().advanced.allowOriginalPlugins.getValue()})")
                throw SecurityException("IPC compatibility mode does not allow access from $callingPackages")
            }
            Timber.d("registerClipboardEntryTransformer: ${transformer.desc} from $callingPackages")
            if (transformer.description.isNullOrBlank()) {
                Timber.w("Cannot register ClipboardEntryTransformer of null or empty description")
            }
            if (clipboardTransformers.any { it.descEquals(transformer) }) {
                Timber.w("ClipboardEntryTransformer ${transformer.desc} has already been registered")
                return
            }
            scope.launch {
                transformer.asBinder().linkToDeath({
                    unregisterClipboardEntryTransformer(transformer)
                }, 0)
                clipboardTransformers.add(transformer)
                updateClipboardManager()
            }
        }

        override fun unregisterClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            Timber.d("unregisterClipboardEntryTransformer: ${transformer.desc}")
            scope.launch {
                clipboardTransformers.remove(transformer)
                        || clipboardTransformers.removeAll { it.descEquals(transformer) }
                        || return@launch
                updateClipboardManager()
            }
        }

        override fun importRemoteClipboardEntry(
            text: String,
            originalText: String,
            originalRootUri: String,
            type: String,
            timestamp: Long,
            sensitive: Boolean
        ) {
            val callingPackages = getCallingPackages()
            if (!isCallerAllowed(callingPackages)) {
                Timber.w("Rejected remote clipboard import from $callingPackages (allowOriginalPlugins=${AppPrefs.getInstance().advanced.allowOriginalPlugins.getValue()})")
                throw SecurityException("IPC compatibility mode does not allow access from $callingPackages")
            }
            runBlocking {
                ClipboardManager.importRemoteEntry(
                    text = text,
                    originalText = originalText,
                    originalRootUri = originalRootUri,
                    type = type,
                    timestamp = timestamp,
                    sensitive = sensitive,
                    notifyListeners = false
                )
            }
        }

        override fun reloadPinyinDict() {
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadPinyinDict() }
        }

        override fun reloadQuickPhrase() {
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady { reloadQuickPhrase() }
        }
    }

    override fun onCreate() {
        Timber.d("FcitxRemoteService onCreate")
        super.onCreate()
    }

    override fun onBind(intent: Intent): IBinder {
        val callingPackages = getCallingPackages()
        Timber.d("FcitxRemoteService onBind: $intent, callingPackages=$callingPackages")
        if (!isCallerAllowed(callingPackages)) {
            Timber.w("Rejected IPC connection from $callingPackages (allowOriginalPlugins=${AppPrefs.getInstance().advanced.allowOriginalPlugins.getValue()})")
            throw SecurityException("IPC compatibility mode does not allow access from $callingPackages")
        }
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.d("FcitxRemoteService onUnbind: $intent")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.d("FcitxRemoteService onDestroy")
        scope.cancel()
        clipboardTransformers.clear()
        runBlocking { updateClipboardManager() }
    }
}
