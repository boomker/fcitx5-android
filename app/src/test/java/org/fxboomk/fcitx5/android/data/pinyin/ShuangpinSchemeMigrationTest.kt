/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Test

class ShuangpinSchemeMigrationTest {
    @Test
    fun mapsLegacyProfileNamesToSchemeEntries() {
        assertEquals("shuangpin-xiaohe", ShuangpinSchemeMigration.targetForLegacyProfile("Xiaohe"))
        assertEquals("shuangpin-ms", ShuangpinSchemeMigration.targetForLegacyProfile("MS"))
        assertEquals("shuangpin-ziguang", ShuangpinSchemeMigration.targetForLegacyProfile("Ziguang"))
        assertEquals("shuangpin-gb", ShuangpinSchemeMigration.targetForLegacyProfile("GB Standard"))
        assertEquals("shuangpin-ziranma", ShuangpinSchemeMigration.targetForLegacyProfile("Ziranma"))
        assertEquals("shuangpin-abc", ShuangpinSchemeMigration.targetForLegacyProfile("ABC"))
        assertEquals(
            "shuangpin-zhongwenzhixing",
            ShuangpinSchemeMigration.targetForLegacyProfile("Zhongwenzhixing")
        )
        assertEquals(
            "shuangpin-pinyinjiajia",
            ShuangpinSchemeMigration.targetForLegacyProfile("PinyinJiajia")
        )
        assertEquals(
            CustomShuangpinSchemeStore.UNIQUE_NAME,
            ShuangpinSchemeMigration.targetForLegacyProfile("Custom")
        )
    }

    @Test
    fun missingOrUnknownProfileFallsBackToZiranma() {
        assertEquals("shuangpin-ziranma", ShuangpinSchemeMigration.targetForLegacyProfile(null))
        assertEquals("shuangpin-ziranma", ShuangpinSchemeMigration.targetForLegacyProfile("Unknown"))
    }
}
