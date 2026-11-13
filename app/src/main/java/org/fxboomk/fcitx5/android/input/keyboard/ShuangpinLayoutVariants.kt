/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.KeyboardRowStyleUtils

/** The compact keyboard is a layout variant, independent of the engine's sub-mode. */
internal object ShuangpinLayoutVariants {
    const val CHILD_KEY = "__compact18__"

    fun entryKey(imeKey: String): String = "$imeKey:$CHILD_KEY"

    fun isCompactEntry(key: String): Boolean = key.endsWith(":$CHILD_KEY")

    fun baseKey(key: String): String = key.removeSuffix(":$CHILD_KEY")

    /** Validate the letter partition before activating a custom compact keyboard. */
    fun validationErrors(
        rows: List<List<Map<String, Any?>>>,
        requireSemicolon: Boolean = false,
    ): List<String> = buildList {
        val presentGroups = mutableSetOf<String>()
        if (rows.isEmpty()) add("18 键布局没有任何行")
        rows.forEachIndexed { rowIndex, row ->
            val keys = KeyboardRowStyleUtils.visibleKeys(row)
            if (keys.isEmpty()) add("18 键布局第 ${rowIndex + 1} 行为空")
            keys.forEachIndexed { keyIndex, key ->
                if (key["type"] == "ShuangpinGroupKey") {
                    val groupId = key["groupId"] as? String
                    if (!Shuangpin18Keys.isValidGroup(groupId)) {
                        add("第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键的 groupId 无效：${groupId.orEmpty()}")
                    } else {
                        presentGroups.add(requireNotNull(groupId))
                    }
                }
                key["weight"]?.let { value ->
                    val width = when (value) {
                        is Number -> value.toFloat()
                        is String -> value.toFloatOrNull()
                        else -> null
                    }
                    if (width == null || !width.isFinite() || width !in 0f..1f) {
                        add("第 ${rowIndex + 1} 行第 ${keyIndex + 1} 个键的宽度必须在 0 到 1 之间")
                    }
                }
            }
        }
        addAll(Shuangpin18Keys.groupingErrors(presentGroups.toList(), requireSemicolon))
    }
}
