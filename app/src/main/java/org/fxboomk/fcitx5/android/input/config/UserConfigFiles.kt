/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import org.fxboomk.fcitx5.android.utils.appContext
import java.io.File

object UserConfigFiles {
    const val DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE = "default"
    private const val TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME = "GeneralKeyboardLayout.json"
    private const val LEGACY_TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME = "TextKeyboardLayout.json"
    private const val TEXT_KEYBOARD_LAYOUT_PREFIX = "GeneralKeyboardLayout."
    private const val LEGACY_TEXT_KEYBOARD_LAYOUT_PREFIX = "TextKeyboardLayout."
    private const val JSON_SUFFIX = ".json"
    private val TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME = Regex(
        "^(?:GeneralKeyboardLayout|TextKeyboardLayout)(?:\\..+)?_backup_\\d{8}_\\d{6}(?:_.*)?\\.json$"
    )

    private fun externalFilesRoot(): File? = appContext.getExternalFilesDir(null)

    /** Same fallback as the native side's `extData` parameter. */
    private fun fcitxConfigDir(): File =
        appContext.getExternalFilesDir(null)?.let { File(it, "config") }
            ?: File(appContext.filesDir, "config")

    fun configDir(): File? = externalFilesRoot()?.let { File(it, "config") }

    fun fontsDir(): File? = externalFilesRoot()?.let { File(it, "fonts") }

    /** fcitx 数据目录（与 native 侧 `FCITX_DATA_HOME` 一致），用户级 PkgData 根。 */
    fun fcitxDataDir(): File? = externalFilesRoot()?.let { File(it, "data") }

    /** 用户级输入法条目目录：写入 `<uniqueName>.conf` 即可让引擎枚举到新的输入法条目。 */
    fun inputMethodConfDir(): File? = fcitxDataDir()?.let { File(it, "inputmethod") }

    /** 单个输入法的专属配置：`conf/pinyin/<uniqueName>.conf`（PkgConfig）。 */
    fun pinyinImConfig(uniqueName: String): File =
        File(File(fcitxConfigDir(), "pinyin"), "$uniqueName.conf")

    fun textKeyboardLayoutJson(): File? = textKeyboardLayoutJson(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)

    fun textKeyboardLayoutJson(profile: String): File? {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: return null
        val dir = configDir() ?: return null
        return resolveExistingTextKeyboardLayoutFile(dir, normalized)
            ?: File(dir, preferredTextKeyboardLayoutFileName(normalized))
    }

