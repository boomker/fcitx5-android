/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.picker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.config.UserJsonConfigStore
import org.fxboomk.fcitx5.android.utils.appContext
import timber.log.Timber

/**
 * 符号 / 表情 / 颜文字面板的数据模型与加载器。
 *
 * 数据格式沿用 Foxy 输入法（`com.fxliang.foxy`）的 symbol catalog 约定：
 *
 * ```json
 * {
 *   "multiLine": false,
 *   "groups": [
 *     { "names": { "zh": "英文标点", "zh-Hant": "英文標點", "en": "Western Punctuation" },
 *       "symbols": ["1", "2", "3"] }
 *   ]
 * }
 * ```
 *
 * 两个要点：
 * - `names` 是**多语言字典**而非单个字符串，需要按当前 locale 走回退链（见 [SymbolCatalogs.resolveName]）。
 * - `multiLine` 表示该 catalog 的条目允许换行/长文本（颜文字为 `true`），
 *   用于决定面板用单列还是多列网格渲染。
 */

/** catalog 原始文件结构（与 Foxy 的 `CatalogFile` 对应）。 */
@Serializable
internal data class SymbolCatalogFile(
    val multiLine: Boolean = false,
    val groups: List<SymbolGroupEntry> = emptyList()
)

/** catalog 里的一个分组（与 Foxy 的 `SymbolGroupEntry` 对应）。 */
@Serializable
internal data class SymbolGroupEntry(
    val names: Map<String, String> = emptyMap(),
    val symbols: List<String> = emptyList()
)

/** 已按当前 locale 解析好组名的分组。 */
data class SymbolGroup(val name: String, val symbols: List<String>)

/** 一份已解析的 catalog。 */
data class SymbolCatalog(val groups: List<SymbolGroup>, val multiLine: Boolean)

/**
 * 三份 catalog 的种类。
 *
 * [PickerWindow.Key] 与之一一对应：[PickerWindow.Key.Symbol] → [Symbols]、
 * [PickerWindow.Key.Emoji] → [Emoji]、[PickerWindow.Key.Kaomoji] → [Kaomoji]。
 *
 * - [assetPath]：APK 内置 catalog 路径。
 * - [subDir]：用户自备 catalog 的子目录名，对应 Foxy 的 `frontend/{symbols,emoji,kaomoji}/`。
 * - [recentKey]：是「最近使用」的存储键。**沿用历史键名，不要跟着枚举改名**：
 *   它既是 SharedPreferences 的键（`picker_recently_used`），也是旧版
 *   `filesDir/recently_used/` 迁移文件的文件名（见 [RecentlyUsed.migrate]），
 *   改了会让存量用户的最近使用记录读不到（等于静默丢数据）。
 *   颜文字用 `Emoticon` 就是 2026-09-28 改名前遗留的值。
 */
enum class SymbolCatalogType(
    val assetPath: String,
    val subDir: String,
    val recentKey: String
) {
    Symbols("bundled/symbols/symbols.json", "symbols", "Symbol"),
    Emoji("bundled/symbols/emoji.json", "emoji", "Emoji"),
    Kaomoji("bundled/symbols/kaomoji.json", "kaomoji", "Emoticon")
}

/**
 * catalog 的读取与缓存。
 *
 * 数据来源有两处，与 Foxy 的行为一致：
 * 1. **APK 内置**（默认）。只要用户没有显式选择自备 catalog，就一直用内置数据。
 * 2. **用户自备**：`config/symbol_catalogs/<kind>/<名>.json`，在
 *    「表情和符号」设置里选中后生效。
 *
 * 选中的文件不存在或解析失败时**回退到内置**（Foxy 的 `lv0.c` 也是这个行为），
 * 而不是让面板变空。
 */
object SymbolCatalogs {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 按 "种类|所选文件" 缓存，避免切换选择后拿到旧内容。 */
    private val cache = LinkedHashMap<String, SymbolCatalog>()

    private val zhHantTags = setOf("zh-TW", "zh-HK", "zh-MO")

    /**
     * 读取一份 catalog（遵循当前选择）。失败时回退内置，内置也读不到则返回空 catalog
     * （面板显示为空而不是崩溃）。
     */
    fun get(type: SymbolCatalogType): SymbolCatalog {
        val selection = selectedName(type)
        val languageTag = currentLanguageTag()
        val cacheKey = "${type.name}|$selection|$languageTag"
        synchronized(this) {
            cache[cacheKey]?.let { return it }
        }
        val catalog = load(type, selection, languageTag)
        synchronized(this) {
            cache[cacheKey] = catalog
        }
        return catalog
    }

