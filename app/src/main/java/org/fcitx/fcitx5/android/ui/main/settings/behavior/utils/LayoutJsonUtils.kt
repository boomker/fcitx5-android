/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.input.keyboard.*

/**
 * JSON 转换工具类，用于键盘布局数据与 JSON 之间的转换。
 * 
 * 主要功能：
 * - [keyDefToJson] - 将 KeyDef 转换为 JSON 地图
 * - [convertToSaveJson] - 将内部数据结构转换为保存用的 JSON 格式
 * - [convertToJsonProperty] - 递归转换任意值为 JsonElement
 */
object LayoutJsonUtils {

    /**
     * 将 KeyDef 转换为 JSON 地图用于保存。
     * 
     * @param keyDef 键盘定义对象
     * @return JSON 地图，包含 type、main、alt、weight 等字段
     */
    fun keyDefToJson(keyDef: KeyDef): MutableMap<String, Any?> {
        val type = when (keyDef) {
            is AlphabetKey -> "AlphabetKey"
            is CapsKey -> "CapsKey"
            is LayoutSwitchKey -> "LayoutSwitchKey"
            is CommaKey -> "CommaKey"
            is LanguageKey -> "LanguageKey"
            is SpaceKey -> "SpaceKey"
            is SymbolKey -> "SymbolKey"
            is ReturnKey -> "ReturnKey"
            is BackspaceKey -> "BackspaceKey"
            else -> "SpaceKey"
        }

        val json = mutableMapOf<String, Any?>("type" to type)
        val appearance = keyDef.appearance

        when (keyDef) {
            is AlphabetKey -> {
                json["main"] = keyDef.character
                json["alt"] = keyDef.punctuation
                json["displayText"] = keyDef.displayText
            }
            is CapsKey -> {
                json["weight"] = appearance.percentWidth
            }
            is LayoutSwitchKey -> {
                json["label"] = (appearance as? KeyDef.Appearance.Text)?.displayText
                json["subLabel"] = keyDef.to
                json["weight"] = appearance.percentWidth
            }
            is CommaKey -> {
                json["weight"] = appearance.percentWidth
            }
            is LanguageKey -> {
                json["weight"] = appearance.percentWidth
            }
            is SpaceKey -> {
                json["weight"] = appearance.percentWidth
            }
            is SymbolKey -> {
                json["label"] = keyDef.symbol
                json["weight"] = appearance.percentWidth
            }
            is ReturnKey -> {
                json["weight"] = appearance.percentWidth
            }
            is BackspaceKey -> {
                json["weight"] = appearance.percentWidth
            }
        }

        return json
    }

    /**
     * 将内部数据结构转换为 JSON 格式用于保存。
     * 
     * 支持子模式布局的嵌套结构：
     * ```json
     * {
     *   "rime": {
     *     "default": [...],
     *     "倉頡五代": [...]
     *   },
     *   "pinyin": [...]
     * }
     * ```
     * 
     * @param entries 布局数据
     * @return JSON 对象
     */
    fun convertToSaveJson(
        entries: Map<String, List<List<Map<String, Any?>>>>
    ): JsonObject {
        val layoutMap = mutableMapOf<String, JsonElement>()

        val baseLayoutNames = entries.keys.map { key ->
            if (key.contains(':')) key.substringBeforeLast(':') else key
        }.distinct()

        for (baseName in baseLayoutNames) {
            val subModeKeys = entries.keys.filter { key ->
                key == baseName || key.startsWith("$baseName:")
            }

            val hasSubModeKeys = subModeKeys.any { key ->
                key != baseName && key.startsWith("$baseName:")
            }

            if (hasSubModeKeys) {
                val subModeMap = mutableMapOf<String, JsonElement>()

                for (key in subModeKeys) {
                    val subModeLabel = if (key.contains(':')) {
                        key.substringAfterLast(':').ifEmpty { "default" }
                    } else {
                        "default"
                    }

                    val rows = entries[key]!!
                    val jsonArray = JsonArray(rows.map { row ->
                        JsonArray(row.map { keyMap ->
                            JsonObject(keyMap.mapValues { (_, v) -> convertToJsonProperty(v) })
                        })
                    })

                    subModeMap[subModeLabel] = jsonArray
                }

                layoutMap[baseName] = JsonObject(subModeMap.toSortedMap())
            } else {
                val key = subModeKeys.firstOrNull() ?: baseName
                val rows = entries[key] ?: continue
                val jsonArray = JsonArray(rows.map { row ->
                    JsonArray(row.map { keyMap ->
                        JsonObject(keyMap.mapValues { (_, v) -> convertToJsonProperty(v) })
                    })
                })
                layoutMap[baseName] = jsonArray
            }
        }

        return JsonObject(layoutMap.toSortedMap())
    }

    /**
     * 递归转换任意值为 JsonElement。
     * 
     * 转换规则：
     * - Map → JsonObject
     * - List → JsonArray
     * - String → JsonPrimitive
     * - Number → JsonPrimitive
     * - Boolean → JsonPrimitive
     * - null → JsonNull
     * - 其他 → JsonPrimitive(value.toString())
     * 
     * @param value 要转换的值
     * @return JsonElement
     */
    fun convertToJsonProperty(value: Any?): JsonElement = when (value) {
        is JsonObject -> value
        is JsonArray -> value
        is Map<*, *> -> {
            val map = value.mapValues { (subKey, subValue) ->
                convertToJsonProperty(subValue)
            }
            JsonObject(map.mapKeys { it.key.toString() }.toMap())
        }
        is List<*> -> {
            val list = value.map { convertToJsonProperty(it) }
            JsonArray(list)
        }
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }
}