    fun normalizeTextKeyboardLayoutProfile(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val sanitized = trimmed
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "")
            .trim()
            .trim('.')
        if (sanitized.isEmpty()) return null
        return if (sanitized.equals(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, ignoreCase = true)) {
            DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        } else {
            sanitized
        }
    }

    fun listTextKeyboardLayoutProfiles(): List<String> {
        val dir = configDir() ?: return listOf(DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        val fileNames = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.toList()
            .orEmpty()

        val profiles = mutableSetOf<String>()
        if (
            fileNames.any {
                it == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME ||
                    it == LEGACY_TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
            }
        ) {
            profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        }
        fileNames.forEach { name ->
            if (TEXT_KEYBOARD_LAYOUT_BACKUP_FILE_NAME.matches(name)) return@forEach
            if (
                name == TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME ||
                    name == LEGACY_TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
            ) {
                return@forEach
            }
            val rawProfile = when {
                name.startsWith(TEXT_KEYBOARD_LAYOUT_PREFIX) && name.endsWith(JSON_SUFFIX) ->
                    name.removePrefix(TEXT_KEYBOARD_LAYOUT_PREFIX).removeSuffix(JSON_SUFFIX)
                name.startsWith(LEGACY_TEXT_KEYBOARD_LAYOUT_PREFIX) && name.endsWith(JSON_SUFFIX) ->
                    name.removePrefix(LEGACY_TEXT_KEYBOARD_LAYOUT_PREFIX).removeSuffix(JSON_SUFFIX)
                else -> null
            }
            if (rawProfile != null) {
                val profile = normalizeTextKeyboardLayoutProfile(rawProfile)
                if (profile != null && profile != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                    profiles += profile
                }
            }
        }

        profiles += DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return profiles.toList().sortedWith(compareBy({ it != DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE }, { it }))
    }

    fun textKeyboardLayoutFileName(profile: String): String {
        val normalized = normalizeTextKeyboardLayoutProfile(profile) ?: DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        return preferredTextKeyboardLayoutFileName(normalized)
    }

    fun popupPresetJson(): File? = configDir()?.let { File(it, "PopupPreset.json") }

    fun swipeSymbolMappingJson(): File? = configDir()?.let { File(it, "SwipeSymbolMapping.json") }

    /**
     * Rime 用户数据目录（fcitx 数据目录下的 rime 子目录，
     * 与设置页"打开用户数据目录"指向的 data/rime 一致）。
     */
    fun rimeDataDir(): File? = externalFilesRoot()?.let { File(it, "data/rime") }

    fun fontsetJson(): File? = fontsDir()?.let { File(it, "fontset.json") }
    
    fun kawaiiBarButtonsConfig(): File? = configDir()?.let { File(it, "KawaiiBarButtonsLayout.json") }

    fun statusAreaButtonsConfig(): File? = configDir()?.let { File(it, "StatusAreaButtonsLayout.json") }

    /**
     * Unified buttons layout configuration file.
     * Replaces separate KawaiiBarButtonsLayout.json and StatusAreaButtonsLayout.json files.
     */
    fun buttonsLayoutConfig(): File? = configDir()?.let { File(it, "ButtonsLayout.json") }

    fun buttonsLayoutThemes(): File? = configDir()?.let { File(it, "ButtonsLayoutThemes.json") }

    /** User-selectable virtual keyboard key icon themes. */
    fun keyIconThemesConfig(): File? = configDir()?.let { File(it, "KeyIconThemes.json") }

    fun customShuangpinSchemeJson(): File = File(fcitxConfigDir(), "CustomShuangpinScheme.json")

    fun customShuangpinSpDat(): File = File(File(fcitxConfigDir(), "pinyin"), "sp.dat")

    private fun preferredTextKeyboardLayoutFileName(profile: String): String {
        return if (profile == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$TEXT_KEYBOARD_LAYOUT_PREFIX$profile$JSON_SUFFIX"
        }
    }

    private fun legacyTextKeyboardLayoutFileName(profile: String): String {
        return if (profile == DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
            LEGACY_TEXT_KEYBOARD_LAYOUT_DEFAULT_FILE_NAME
        } else {
            "$LEGACY_TEXT_KEYBOARD_LAYOUT_PREFIX$profile$JSON_SUFFIX"
        }
    }

    private fun resolveExistingTextKeyboardLayoutFile(dir: File, profile: String): File? {
        val preferred = File(dir, preferredTextKeyboardLayoutFileName(profile))
        if (preferred.isFile) return preferred

        val legacy = File(dir, legacyTextKeyboardLayoutFileName(profile))
        if (legacy.isFile) return legacy

        return null
    }

    /** 表示使用 APK 内置符号 catalog。 */
    const val SYMBOL_CATALOG_BUILTIN = "__builtin__"

    private const val SYMBOL_CATALOG_DIR_NAME = "symbol_catalogs"
    private val SAFE_DIR_NAME = Regex("^(symbols|emoji|kaomoji)$")
    private val SAFE_CATALOG_NAME = Regex("^[^/\\\\:*?\"<>|\\p{Cntrl}]{1,120}\\.json$")

    fun symbolCatalogDir(subDir: String): File? {
        if (!SAFE_DIR_NAME.matches(subDir)) return null
        return configDir()?.let { File(File(it, SYMBOL_CATALOG_DIR_NAME), subDir) }
    }

    fun isValidCatalogName(name: String): Boolean = SAFE_CATALOG_NAME.matches(name)

    fun symbolCatalogFile(subDir: String, name: String): File? {
        val dir = symbolCatalogDir(subDir) ?: return null
        if (!isValidCatalogName(name)) return null
        val file = File(dir, name)
        val basePath = dir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(basePath)) return null
        return file
    }

    fun listSymbolCatalogFiles(subDir: String): List<String> {
        val dir = symbolCatalogDir(subDir) ?: return emptyList()
        val basePath = dir.canonicalPath + File.separator
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { isValidCatalogName(it.name) }
            ?.filter { it.canonicalPath.startsWith(basePath) }
            ?.map { it.name }
            ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
            ?.toList()
            .orEmpty()
    }
}
