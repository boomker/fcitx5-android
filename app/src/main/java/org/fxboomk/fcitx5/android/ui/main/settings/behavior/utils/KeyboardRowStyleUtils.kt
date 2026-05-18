/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils

object KeyboardRowStyleUtils {

    const val ROW_META_MARKER = "__rowMeta"
    const val ROW_KEYS_FIELD = "keys"
    const val ROW_HEIGHT_MULTIPLIER = "heightMultiplier"
    const val ROW_ALT_TEXT_POSITION = "altTextPosition"
    const val ROW_BACKGROUND_STYLE = "backgroundStyle"
    const val ROW_BACKGROUND_COLOR = "backgroundColor"

    enum class AltTextPosition(val wireValue: String) {
        Top("top"),
        TopRight("topRight"),
        Bottom("bottom");

        companion object {
            fun fromWireValue(value: String?): AltTextPosition? = entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true)
            }
        }
    }

    enum class BackgroundStyle(val wireValue: String) {
        Solid("solid"),
        Gradient("gradient");

        companion object {
            fun fromWireValue(value: String?): BackgroundStyle? = entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true)
            }
        }
    }

    data class RowStyle(
        val heightMultiplier: Float = 1f,
        val altTextPosition: AltTextPosition? = null,
        val backgroundStyle: BackgroundStyle? = null,
        val backgroundColor: Int? = null
    ) {
        fun isDefault(): Boolean {
            return heightMultiplier == 1f &&
                altTextPosition == null &&
                backgroundStyle == null &&
                backgroundColor == null
        }
    }

    fun isRowMeta(entry: Map<String, Any?>): Boolean = entry[ROW_META_MARKER] == true

    fun visibleKeys(row: List<Map<String, Any?>>): List<Map<String, Any?>> = row.filterNot(::isRowMeta)

    fun visibleMutableKeys(row: List<MutableMap<String, Any?>>): List<MutableMap<String, Any?>> = row.filterNot(::isRowMeta)

    fun visibleKeyCount(row: List<Map<String, Any?>>): Int = row.count { !isRowMeta(it) }

    fun actualToVisibleIndex(row: List<Map<String, Any?>>, actualIndex: Int): Int {
        if (actualIndex !in row.indices || isRowMeta(row[actualIndex])) return -1
        var visibleIndex = 0
        row.forEachIndexed { index, entry ->
            if (isRowMeta(entry)) return@forEachIndexed
            if (index == actualIndex) return visibleIndex
            visibleIndex++
        }
        return -1
    }

    fun visibleToActualIndex(row: List<Map<String, Any?>>, visibleIndex: Int): Int {
        if (visibleIndex < 0) return visibleIndex
        var currentVisible = 0
        row.forEachIndexed { actualIndex, entry ->
            if (isRowMeta(entry)) return@forEachIndexed
            if (currentVisible == visibleIndex) return actualIndex
            currentVisible++
        }
        return row.size
    }

    fun visibleInsertToActualIndex(row: List<Map<String, Any?>>, visibleSlot: Int): Int {
        if (visibleSlot <= 0) {
            return if (row.firstOrNull()?.let(::isRowMeta) == true) 1 else 0
        }
        var currentVisible = 0
        row.forEachIndexed { actualIndex, entry ->
            if (isRowMeta(entry)) return@forEachIndexed
            if (currentVisible == visibleSlot) return actualIndex
            currentVisible++
        }
        return row.size
    }

    fun actualInsertToVisibleIndex(row: List<Map<String, Any?>>, actualIndex: Int): Int {
        val clamped = actualIndex.coerceIn(0, row.size)
        var visibleCount = 0
        row.take(clamped).forEach { entry ->
            if (!isRowMeta(entry)) visibleCount++
        }
        return visibleCount
    }

    fun rowStyle(row: List<Map<String, Any?>>): RowStyle = rowMeta(row)?.let(::rowStyleFromMeta) ?: RowStyle()

    fun rowMeta(row: List<Map<String, Any?>>): Map<String, Any?>? = row.firstOrNull(::isRowMeta)

    fun applyRowStyle(row: MutableList<MutableMap<String, Any?>>, style: RowStyle) {
        row.removeAll(::isRowMeta)
        if (!style.isDefault()) {
            row.add(0, buildMeta(style))
        }
    }

    fun buildMeta(style: RowStyle): MutableMap<String, Any?> {
        val meta = mutableMapOf<String, Any?>(ROW_META_MARKER to true)
        if (style.heightMultiplier != 1f) {
            meta[ROW_HEIGHT_MULTIPLIER] = style.heightMultiplier
        }
        style.altTextPosition?.let { meta[ROW_ALT_TEXT_POSITION] = it.wireValue }
        style.backgroundStyle?.let { meta[ROW_BACKGROUND_STYLE] = it.wireValue }
        style.backgroundColor?.let { meta[ROW_BACKGROUND_COLOR] = it }
        return meta
    }

    fun rowStyleFromMeta(meta: Map<String, Any?>?): RowStyle {
        if (meta == null) return RowStyle()
        return RowStyle(
            heightMultiplier = parseFloat(meta[ROW_HEIGHT_MULTIPLIER])?.takeIf { it > 0f } ?: 1f,
            altTextPosition = AltTextPosition.fromWireValue(meta[ROW_ALT_TEXT_POSITION] as? String),
            backgroundStyle = BackgroundStyle.fromWireValue(meta[ROW_BACKGROUND_STYLE] as? String),
            backgroundColor = parseInt(meta[ROW_BACKGROUND_COLOR])
        ).normalize()
    }

    fun resolveRowBackgroundColor(
        style: RowStyle,
        visibleIndex: Int,
        visibleCount: Int
    ): Int? {
        val baseColor = style.backgroundColor ?: return null
        return when (style.backgroundStyle) {
            BackgroundStyle.Solid -> baseColor
            BackgroundStyle.Gradient -> {
                if (visibleCount <= 1) return baseColor
                val start = blendArgb(baseColor, 0xffffffff.toInt(), 0.18f)
                val end = blendArgb(baseColor, 0xff000000.toInt(), 0.12f)
                val ratio = visibleIndex.coerceIn(0, visibleCount - 1).toFloat() / (visibleCount - 1).toFloat()
                blendArgb(start, end, ratio)
            }
            null -> null
        }
    }

    private fun RowStyle.normalize(): RowStyle {
        val normalizedHeight = heightMultiplier.takeIf { it > 0f } ?: 1f
        val normalizedStyle = backgroundStyle.takeIf { backgroundColor != null }
        val normalizedColor = backgroundColor.takeIf { normalizedStyle != null }
        return copy(
            heightMultiplier = normalizedHeight,
            backgroundStyle = normalizedStyle,
            backgroundColor = normalizedColor
        )
    }

    private fun parseFloat(value: Any?): Float? = when (value) {
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

    private fun parseInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toLongOrNull()?.toInt()
        else -> null
    }

    private fun blendArgb(from: Int, to: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        return argb(
            lerp(channel(from, 24), channel(to, 24), t),
            lerp(channel(from, 16), channel(to, 16), t),
            lerp(channel(from, 8), channel(to, 8), t),
            lerp(channel(from, 0), channel(to, 0), t)
        )
    }

    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xff

    private fun lerp(from: Int, to: Int, ratio: Float): Int {
        return (from + ((to - from) * ratio)).toInt().coerceIn(0, 255)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int {
        return ((a and 0xff) shl 24) or
            ((r and 0xff) shl 16) or
            ((g and 0xff) shl 8) or
            (b and 0xff)
    }
}
