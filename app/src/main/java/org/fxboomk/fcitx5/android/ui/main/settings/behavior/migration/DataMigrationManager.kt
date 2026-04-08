/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data migration manager responsible for keyboard layout data migration, backup, and restoration.
 *
 * Migration strategy:
 * 1. For layouts with existing submode layouts (e.g., "rime:倉頡五代"):
 *    - Extract displayText values from base layout to corresponding submode layouts
 *    - Convert base layout displayText to simple strings or remove them
 * 2. For layouts without submode layouts:
 *    - Keep displayText in original format for backward compatibility
 *
 * Main functions:
 * - [checkIfMigrationNeeded] - Check if migration is needed
 * - [createBackup] - Create timestamped backup (called on every save)
 * - [restoreFromBackup] - Restore from backup
 * - [migrateAllDisplayTextToSubmodeStructure] - Execute migration
 * - [parseJsonToEntries] - Parse JSON to data structure
 */
class DataMigrationManager(
    private val dataManager: LayoutDataManager
) {
    companion object {
        private const val TAG = "DataMigrationManager"
        private const val BACKUP_KEEP_COUNT = 3
    }

    private val entries = dataManager.entries
    private var lastBackupFile: File? = null

    /**
     * Create a timestamped backup file.
     *
     * Backup file name format: `{originalFileName}_backup_{timestamp}.json`
     * Example: `TextKeyboardLayout_backup_20260317_143022.json`
     *
     * This method should be called on every save to create a backup.
     * Old backups are automatically cleaned up, keeping only the latest N backups.
     *
     * @param originalFile The original file to backup
     * @return The backup file, or null if failed
     */
    fun createBackup(originalFile: File): File? {
        return runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val backupFileName = "${originalFile.nameWithoutExtension}_backup_$timestamp.json"
            val backupFile = File(originalFile.parentFile, backupFileName)

            originalFile.copyTo(backupFile, overwrite = false)
            Log.i(TAG, "Backup created: ${backupFile.absolutePath}")

            this.lastBackupFile = backupFile
            cleanupOldBackups(originalFile)

            backupFile
        }.onFailure { e ->
            Log.e(TAG, "Failed to create backup", e)
        }.getOrNull()
    }

    /**
     * 检测是否需要迁移。
     * 
     * 检测条件：
     * 1. 存在子模式布局（如 "rime:倉頡五代"）
     * 2. 基础布局中仍有旧格式的 displayText: { "倉頡五代": "手" }
     * 
     * @return true 需要迁移，false 数据已是新格式
     */
    fun checkIfMigrationNeeded(): Boolean {
        val layoutGroups = entries.keys.groupBy { key ->
            if (key.contains(':')) key.substringBeforeLast(':') else key
        }

        layoutGroups.forEach { (baseName, keys) ->
            val hasSubModeLayouts = keys.any { it.contains(':') && it != "$baseName:default" }

            if (hasSubModeLayouts) {
                val baseKey = keys.firstOrNull { it == baseName || it == "$baseName:default" }
                val baseLayout = baseKey?.let { entries[it] }
                baseLayout?.forEach { row ->
                    row.forEach { key ->
                        val displayText = key["displayText"]
                        if (displayText is Map<*, *> && displayText.isNotEmpty()) {
                            return true
                        }
                    }
                }
            }
        }

        return false
    }

    /**
     * Clean up old backup files, keeping only the latest N backups.
     */
    private fun cleanupOldBackups(originalFile: File) {
        runCatching {
            val backupPattern = "${originalFile.nameWithoutExtension}_backup_"
            val backups = originalFile.parentFile
                ?.listFiles { file ->
                    file.name.startsWith(backupPattern) && file.name.endsWith(".json")
                }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (backups.size > BACKUP_KEEP_COUNT) {
                backups.drop(BACKUP_KEEP_COUNT).forEach { oldBackup ->
                    if (oldBackup.delete()) {
                        Log.i(TAG, "Deleted old backup: ${oldBackup.absolutePath}")
                    }
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to cleanup old backups", e)
        }
    }

    /**
     * Restore from the latest backup file.
     *
     * @param targetFile The target file to restore to
     */
    fun restoreFromBackup(targetFile: File?) {
        if (targetFile == null) return

        // Find the latest backup file
        val backupPattern = "${targetFile.nameWithoutExtension}_backup_"
        val latestBackup = targetFile.parentFile
            ?.listFiles { file ->
                file.name.startsWith(backupPattern) && file.name.endsWith(".json")
            }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()

        if (latestBackup == null || !latestBackup.exists()) {
            Log.w(TAG, "No backup file found for restoration")
            return
        }

        runCatching {
            latestBackup.copyTo(targetFile, overwrite = true)
            Log.i(TAG, "Restored from backup: ${latestBackup.absolutePath}")
        }.onFailure { e ->
            Log.e(TAG, "Failed to restore from backup", e)
        }
    }

    /**
     * Automatically migrate old displayText format to new submode structure.
     *
     * Migration strategy:
     * 1. For layouts with submode layouts:
     *    - Extract displayText values from base layout to corresponding submode layouts
     *    - Convert base layout displayText to simple strings or remove them
     * 2. For layouts without submode layouts:
     *    - Keep displayText in original format for backward compatibility
     */
    fun migrateAllDisplayTextToSubmodeStructure() {
        val layoutGroups = entries.keys.groupBy { key ->
            if (key.contains(':')) key.substringBeforeLast(':') else key
        }

        layoutGroups.forEach { (baseName, keys) ->
            val subModeKeys = keys.filter { it.contains(':') && it != "$baseName:default" }

            if (subModeKeys.isEmpty()) {
                val baseKey = keys.firstOrNull { it == baseName || it == "$baseName:default" }
                baseKey?.let { normalizeDisplayTextToMap(it) }
                return@forEach
            }

            val baseKey = keys.firstOrNull { it == baseName || it == "$baseName:default" }

            subModeKeys.forEach { subModeKey ->
                val subModeLabel = subModeKey.substringAfterLast(':')
                val subModeLayout = entries[subModeKey]
                subModeLayout?.let { layout ->
                    migrateDisplayTextForSubMode(layout, subModeLabel)
                }
            }

            baseKey?.let { cleanupBaseLayoutDisplayText(baseName) }
        }
    }

    /**
     * Migrate displayText from old format to new submode format.
     */
    internal fun migrateDisplayTextForSubMode(
        layout: MutableList<MutableList<MutableMap<String, Any?>>>,
        subModeLabel: String
    ) {
        for (row in layout) {
            for (key in row) {
                val displayText = key["displayText"]
                when (displayText) {
                    is Map<*, *> -> {
                        val specificValue = displayText[subModeLabel]?.toString()
                        val defaultValue = displayText["default"]?.toString()
                        val newValue = specificValue ?: defaultValue

                        if (newValue != null) {
                            key["displayText"] = newValue
                        } else {
                            key.remove("displayText")
                        }
                    }
                }
            }
        }
    }

    /**
     * Clean up base layout's displayText entries that are now covered by submode layouts.
     */
    internal fun cleanupBaseLayoutDisplayText(layoutName: String) {
        val baseLayout = entries[layoutName] ?: return

        val subModeKeys = entries.keys.filter {
            it.startsWith("$layoutName:") && it != "$layoutName:default"
        }

        if (subModeKeys.isEmpty()) return

        for (row in baseLayout) {
            for (key in row) {
                val displayText = key["displayText"]
                when (displayText) {
                    is MutableMap<*, *> -> {
                        val keysToRemove = subModeKeys.map { it.substringAfter("$layoutName:") }
                            .filter { it in displayText.keys }
                        keysToRemove.forEach { keyToRemove ->
                            displayText.remove(keyToRemove)
                        }

                        val remainingKeys = displayText.keys.filter { it != "default" }
                        if (remainingKeys.isEmpty()) {
                            val defaultValue = displayText["default"]?.toString()
                            if (defaultValue != null) {
                                key["displayText"] = defaultValue
                            } else if (displayText.isEmpty()) {
                                key.remove("displayText")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Normalize displayText from JsonObject to Map<String, Any?> for consistent handling.
     *
     * Note: The cast is safe because JSON keys are always strings.
     */
    @Suppress("UNCHECKED_CAST")
    private fun normalizeDisplayTextToMap(layoutKey: String) {
        val layout = entries[layoutKey] ?: return
        for (row in layout) {
            for (key in row) {
                val displayText = key["displayText"]
                if (displayText is Map<*, *>) {
                    key["displayText"] = displayText.toMutableMap() as MutableMap<String, Any?>
                }
            }
        }
    }

    /**
     * 解析 JsonElement 到数据结构。
     * 
     * @param jsonObject JSON 对象
     * @return 解析后的数据结构
     */
    fun parseJsonToEntries(jsonObject: JsonObject): Map<String, List<List<Map<String, Any?>>>> {
        val result = mutableMapOf<String, List<List<Map<String, Any?>>>>()

        jsonObject.entries.forEach { (layoutName, layoutValue) ->
            when (layoutValue) {
                is JsonArray -> {
                    val rows = dataManager.parseLayoutRows(layoutValue.jsonArray)
                    result[layoutName] = rows
                }
                is JsonObject -> {
                    layoutValue.entries.forEach { (subModeLabel, subModeValue) ->
                        if (subModeValue is JsonArray) {
                            val rows = dataManager.parseLayoutRows(subModeValue.jsonArray)
                            val key = if (subModeLabel == "default") {
                                layoutName
                            } else {
                                "$layoutName:$subModeLabel"
                            }
                            result[key] = rows
                        }
                    }
                }
                else -> {}
            }
        }
        return result
    }
}
