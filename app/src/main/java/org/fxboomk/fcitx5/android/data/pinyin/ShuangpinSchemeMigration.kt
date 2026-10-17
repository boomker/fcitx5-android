/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.pinyin

/** Maps the legacy single "双拼" entry and its hidden profile value to the
 * per-scheme input method introduced by the add-dialog refactor. */
object ShuangpinSchemeMigration {
    const val LEGACY_UNIQUE_NAME = "shuangpin"

    fun targetForLegacyProfile(profile: String?): String = when (profile) {
        "Xiaohe" -> "shuangpin-xiaohe"
        "MS" -> "shuangpin-ms"
        "Ziguang" -> "shuangpin-ziguang"
        "GB Standard", "GB" -> "shuangpin-gb"
        "Ziranma" -> "shuangpin-ziranma"
        "ABC" -> "shuangpin-abc"
        "Zhongwenzhixing" -> "shuangpin-zhongwenzhixing"
        "PinyinJiajia" -> "shuangpin-pinyinjiajia"
        "Custom" -> CustomShuangpinSchemeStore.UNIQUE_NAME
        else -> "shuangpin-ziranma"
    }
}
