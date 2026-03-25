/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonObject
import java.io.File

typealias ConfigChangeListener = () -> Unit

interface ConfigProvider {
    fun textKeyboardLayoutFile(): File?
    fun textKeyboardLayoutJson(): JsonObject? = null
    fun popupPresetFile(): File?
    fun fontsetFile(): File?
    fun buttonsLayoutConfigFile(): File?
    fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File>
}

object DefaultConfigProvider : ConfigProvider {
    override fun textKeyboardLayoutFile(): File? = UserConfigFiles.textKeyboardLayoutJson()
    override fun popupPresetFile(): File? = UserConfigFiles.popupPresetJson()
    override fun fontsetFile(): File? = UserConfigFiles.fontsetJson()
    override fun buttonsLayoutConfigFile(): File? = UserConfigFiles.buttonsLayoutConfig()
    override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
        UserJsonConfigStore.writeFontsetPathMap(pathMap)
}

/**
 * A [ConfigProvider] that uses in-memory JSON data instead of file-based storage.
 * This is useful for preview scenarios to avoid disk I/O.
 *
 * @param textKeyboardLayoutJson The in-memory JSON data for text keyboard layout
 * @param delegate The delegate provider for other config files
 */
class MemoryConfigProvider(
    private val textKeyboardLayoutJson: JsonObject,
    private val delegate: ConfigProvider
) : ConfigProvider {
    override fun textKeyboardLayoutFile(): File? = null
    override fun textKeyboardLayoutJson(): JsonObject = textKeyboardLayoutJson
    override fun popupPresetFile(): File? = delegate.popupPresetFile()
    override fun fontsetFile(): File? = delegate.fontsetFile()
    override fun buttonsLayoutConfigFile(): File? = delegate.buttonsLayoutConfigFile()
    override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
        delegate.writeFontsetPathMap(pathMap)
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
    
    @Volatile
    private var watchedButtonsLayoutFileName: String? = null

    private val textLayoutListeners = linkedSetOf<() -> Unit>()
    private val popupPresetListeners = linkedSetOf<() -> Unit>()
    private val fontsetListeners = linkedSetOf<() -> Unit>()
    private val buttonsLayoutListeners = linkedSetOf<() -> Unit>()

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
    fun addButtonsLayoutListener(listener: () -> Unit) {
        buttonsLayoutListeners.add(listener)
        ensureWatching()
    }

    @Synchronized
    fun removeButtonsLayoutListener(listener: () -> Unit) {
        buttonsLayoutListeners.remove(listener)
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
        watchedButtonsLayoutFileName = null
        watchedFontDir = null
        watchedFontsetFileName = null
        ensureWatching()
    }

    @Synchronized
    fun ensureWatching() {
        // Skip file watching when provider uses in-memory JSON (e.g., preview scenarios)
        // This avoids null pointer exceptions and unnecessary file monitoring
        if (provider.textKeyboardLayoutJson() != null) {
            return
        }

        val hasConfigListeners = textLayoutListeners.isNotEmpty() || popupPresetListeners.isNotEmpty() ||
                buttonsLayoutListeners.isNotEmpty()
        val hasFontsetListeners = fontsetListeners.isNotEmpty()

        if (!hasConfigListeners && !hasFontsetListeners) return

        val textFile = provider.textKeyboardLayoutFile()
        val popupFile = provider.popupPresetFile()
        val buttonsLayoutFile = provider.buttonsLayoutConfigFile()
        val configDir = textFile?.parentFile ?: popupFile?.parentFile ?: buttonsLayoutFile?.parentFile

        if (hasConfigListeners && configDir != null) {
            val dirPath = configDir.absolutePath
            val textFileName = textFile?.name
            val popupFileName = popupFile?.name
            val buttonsLayoutFileName = buttonsLayoutFile?.name

            if (!(configWatcher != null &&
                        watchedConfigDir == dirPath &&
                        watchedTextLayoutFileName == textFileName &&
                        watchedPopupPresetFileName == popupFileName &&
                        watchedButtonsLayoutFileName == buttonsLayoutFileName)
            ) {
                configWatcher?.stopWatching()
                // Use String path for API 28 compatibility (HarmonyOS 9.1.0)
                // Suppress deprecation warning: deprecated constants are still supported on all API levels
                @Suppress("DEPRECATION")
                configWatcher = object : FileObserver(
                    dirPath,
                    CLOSE_WRITE or MODIFY or MOVED_TO or CREATE or DELETE or DELETE_SELF or MOVE_SELF
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null) return
                        val textName = watchedTextLayoutFileName
                        val popupName = watchedPopupPresetFileName
                        val buttonsLayoutName = watchedButtonsLayoutFileName
                        if (textName != null && path == textName) {
                            notifyListeners(textLayoutListeners)
                        }
                        if (popupName != null && path == popupName) {
                            notifyListeners(popupPresetListeners)
                        }
                        if (buttonsLayoutName != null && path == buttonsLayoutName) {
                            notifyListeners(buttonsLayoutListeners)
                        }
                    }
                }.also { it.startWatching() }

                watchedConfigDir = dirPath
                watchedTextLayoutFileName = textFileName
                watchedPopupPresetFileName = popupFileName
                watchedButtonsLayoutFileName = buttonsLayoutFileName
            }
        } else if (!hasConfigListeners) {
            configWatcher?.stopWatching()
            configWatcher = null
            watchedConfigDir = null
            watchedTextLayoutFileName = null
            watchedPopupPresetFileName = null
            watchedButtonsLayoutFileName = null
        }

        val fontsetFile = provider.fontsetFile()
        val fontDir = fontsetFile?.parentFile
        if (hasFontsetListeners && fontDir != null) {
            val dirPath = fontDir.absolutePath
            val fileName = fontsetFile.name
            if (!(fontWatcher != null && watchedFontDir == dirPath && watchedFontsetFileName == fileName)) {
                fontWatcher?.stopWatching()
                // Use String path for API 28 compatibility (HarmonyOS 9.1.0)
                // Suppress deprecation warning: deprecated constants are still supported on all API levels
                @Suppress("DEPRECATION")
                fontWatcher = object : FileObserver(
                    dirPath,
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

    inline fun <reified T> readTextKeyboardLayout(): UserJsonConfigStore.JsonSnapshot<T>? {
        // Try to read from in-memory JSON first to avoid disk I/O
        val memoryJson = provider.textKeyboardLayoutJson()
        if (memoryJson != null) {
            return UserJsonConfigStore.readJson<T>(memoryJson).also { ensureWatching() }
        }
        // Fallback to file-based reading
        return UserJsonConfigStore.readJson<T>(provider.textKeyboardLayoutFile()).also { ensureWatching() }
    }

    inline fun <reified T> readPopupPreset(): UserJsonConfigStore.JsonSnapshot<T>? =
        UserJsonConfigStore.readJson<T>(provider.popupPresetFile()).also { ensureWatching() }

    /**
     * Read unified buttons layout configuration.
     * Supports migration from old separate config files.
     */
    inline fun <reified T> readButtonsLayoutConfig(): UserJsonConfigStore.JsonSnapshot<T>? =
        UserJsonConfigStore.readJson<T>(provider.buttonsLayoutConfigFile()).also { ensureWatching() }

    /**
     * @deprecated Use readButtonsLayoutConfig instead
     */
    @Deprecated("Use readButtonsLayoutConfig instead", ReplaceWith("readButtonsLayoutConfig()"))
    inline fun <reified T> readKawaiiBarButtonsConfig(): UserJsonConfigStore.JsonSnapshot<T>? =
        readButtonsLayoutConfig()

    /**
     * @deprecated Use readButtonsLayoutConfig instead
     */
    @Deprecated("Use readButtonsLayoutConfig instead", ReplaceWith("readButtonsLayoutConfig()"))
    inline fun <reified T> readStatusAreaButtonsConfig(): UserJsonConfigStore.JsonSnapshot<T>? =
        readButtonsLayoutConfig()

    fun readFontsetPathMapSnapshot(): Result<UserJsonConfigStore.JsonSnapshot<Map<String, List<String>>>?> =
        runCatching {
            ensureWatching()
            UserJsonConfigStore.readFontsetPathMapSnapshot().getOrNull()
        }
}
