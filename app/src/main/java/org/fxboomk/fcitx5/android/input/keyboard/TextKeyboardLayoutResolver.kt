/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys

/**
 * 英文键盘输入法的 uniqueName。只有英文键盘才回退到文件级共享的 `default` 布局：
 * 其余未定制专属布局的输入法一律使用出厂 26 键布局，避免复用可被用户定制的
 * "English"（`default`）布局造成布局污染。
 */
private const val ENGLISH_IME_UNIQUE_NAME = "keyboard-us"

internal data class TextKeyboardLayoutResolution(
    val sourceKey: String,
    /** Top-level JSON key that matched this input method (may be a display-name fallback). */
    val layoutKey: String,
    val root: JsonElement,
    val subMode: JsonElement?,
    /** The child key that matched the active sub-mode, when one exists. */
    val subModeKey: String?,
    val subModeLabel: String,
    val globalDefault: JsonElement?,
    /** Key for [globalDefault] when it is eligible to provide the effective height. */
    val globalDefaultKey: String?,
    val rows: JsonArray?,
    /** Compact layouts add variant heights ahead of the existing IME height chain. */
    val heightSources: List<KeyboardLayoutHeightSource>? = null,
)

internal data class KeyboardLayoutHeightSource(
    val target: KeyboardHeightOverrideTarget,
    val value: JsonElement,
)

internal sealed interface KeyboardHeightOverrideTarget {
    data class Layout(val layoutKey: String) : KeyboardHeightOverrideTarget
    data class SubMode(val layoutKey: String, val subModeLabel: String) : KeyboardHeightOverrideTarget
    data object Profile : KeyboardHeightOverrideTarget
}

internal fun resolveTextKeyboardLayout(
    json: JsonObject,
    uniqueName: String,
    displayName: String,
    subModeLabel: String,
    subModeName: String = "",
    layoutVariant: ShuangpinKeyboardLayout = ShuangpinKeyboardLayout.Full,
): TextKeyboardLayoutResolution? {
    if (layoutVariant == ShuangpinKeyboardLayout.Compact18) {
        return resolveCompactTextKeyboardLayout(json, uniqueName, displayName, subModeLabel, subModeName)
    }
    val parentKey = ShuangpinLayoutKeys.parentBaseLayoutKey(uniqueName)
    val (matchedSourceKey, root) = when {
        json[uniqueName] != null -> uniqueName to json.getValue(uniqueName)
        json[displayName] != null -> displayName to json.getValue(displayName)
        // 双拼方案回退到"双拼"父级输入法布局（所有内置 / 自定义双拼的公共父布局）
        parentKey != null && json[parentKey] != null -> parentKey to json.getValue(parentKey)
        // 仅英文键盘回退到共享的 default 布局，避免非英文输入法复用可定制的 English 布局
        uniqueName == ENGLISH_IME_UNIQUE_NAME && json["default"] != null ->
            "default" to json.getValue("default")
        else -> return null
    }
    val layout = root as? JsonObject
    // Rime exposes a short display label (for example `朙`) alongside the
    // schema name (`朙月拼音`). User layout files are keyed by either form,
    // depending on when they were created. Prefer the visible label for
    // backwards compatibility, then use the complete schema name.
    val subModeCandidates = listOf(subModeLabel.trim(), subModeName.trim())
        .filter { it.isNotEmpty() }
        .distinct()
    val matchedSubModeKey = subModeCandidates.firstOrNull { layout?.containsKey(it) == true }
    val subMode = matchedSubModeKey?.let { layout?.get(it) }
    val isChildOnlyLayout = layout != null && layout["default"] == null && layout[""] == null
    // 兜底基准：双拼方案以"双拼"父级输入法布局为基准；其余输入法沿用全局 English 兜底
    // （英文键盘本身即 default，没有兜底）。
    val baselineKey = when {
        matchedSourceKey == "default" -> null
        parentKey != null -> parentKey.takeIf { it != matchedSourceKey }
        else -> "default"
    }
    val globalDefault = baselineKey?.let { json[it] }
    val rowFallbackKey = baselineKey ?: "default"
    val rowSources = when (root) {
        is JsonArray -> listOf(matchedSourceKey to root)
        is JsonObject -> listOfNotNull(
            subMode?.let { matchedSourceKey to it },
            (root["default"] ?: root[""])?.let { matchedSourceKey to it },
            globalDefault?.let { rowFallbackKey to it },
        )
        else -> listOfNotNull(globalDefault?.let { rowFallbackKey to it })
    }
    val (sourceKey, rows) = rowSources.firstNotNullOfOrNull { (key, source) ->
        source.rowsOrNull()?.let { key to it }
    } ?: (matchedSourceKey to null)
    // 兜底高度基准：双拼方案即便拥有自己的行，缺失的高度也应以"双拼"父级输入法布局
    // 为基准；其余输入法仅在子布局（无自己的行）时才继承兜底高度，避免显式
    // 父级输入法布局意外继承 English 的高度。
    val heightBaselineKey = baselineKey?.takeIf {
        isChildOnlyLayout || (parentKey != null && parentKey != matchedSourceKey)
    }
    val heightBaseline = heightBaselineKey?.let { json[it] }
    return TextKeyboardLayoutResolution(
        sourceKey = sourceKey,
        layoutKey = matchedSourceKey,
        root = root,
        subMode = subMode,
        subModeKey = matchedSubModeKey,
        subModeLabel = subModeLabel,
        globalDefault = heightBaseline,
        globalDefaultKey = heightBaselineKey,
        rows = rows,
    )
}

