/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.popup

/**
 * Resolve a popup preset using the visible key label first and the emitted
 * character as a fallback. PopupPreset is a presentation map: when a layout
 * deliberately displays `l` for a key that emits `L`, the lower-case preset is
 * the one the user sees and expects to edit.
 */
internal fun resolvePopupPresetCandidates(
    label: String,
    fallbackLabel: String?,
    customPresets: Map<String, Array<String>>? = null,
    builtInPresets: Map<String, Array<String>> = PopupPreset,
): Array<String>? {
    val keys = buildList {
        fun addWithCaseVariants(value: String?) {
            value?.takeIf { it.isNotBlank() }?.let { key ->
                add(key)
                if (key.length == 1 && key[0].isLetter()) {
                    val oppositeCase = if (key[0].isUpperCase()) key.lowercase() else key.uppercase()
                    if (oppositeCase != key) add(oppositeCase)
                }
            }
        }
        addWithCaseVariants(label)
        fallbackLabel
            ?.takeIf { it.isNotBlank() && it != label }
            ?.let(::addWithCaseVariants)
    }
    // A user supplied entry must win even when it is stored under the logical
    // case and the first visible-case candidate also has a built-in preset.
    keys.firstNotNullOfOrNull { key -> customPresets?.get(key) }?.let { return it }
    keys.firstNotNullOfOrNull { key -> builtInPresets[key] }?.let { return it }
    return null
}
