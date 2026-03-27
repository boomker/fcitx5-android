/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.utils

import android.util.Log
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.input.keyboard.*

// Import Macro types explicitly
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef

/**
 * JSON 转换工具类，用于键盘布局数据与 JSON 之间的双向转换。
 *
 * 主要功能：
 * - 解析：[parseKeyJsonArray], [parseLayoutRows], [parseOptionalFloat]
 * - 转换：[keyDefToJson], [convertToSaveJson], [convertToJsonProperty]
 * - 工具：[removeJsonComments], [resolveDisplayText]
 */
object LayoutJsonUtils {

    private const val TAG = "LayoutJsonUtils"

    // ==================== 解析功能 ====================

    /**
     * 从 JSON 字符串中移除 // 注释。
     *
     * 改进的引号处理：正确识别转义引号 (\")
     *
     * @param jsonStr 原始 JSON 字符串
     * @return 移除注释后的 JSON 字符串
     */
    fun removeJsonComments(jsonStr: String): String {
        return jsonStr.lines()
            .joinToString("\n") { line ->
                val commentIdx = line.indexOf("//")
                if (commentIdx >= 0) {
                    val beforeComment = line.substring(0, commentIdx)
                    // 更准确的引号计数：考虑转义引号
                    var quoteCount = 0
                    var i = 0
                    while (i < beforeComment.length) {
                        if (beforeComment[i] == '"' && (i == 0 || beforeComment[i - 1] != '\\')) {
                            quoteCount++
                        }
                        i++
                    }
                    if (quoteCount % 2 == 0) line.substring(0, commentIdx) else line
                } else line
            }
    }

