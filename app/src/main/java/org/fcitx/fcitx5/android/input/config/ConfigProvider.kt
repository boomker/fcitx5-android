/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

interface ConfigProvider {
    fun textKeyboardLayoutFile(): File?
    fun popupPresetFile(): File?
    fun fontsetFile(): File?
    fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File>
}

object DefaultConfigProvider : ConfigProvider {
    override fun textKeyboardLayoutFile(): File? = UserConfigFiles.textKeyboardLayoutJson()
    override fun popupPresetFile(): File? = UserConfigFiles.popupPresetJson()
    override fun fontsetFile(): File? = UserConfigFiles.fontsetJson()
    override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
        UserJsonConfigStore.writeFontsetPathMap(pathMap)
}

object ConfigProviders {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var provider: ConfigProvider = DefaultConfigProvider
        set(value) {
            field = value
            restartWatching()
        }

    @Volatile
    private var configWatcher: FileObserver? = null

    @Volatile
    private var fontWatcher: FileObserver? = null

    @Volatile
    private var watchedConfigDir: String? = null

    @Volatile
    private var watchedTextLayoutFileName: String? = null

    @Volatile
    private var watchedPopupPresetFileName: String? = null

    @Volatile
    private var watchedFontDir: String? = null

    @Volatile
    private var watchedFontsetFileName: String? = null

    private val textLayoutListeners = linkedSetOf<() -> Unit>()
    private val popupPresetListeners = linkedSetOf<() -> Unit>()
    private val fontsetListeners = linkedSetOf<() -> Unit>()

    @Synchronized
    private fun notifyListeners(listeners: Set<() -> Unit>) {
        if (listeners.isEmpty()) return
        val snapshot = listeners.toList()
        mainHandler.post {
            snapshot.forEach { listener ->
                runCatching { listener() }
            }
        }
    }

    @Synchronized
    fun addTextKeyboardLayoutListener(listener: () -> Unit) {
        textLayoutListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun addPopupPresetListener(listener: () -> Unit) {
        popupPresetListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun addFontsetListener(listener: () -> Unit) {
        fontsetListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    private fun restartWatching() {
        configWatcher?.stopWatching()
        fontWatcher?.stopWatching()
        configWatcher = null
        fontWatcher = null
        watchedConfigDir = null
        watchedTextLayoutFileName = null
        watchedPopupPresetFileName = null
        watchedFontDir = null
        watchedFontsetFileName = null
        ensureWatching()
    }

    @Synchronized
    fun ensureWatching() {
        val hasConfigListeners = textLayoutListeners.isNotEmpty() || popupPresetListeners.isNotEmpty()
        val hasFontsetListeners = fontsetListeners.isNotEmpty()

        if (!hasConfigListeners && !hasFontsetListeners) return

        val textFile = provider.textKeyboardLayoutFile()
        val popupFile = provider.popupPresetFile()
        val configDir = textFile?.parentFile ?: popupFile?.parentFile

        if (hasConfigListeners && configDir != null) {
            val dirPath = configDir.absolutePath
            val textFileName = textFile?.name
            val popupFileName = popupFile?.name

            if (!(configWatcher != null &&
                        watchedConfigDir == dirPath &&
                        watchedTextLayoutFileName == textFileName &&
                        watchedPopupPresetFileName == popupFileName)
            ) {
                configWatcher?.stopWatching()
                configWatcher = object : FileObserver(
                    configDir,
                    CLOSE_WRITE or MODIFY or MOVED_TO or CREATE or DELETE or DELETE_SELF or MOVE_SELF
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null) return
                        val textName = watchedTextLayoutFileName
                        val popupName = watchedPopupPresetFileName
                        if (textName != null && path == textName) {
                            notifyListeners(textLayoutListeners)
                        }
                        if (popupName != null && path == popupName) {
                            notifyListeners(popupPresetListeners)
                        }
                    }
                }.also { it.startWatching() }

                watchedConfigDir = dirPath
                watchedTextLayoutFileName = textFileName
                watchedPopupPresetFileName = popupFileName
            }
        } else if (!hasConfigListeners) {
            configWatcher?.stopWatching()
            configWatcher = null
            watchedConfigDir = null
            watchedTextLayoutFileName = null
            watchedPopupPresetFileName = null
        }

        val fontsetFile = provider.fontsetFile()
        val fontDir = fontsetFile?.parentFile
        if (hasFontsetListeners && fontDir != null) {
            val dirPath = fontDir.absolutePath
            val fileName = fontsetFile.name
            if (!(fontWatcher != null && watchedFontDir == dirPath && watchedFontsetFileName == fileName)) {
                fontWatcher?.stopWatching()
                fontWatcher = object : FileObserver(
                    fontDir,
                    CLOSE_WRITE or MODIFY or MOVED_TO or CREATE or DELETE or DELETE_SELF or MOVE_SELF
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        val target = watchedFontsetFileName
                        if (target == null) return
                        if (path != null && path != target) return
                        notifyListeners(fontsetListeners)
                    }
                }.also { it.startWatching() }

                watchedFontDir = dirPath
                watchedFontsetFileName = fileName
            }
        } else if (!hasFontsetListeners) {
            fontWatcher?.stopWatching()
            fontWatcher = null
            watchedFontDir = null
            watchedFontsetFileName = null
        }
    }

    inline fun <reified T> readTextKeyboardLayout(): UserJsonConfigStore.JsonSnapshot<T>? =
        UserJsonConfigStore.readJson<T>(provider.textKeyboardLayoutFile()).also { ensureWatching() }

    inline fun <reified T> readPopupPreset(): UserJsonConfigStore.JsonSnapshot<T>? =
        UserJsonConfigStore.readJson<T>(provider.popupPresetFile()).also { ensureWatching() }

    fun readFontsetPathMapSnapshot(): Result<UserJsonConfigStore.JsonSnapshot<Map<String, List<String>>>?> =
        runCatching {
            ensureWatching()
            val file = provider.fontsetFile()?.takeIf { it.exists() } ?: return@runCatching null
            val content = UserJsonConfigStore.cleanJson(file.readText(), stripLineComments = true)
            val json = org.json.JSONObject(content)
            val parsed = json.keys().asSequence().associateWith { key ->
                json.optString(key)
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            UserJsonConfigStore.JsonSnapshot(
                value = parsed,
                lastModified = file.lastModified(),
                file = file
            )
        }
}
