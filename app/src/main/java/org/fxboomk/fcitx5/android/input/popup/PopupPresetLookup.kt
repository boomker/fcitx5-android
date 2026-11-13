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

/**
 * Brackets grouped by the direction they open in. A grouped key only owns the
 * symbol family advertised by its corner, so `h` (corner `(`) keeps `[`, `{`,
 * `【`, while `jk` (corner `)`) keeps `]`, `}`, `】`.
 */
private val openingBrackets = setOf(
    '(', '[', '{', '（', '［', '｛', '【', '〔', '〖', '〘', '〚', '《', '〈', '「', '『', '⟨',
)
private val closingBrackets = setOf(
    ')', ']', '}', '）', '］', '｝', '】', '〕', '〗', '〙', '〛', '》', '〉', '」', '』', '⟩',
)

private fun bracketFamily(corner: String): Set<Char>? = when {
    corner.length != 1 -> null
    corner[0] in openingBrackets -> openingBrackets
    corner[0] in closingBrackets -> closingBrackets
    else -> null
}

/**
 * Only accept candidates that agree with the corner: letters (including the
 * accented forms of the group letters), the corner itself, and brackets that
 * open in the same direction. Digits and symbols from other keys are dropped so
 * `we` cannot offer `3` and `jk` cannot offer `{`.
 */
private fun matchesCorner(candidate: String, corner: String, family: Set<Char>?): Boolean {
    if (candidate.isEmpty()) return false
    if (candidate == corner) return true
    if (candidate.isNotEmpty() && candidate.all { it.isLetter() }) return true
    return family != null && candidate.length == 1 && candidate[0] in family
}

/**
 * Collect the bracket variants of the requested direction from every preset the
 * user can see, so a custom `【` lands on the opening corner and a custom `】`
 * lands on the closing one.
 */
private fun cornerFamilyCandidates(
    family: Set<Char>,
    customPresets: Map<String, Array<String>>?,
    builtInPresets: Map<String, Array<String>>,
): List<String> {
    val effectivePresets = LinkedHashMap(builtInPresets)
    customPresets?.forEach { (key, values) -> effectivePresets[key] = values }
    return buildList {
        effectivePresets.values.forEach { values ->
            values.forEach { value ->
                if (value.length == 1 && value[0] in family) add(value)
            }
        }
    }.distinct()
}

internal fun resolveGroupedPopupPresetCandidates(
    group: String,
    corner: String,
    customPresets: Map<String, Array<String>>? = null,
    builtInPresets: Map<String, Array<String>> = PopupPreset,
): Array<String> {
    val family = bracketFamily(corner)
    return buildList {
        // Keep the existing letter choices and default focus ahead of the presets.
        addAll(group.map { if (it == ';') ":" else it.uppercase() })
        if (corner.isNotEmpty()) add(corner)
        addAll(group.map(Char::toString))
        group.forEach { letter ->
            resolvePopupPresetCandidates(letter.toString(), null, customPresets, builtInPresets)
                ?.forEach { candidate ->
                    if (matchesCorner(candidate, corner, family)) add(candidate)
                }
        }
        family?.let { addAll(cornerFamilyCandidates(it, customPresets, builtInPresets)) }
    }.distinct().toTypedArray()
}
