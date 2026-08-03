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

internal data class TextKeyboardLayoutResolution(
    val sourceKey: String,
    val root: JsonElement,
    val subMode: JsonElement?,
    val rows: JsonArray?,
)

internal fun resolveTextKeyboardLayout(
    json: JsonObject,
    uniqueName: String,
    displayName: String,
    subModeLabel: String,
): TextKeyboardLayoutResolution? {
    val (sourceKey, root) = when {
        json[uniqueName] != null -> uniqueName to json.getValue(uniqueName)
        json[displayName] != null -> displayName to json.getValue(displayName)
        json["default"] != null -> "default" to json.getValue("default")
        else -> return null
    }
    val layout = root as? JsonObject
    val subMode = layout?.get(subModeLabel)
    val rowsSource = when (root) {
        is JsonArray -> root
        is JsonObject -> (
            subMode
                ?: root["default"]
                ?: root[""]
            )
        else -> null
    }
    return TextKeyboardLayoutResolution(
        sourceKey = sourceKey,
        root = root,
        subMode = subMode,
        rows = rowsSource.rowsOrNull(),
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
    return layoutHeightPercentFromMeta(subMode, key) ?: layoutHeightPercentFromMeta(root, key)
}

private fun layoutHeightPercentFromMeta(layout: JsonElement?, key: String): Int? {
    val meta = (layout as? JsonObject)?.get("__meta__") as? JsonObject ?: return null
    return (meta[key] as? JsonPrimitive)
        ?.intOrNull
        ?.takeIf { it in 10..90 }
}

private fun JsonElement?.rowsOrNull(): JsonArray? {
    return when (this) {
        is JsonArray -> this
        is JsonObject -> (this["rows"] ?: this["default"] ?: this[""]) as? JsonArray
        else -> null
    }
}