    /**
     * 解析可选的 Float 值。
     *
     * 支持以下格式：
     * - JsonPrimitive(Number) → Float
     * - JsonPrimitive(String) → 尝试解析为 Float
     * - JsonNull → null
     * - null → null
     *
     * @param element JSON 元素
     * @return 解析后的 Float 值，或 null
     */
    fun parseOptionalFloat(element: JsonElement?): Float? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return if (primitive.isString) {
            primitive.content
                .trim()
                .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                ?.toFloatOrNull()
        } else {
            primitive.floatOrNull ?: primitive.doubleOrNull?.toFloat()
        }
    }

    /**
     * 解析 displayText 字段。
     *
     * 根据当前子模式标签和名称，从 displayText 中解析出正确的显示文本。
     *
     * 优先级：
     * 1. 匹配 subModeLabel
     * 2. 匹配 subModeName
     * 3. 空字符串键 ""
     * 4. 返回 default
     *
     * @param displayText displayText JSON 元素
     * @param subModeLabel 当前子模式标签
     * @param subModeName 当前子模式名称
     * @param default 默认值
     * @return 解析后的显示文本
     */
    fun resolveDisplayText(
        displayText: JsonElement?,
        subModeLabel: String,
        subModeName: String,
        default: String
    ): String {
        return when {
            displayText == null -> default
            displayText is JsonPrimitive -> displayText.content
            displayText is JsonObject -> resolveDisplayTextMap(displayText, subModeLabel, subModeName) ?: default
            else -> default
        }
    }

    private fun resolveDisplayTextMap(
        map: JsonObject,
        subModeLabel: String,
        subModeName: String
    ): String? {
        // 优先级：subModeLabel → subModeName
        return map[subModeLabel]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
            ?: map[subModeName]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
    }

    /**
     * 解析单个 KeyJson 对象。
     *
     * @param obj JSON 对象
     * @return 解析后的 KeyJson，如果 type 缺失则返回 null
     */
    fun parseKeyJson(obj: JsonObject): KeyJson? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return KeyJson(
            type = type,
            main = obj["main"]?.jsonPrimitive?.content,
            alt = obj["alt"]?.jsonPrimitive?.content,
            displayText = obj["displayText"],  // AlphabetKey 和 MacroKey 共用
            label = obj["label"]?.jsonPrimitive?.content,
            altLabel = obj["altLabel"]?.jsonPrimitive?.content,
            subLabel = obj["subLabel"]?.jsonPrimitive?.content,
            weight = parseOptionalFloat(obj["weight"]),
            tap = obj["tap"]?.jsonObject?.let { parseMacroAction(it) },
            swipe = obj["swipe"]?.jsonObject?.let { parseMacroAction(it) },
            longPress = obj["longPress"]?.jsonObject?.let { parseMacroAction(it) }
        )
    }

    /**
     * 解析 KeyRef（按键引用）
     * @param obj JSON 对象，包含 "fcitx" 或 "android" 字段
     * @return 解析后的 KeyRef
     */
    fun parseKeyRef(obj: JsonObject): KeyRef {
        // 优先解析 fcitx 类型
        obj["fcitx"]?.jsonPrimitive?.content?.let { return KeyRef.Fcitx(it) }
        
        // 解析 android 类型（只接受整数键码）
        obj["android"]?.let { androidValue ->
            val keyCode = androidValue.jsonPrimitive.intOrNull
                ?: androidValue.jsonPrimitive.content.toIntOrNull()
            if (keyCode != null) {
                return KeyRef.Android(keyCode)
            }
        }
        
        // 如果 android 值不是整数，可能是数据错误（如 {"android": "Left"}）
        // 尝试将其作为 fcitx 键名处理
        obj["android"]?.jsonPrimitive?.content?.let {
            return KeyRef.Fcitx(it)
        }
        
        throw IllegalArgumentException("Invalid KeyRef: $obj")
    }

    /**
     * 解析 MacroStep（宏步骤）
     * @param obj JSON 对象，包含 "type" 字段和其他参数字段
     * @return 解析后的 MacroStep
     */
    fun parseMacroStep(obj: JsonObject): MacroStep {
        val type = obj["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing step type")
        return when (type) {
            "down" -> MacroStep.Down(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "up" -> MacroStep.Up(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "tap" -> MacroStep.Tap(obj["keys"]?.jsonArray?.map { parseKeyRef(it.jsonObject) } ?: emptyList())
            "text" -> MacroStep.Text(obj["text"]?.jsonPrimitive?.content ?: "")
            "edit" -> MacroStep.Edit(obj["action"]?.jsonPrimitive?.content ?: "copy")
            "shortcut" -> {
                val modifiers = obj["modifiers"]?.jsonArray?.map { parseKeyRef(it.jsonObject) }
                    ?: obj["modifier"]?.jsonObject?.let { listOf(parseKeyRef(it)) }
                    ?: throw IllegalArgumentException("Missing modifiers for shortcut")
                val key = obj["key"]?.jsonObject?.let { parseKeyRef(it) }
                    ?: throw IllegalArgumentException("Missing key for shortcut")
                MacroStep.Shortcut(modifiers, key)
            }
            else -> throw IllegalArgumentException("Unknown step type: $type")
        }
    }

    /**
     * 解析 MacroAction（宏动作）
     * @param obj JSON 对象，包含 "macro" 字段
     * @return 解析后的 MacroAction
     */
    fun parseMacroAction(obj: JsonObject): MacroAction {
        val steps = obj["macro"]?.jsonArray?.map { parseMacroStep(it.jsonObject) } ?: emptyList()
        return MacroAction(steps)
    }

    /**
     * 解析一行按键数据。
     *
     * @param rowArray 包含按键的 JSON 数组
     * @param showLangSwitch 是否显示语言切换键（用于过滤 LanguageKey）
     * @return 解析后的 KeyJson 列表
     */
    fun parseKeyJsonArray(rowArray: JsonArray, showLangSwitch: Boolean = true): List<KeyJson> {
        return rowArray.mapNotNull { element ->
            val obj = element.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: ""
            // 如果 showLangSwitch 为 false，跳过 LanguageKey
            if (type == "LanguageKey" && !showLangSwitch) {
                return@mapNotNull null
            }
            parseKeyJson(obj)
        }
    }

    /**
     * 从 JsonArray 解析布局行数据为 Map 表示。
     *
     * @param rowsArray JSON 数组，包含布局行数据
     * @return 解析后的布局行列表
     */
    fun parseLayoutRows(rowsArray: JsonArray): List<List<Map<String, Any?>>> {
        val rows = mutableListOf<List<Map<String, Any?>>>()
        for (i in rowsArray.indices) {
            val rowArray = rowsArray[i].jsonArray
            val row = mutableListOf<Map<String, Any?>>()
            for (j in rowArray.indices) {
                val rowElement = rowArray[j]
                if (rowElement is JsonNull) continue
                if (rowElement !is JsonObject) {
                    Log.w(TAG, "Skipping invalid key element at row $i, col $j: ${rowElement::class.simpleName}")
                    continue
                }

                val keyJson = rowElement
                val keyMap = mutableMapOf<String, Any?>()
                keyJson.entries.forEach { (key, value) ->
                    keyMap[key] = normalizeKeyValue(key, toAny(value))
                }
                row.add(keyMap)
            }
            rows.add(row)
        }
        return rows
    }

    /**
     * 规范化键值，特别处理 weight 字段。
     *
     * @param key 键名
     * @param value 原始值
     * @return 规范化后的值
     */
    private fun normalizeKeyValue(key: String, value: Any?): Any? {
        if (key != "weight") return value
        return when (value) {
            null -> null
            is Number -> value.toFloat()
            is String -> {
                value.trim()
                    .takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
                    ?.toFloatOrNull()
            }
            else -> null
        }
    }

    /**
     * 将 JsonElement 递归转换为 Any? 类型。
     *
     * 转换规则：
     * - JsonObject → Map<String, Any?>
     * - JsonArray → List<Any?>
     * - JsonPrimitive(String) → String
     * - JsonPrimitive(Boolean) → Boolean
     * - JsonPrimitive(Number) → Number
     * - JsonNull → null
     *
     * @param element JSON 元素
     * @return 转换后的 Any? 值
     */
    fun toAny(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.toMap().mapValues { it.value.let { v -> toAny(v) } }
        is JsonArray -> element.map { toAny(it) }
        is JsonPrimitive -> {
            if (element.isString) element.content
            else element.booleanOrNull ?: element.intOrNull ?: element.doubleOrNull
        }
        is JsonNull -> null
    }

    // ==================== 转换功能 ====================

    /**
     * 数据类，表示解析后的按键 JSON 数据。
     *
     * @property type 按键类型
     * @property main 主要字符（AlphabetKey）
     * @property alt 备选字符（AlphabetKey）
     * @property displayText 显示文本（支持子模式）
     * @property label 标签（LayoutSwitchKey, SymbolKey, MacroKey）
     * @property altLabel 备选标签（MacroKey）
     * @property subLabel 子标签（LayoutSwitchKey）
     * @property weight 权重
     * @property tap 点击宏（MacroKey）
     * @property swipe 划动宏（MacroKey）
     * @property longPress 长按宏（MacroKey）
     */
    data class KeyJson(
        val type: String,
        val main: String? = null,
        val alt: String? = null,
        val displayText: JsonElement? = null,  // AlphabetKey 和 MacroKey 共用
        val label: String? = null,  // MacroKey/SymbolKey/LayoutSwitchKey 使用
        val altLabel: String? = null,  // MacroKey 使用
        val subLabel: String? = null,  // LayoutSwitchKey 使用
        val weight: Float? = null,
        val tap: MacroAction? = null,  // MacroKey 使用
        val swipe: MacroAction? = null,  // MacroKey 使用
        val longPress: MacroAction? = null  // MacroKey 使用
    )

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
            is MacroKey -> "MacroKey"
            else -> "SpaceKey"
        }

        val json = mutableMapOf<String, Any?>("type" to type)
        val appearance = keyDef.appearance

        when (keyDef) {
            is AlphabetKey -> {
                json["main"] = keyDef.character
                json["alt"] = keyDef.punctuation
                json["displayText"] = keyDef.displayText
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
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
            is MacroKey -> {
                json["label"] = keyDef.label
                if (keyDef.altLabel != null) {
                    json["altLabel"] = keyDef.altLabel
                }
                json["tap"] = macroActionToJson(keyDef.tap)
                keyDef.swipe?.let { json["swipe"] = macroActionToJson(it) }
                keyDef.longPress?.let { json["longPress"] = macroActionToJson(it) }
                json["weight"] = appearance.percentWidth.takeIf { it != 0.1f }
            }
        }

        return json
    }

    /**
     * 将 MacroAction 转换为 JSON 对象。
     *
     * @param action MacroAction 对象
     * @return JSON 对象，包含 "macro" 字段
     */
    fun macroActionToJson(action: MacroAction): JsonObject {
        val steps = JsonArray(action.steps.map { macroStepToJson(it) })
        return JsonObject(mapOf("macro" to steps))
    }

    /**
     * 将 MacroStep 转换为 JSON 对象。
     *
     * @param step MacroStep 对象
     * @return JSON 对象
     */
    fun macroStepToJson(step: MacroStep): JsonObject {
        return when (step) {
            is MacroStep.Down -> JsonObject(mapOf(
                "type" to JsonPrimitive("down"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Up -> JsonObject(mapOf(
                "type" to JsonPrimitive("up"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Tap -> JsonObject(mapOf(
                "type" to JsonPrimitive("tap"),
                "keys" to JsonArray(step.keys.map { keyRefToJson(it) })
            ))
            is MacroStep.Text -> JsonObject(mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(step.text)
            ))
            is MacroStep.Edit -> JsonObject(mapOf(
                "type" to JsonPrimitive("edit"),
                "action" to JsonPrimitive(step.action)
            ))
            is MacroStep.Shortcut -> JsonObject(mapOf(
                "type" to JsonPrimitive("shortcut"),
                "modifiers" to JsonArray(step.modifiers.map { keyRefToJson(it) }),
                "key" to keyRefToJson(step.key)
            ))
        }
    }

    /**
     * 将 KeyRef 转换为 JSON 对象。
     *
     * @param ref KeyRef 对象
     * @return JSON 对象
     */
    fun keyRefToJson(ref: KeyRef): JsonObject {
        return when (ref) {
            is KeyRef.Fcitx -> JsonObject(mapOf("fcitx" to JsonPrimitive(ref.code)))
            is KeyRef.Android -> JsonObject(mapOf("android" to JsonPrimitive(ref.code)))
        }
    }

    /**
     * 将 KeyJson 转换为 KeyDef。
     *
     * @param key 解析后的 KeyJson
     * @param subModeLabel 当前子模式标签（用于解析 displayText）
     * @param subModeName 当前子模式名称（用于解析 displayText）
     * @return 转换后的 KeyDef
     */
    fun createKeyDef(key: KeyJson, subModeLabel: String = "", subModeName: String = ""): KeyDef {
        return when (key.type) {
            "AlphabetKey" -> AlphabetKey(
                character = key.main ?: "",
                punctuation = key.alt ?: "",
                displayText = resolveDisplayText(
                    key.displayText,
                    subModeLabel,
                    subModeName,
                    key.main ?: ""
                ),
                weight = key.weight
            )
            "CapsKey" -> CapsKey(
                percentWidth = key.weight ?: 0.15f
            )
            "LayoutSwitchKey" -> LayoutSwitchKey(
                displayText = key.label ?: "?123",
                to = key.subLabel ?: "",
                percentWidth = key.weight ?: 0.15f
            )
            "CommaKey" -> CommaKey(
                percentWidth = key.weight ?: 0.1f,
                variant = KeyDef.Appearance.Variant.Alternative
            )
            "LanguageKey" -> LanguageKey(
                percentWidth = key.weight ?: 0.1f
            )
            "SpaceKey" -> SpaceKey(
                percentWidth = key.weight ?: 0f
            )
            "SymbolKey" -> SymbolKey(
                symbol = key.label ?: ".",
                percentWidth = key.weight ?: 0.1f,
                variant = KeyDef.Appearance.Variant.Alternative
            )
            "ReturnKey" -> ReturnKey(
                percentWidth = key.weight ?: 0.15f
            )
            "BackspaceKey" -> BackspaceKey(
                percentWidth = key.weight ?: 0.15f
            )
            "MacroKey" -> {
                val tap = key.tap ?: throw IllegalArgumentException("MacroKey requires 'tap' action")
                // 解析 label：基础 label + displayText 多模式覆盖
                // 优先使用 displayText 中当前 submode 的值，否则使用基础 label
                val baseLabel = key.label ?: ""
                val label = resolveDisplayText(
                    key.displayText,
                    subModeLabel,
                    subModeName,
                    baseLabel  // 默认值为基础 label（displayText 中不需要 default 键）
                )
                // altLabel 不随 submode 变化（像 AlphabetKey 的 alt 那样）
                val altLabel = key.altLabel ?: ""
                MacroKey(
                    label = label,
                    altLabel = altLabel.ifEmpty { null },
                    tap = tap,
                    swipe = key.swipe,
                    longPress = key.longPress,
                    percentWidth = key.weight ?: 0.1f
                )
            }
            else -> SpaceKey() // Fallback
        }
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