private fun resolveCompactTextKeyboardLayout(
    json: JsonObject,
    uniqueName: String,
    displayName: String,
    subModeLabel: String,
    subModeName: String,
): TextKeyboardLayoutResolution? {
    if (!ShuangpinKeyboardLayouts.supports(uniqueName)) return null
    val parentKey = ShuangpinLayoutKeys.parentBaseLayoutKey(uniqueName)
    val candidates = listOfNotNull(uniqueName, displayName, parentKey).distinct()
    val compactSources = candidates.mapNotNull { key ->
        val root = json[key] as? JsonObject ?: return@mapNotNull null
        root[ShuangpinLayoutVariants.CHILD_KEY]?.let { key to it }
    }
    val legacy = resolveTextKeyboardLayout(json, uniqueName, displayName, subModeLabel, subModeName)
    val selected = compactSources.firstOrNull()
    // Keep the ordinary height chain even when all compact rows use the built-in template.
    val layoutKey = selected?.first ?: legacy?.layoutKey ?: return null
    val root = json.getValue(layoutKey)
    val compact = selected?.second
    val heights = compactSources.map { (key, value) ->
        KeyboardLayoutHeightSource(
            KeyboardHeightOverrideTarget.SubMode(key, ShuangpinLayoutVariants.CHILD_KEY), value,
        )
    } + legacy?.let { resolution ->
        listOfNotNull(
            resolution.subMode?.let {
                KeyboardLayoutHeightSource(
                    KeyboardHeightOverrideTarget.SubMode(
                        resolution.layoutKey, resolution.subModeKey ?: resolution.subModeLabel,
                    ), it,
                )
            },
            KeyboardLayoutHeightSource(KeyboardHeightOverrideTarget.Layout(resolution.layoutKey), resolution.root),
            resolution.globalDefaultKey?.let { key ->
                resolution.globalDefault?.let {
                    KeyboardLayoutHeightSource(KeyboardHeightOverrideTarget.Layout(key), it)
                }
            },
        )
    }.orEmpty()
    return TextKeyboardLayoutResolution(
        sourceKey = selected?.let { ShuangpinLayoutVariants.entryKey(it.first) } ?: "compact18-default",
        layoutKey = layoutKey,
        root = root,
        subMode = compact,
        subModeKey = compact?.let { ShuangpinLayoutVariants.CHILD_KEY },
        subModeLabel = subModeLabel,
        globalDefault = null,
        globalDefaultKey = null,
        rows = compact.rowsOrNull(),
        heightSources = heights,
    )
}

internal fun TextKeyboardLayoutResolution.keyboardHeightPercentOverride(
    landscape: Boolean,
): Int? {
    val key = if (landscape) {
        "keyboard_height_percent_landscape"
    } else {
        "keyboard_height_percent"
    }
    heightSources?.let { sources ->
        return sources.firstNotNullOfOrNull { layoutHeightPercentFromMeta(it.value, key) }
    }
    return layoutHeightPercentFromMeta(subMode, key)
        ?: layoutHeightPercentFromMeta(root, key)
        ?: layoutHeightPercentFromMeta(globalDefault, key)
}

/**
 * Returns the layout metadata location that currently supplies this orientation's explicit height.
 * File-level profile metadata is resolved separately because it is not part of a layout resolution.
 */