    /** 当前选中的 catalog 文件名，或 [UserConfigFiles.SYMBOL_CATALOG_BUILTIN] 表示内置。 */
    fun selectedName(type: SymbolCatalogType): String =
        AppPrefs.getInstance().symbols.catalogPreference(type).getValue()

    /** 该种类下可选的用户自备 catalog 文件名。 */
    fun availableFiles(type: SymbolCatalogType): List<String> =
        UserConfigFiles.listSymbolCatalogFiles(type.subDir)

    /** 清除缓存。切换选择或语言变化后调用。 */
    fun invalidate() {
        synchronized(this) {
            cache.clear()
        }
    }

    private fun load(type: SymbolCatalogType, selection: String, languageTag: String): SymbolCatalog {
        if (selection != UserConfigFiles.SYMBOL_CATALOG_BUILTIN) {
            val catalog = loadUserCatalog(type, selection, languageTag)
            if (catalog != null) return catalog
            Timber.w(
                "Symbol catalog %s/%s unusable, falling back to builtin",
                type.subDir, selection
            )
        }
        return loadBuiltin(type, languageTag)
    }

    /**
     * 读取用户自备 catalog。任一环节失败（文件不存在、解析异常、解析后无有效分组）
     * 都返回 null，由调用方回退内置。
     */
    private fun loadUserCatalog(
        type: SymbolCatalogType,
        selection: String,
        languageTag: String
    ): SymbolCatalog? {
        val file = UserConfigFiles.symbolCatalogFile(type.subDir, selection) ?: return null
        if (!file.exists()) return null
        return runCatching {
            // 与其它用户 JSON 配置一致：容忍 // 行注释
            val text = UserJsonConfigStore.stripLineComments(file.readText())
            parse(text, languageTag)
        }.onFailure {
            Timber.w(it, "Failed to parse user symbol catalog %s", selection)
        }.getOrNull()?.takeIf { it.groups.isNotEmpty() }
    }

    private fun loadBuiltin(type: SymbolCatalogType, languageTag: String): SymbolCatalog =
        runCatching {
            val text = appContext.assets.open(type.assetPath)
                .use { it.readBytes().decodeToString() }
            parse(text, languageTag)
        }.onFailure {
            Timber.w(it, "Failed to load builtin symbol catalog %s", type.assetPath)
        }.getOrNull() ?: SymbolCatalog(emptyList(), multiLine = false)

    /**
     * 当前首选 locale 的 BCP-47 标签。
     *
     * 本模块 minSdk 为 24，可直接使用 `Configuration.locales`。
     */
    private fun currentLanguageTag(): String =
        appContext.resources.configuration.locales[0].toLanguageTag()

    /** 解析一份 catalog 文本，按 [languageTag] 挑选组名。 */
    internal fun parse(text: String, languageTag: String): SymbolCatalog {
        val file = json.decodeFromString<SymbolCatalogFile>(text)
        val groups = file.groups.mapNotNull { entry ->
            val name = resolveName(entry.names, languageTag) ?: return@mapNotNull null
            if (entry.symbols.isEmpty()) null else SymbolGroup(name, entry.symbols)
        }
        return SymbolCatalog(groups, file.multiLine)
    }

    /**
     * 组名回退链，与 Foxy 的 `lv0.d` 行为一致：
     *
     * 1. 完整语言标签命中（如 `zh-Hans`）
     * 2. `zh-TW` / `zh-HK` / `zh-MO` 特判走 `zh-Hant`
     * 3. 语言主标签命中（如 `zh`）
     * 4. 兜底：取字典里的第一个值
     */
    internal fun resolveName(names: Map<String, String>, languageTag: String): String? {
        if (names.isEmpty()) return null
        val language = languageTag.substringBefore('-')
        var name: String? = names[languageTag]
        if (name == null && language == "zh" && languageTag in zhHantTags) {
            name = names["zh-Hant"]
        }
        if (name == null) {
            name = names[language]
        }
        if (name == null) {
            name = if (language != "zh" || names["zh-Hant"] == null) {
                names.values.firstOrNull()
            } else {
                names["zh-Hant"]
            }
        }
        return name
    }
}