internal fun TextKeyboardLayoutResolution.keyboardHeightOverrideTarget(
    landscape: Boolean,
): KeyboardHeightOverrideTarget? {
    val key = keyboardHeightPercentMetaKey(landscape)
    heightSources?.let { sources ->
        return sources.firstOrNull { layoutHeightPercentFromMeta(it.value, key) != null }?.target
    }
    if (layoutHeightPercentFromMeta(subMode, key) != null) {
        return KeyboardHeightOverrideTarget.SubMode(layoutKey, subModeKey ?: subModeLabel)
    }
    if (layoutHeightPercentFromMeta(root, key) != null) {
        return KeyboardHeightOverrideTarget.Layout(layoutKey)
    }
    if (globalDefaultKey != null && layoutHeightPercentFromMeta(globalDefault, key) != null) {
        return KeyboardHeightOverrideTarget.Layout(globalDefaultKey)
    }
    return null
}

/**
 * Applies an orientation-specific height override while preserving the rest of the JSON tree.
 * Array layouts are wrapped in the established object form with their rows stored under `default`.
 */
internal fun JsonObject.withKeyboardHeightPercentOverride(
    target: KeyboardHeightOverrideTarget,
    landscape: Boolean,
    percent: Int,
): JsonObject? {
    val metaKey = keyboardHeightPercentMetaKey(landscape)
    val value = JsonPrimitive(percent.coerceIn(10, 90))
    return when (target) {
        is KeyboardHeightOverrideTarget.Layout -> {
            val current = this[target.layoutKey] ?: return null
            val updated = current.withHeightPercentMetadata(metaKey, value) ?: return null
            JsonObject(toMutableMap().apply { put(target.layoutKey, updated) })
        }
        is KeyboardHeightOverrideTarget.SubMode -> {
            val layout = this[target.layoutKey] as? JsonObject ?: return null
            val current = layout[target.subModeLabel] ?: return null
            val updated = current.withHeightPercentMetadata(metaKey, value) ?: return null
            JsonObject(toMutableMap().apply {
                put(
                    target.layoutKey,
                    JsonObject(layout.toMutableMap().apply { put(target.subModeLabel, updated) })
                )
            })
        }
        KeyboardHeightOverrideTarget.Profile -> {
            val profile = this[PROFILE_META_KEY] as? JsonObject ?: return null
            JsonObject(toMutableMap().apply {
                put(PROFILE_META_KEY, profile.withHeightPercentMetadata(metaKey, value) as JsonObject)
            })
        }
    }
}

/**
 * 文件级（profile 层）键盘高度覆写：文件顶层 {"__profile__": {"__meta__": {...}}}。
 * 字面量需与 LayoutJsonUtils.PROFILE_META_KEY 保持一致。
 */
internal const val PROFILE_META_KEY = "__profile__"

internal fun JsonObject?.fileLevelKeyboardHeightPercentOverride(landscape: Boolean): Int? {
    if (this == null) return null
    val profile = this[PROFILE_META_KEY] as? JsonObject ?: return null
    return layoutHeightPercentFromMeta(
        profile,
        if (landscape) "keyboard_height_percent_landscape" else "keyboard_height_percent"
    )
}

private fun layoutHeightPercentFromMeta(layout: JsonElement?, key: String): Int? {
    val meta = (layout as? JsonObject)?.get("__meta__") as? JsonObject ?: return null
    return (meta[key] as? JsonPrimitive)
        ?.intOrNull
        ?.takeIf { it in 10..90 }
}

private fun keyboardHeightPercentMetaKey(landscape: Boolean): String = if (landscape) {
    "keyboard_height_percent_landscape"
} else {
    "keyboard_height_percent"
}

private fun JsonElement.withHeightPercentMetadata(key: String, value: JsonPrimitive): JsonElement? {
    return when (this) {
        is JsonArray -> JsonObject(
            mapOf(
                "default" to this,
                "__meta__" to JsonObject(mapOf(key to value)),
            )
        )
        is JsonObject -> JsonObject(toMutableMap().apply {
            val meta = (this["__meta__"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            meta[key] = value
            put("__meta__", JsonObject(meta))
        })
        else -> null
    }
}

private fun JsonElement?.rowsOrNull(): JsonArray? {
    return when (this) {
        is JsonArray -> this
        is JsonObject -> (this["rows"] ?: this["default"] ?: this[""]) as? JsonArray
        else -> null
    }
}
