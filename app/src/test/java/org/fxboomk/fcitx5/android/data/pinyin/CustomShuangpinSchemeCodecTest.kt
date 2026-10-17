/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.pinyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomShuangpinSchemeCodecTest {
    @Test
    fun defaultSchemeUsesXiaoheMappings() {
        val scheme = CustomShuangpinSchemeCodec.defaultScheme()
        assertEquals(CustomShuangpinSchemeCodec.DEFAULT_NAME, scheme.name)
        assertEquals("*", scheme.zeroInitial)
        assertEquals("v", scheme.initials["zh"])
        assertEquals("i", scheme.initials["ch"])
        assertEquals("u", scheme.initials["sh"])
        assertEquals("d", scheme.finals["ai"])
        assertEquals("s", scheme.finals["ong"])
        assertEquals("v", scheme.finals["v"])
    }

    @Test
    fun generatedSpDatContainsNameAndMappings() {
        val scheme = CustomShuangpinScheme(name = "我的双拼", zeroInitial = "o*")
            .copy(
                initials = mapOf("zh" to "v", "ch" to "i", "sh" to "u"),
                finals = mapOf("ai" to "l", "an" to "j", "ong" to "s")
            )
        val text = CustomShuangpinSchemeCodec.toSpDat(scheme)

        assertTrue(text.contains("方案名称=我的双拼"))
        assertTrue(text.contains("=o*"))
        assertTrue(text.contains("zh=v"))
        assertTrue(text.contains("ch=i"))
        assertTrue(text.contains("sh=u"))
        assertTrue(text.contains("ai=l"))
        assertTrue(text.contains("an=j"))
        assertTrue(text.contains("ong=s"))
    }

    @Test
    fun parsedSpDatRoundTripsKnownMappings() {
        val text = """
            [方案]
            方案名称=测试方案

            [零声母标识]
            =O*

            [声母]
            zh=v
            ch=i
            sh=u

            [韵母]
            ai=l
            an=j
            ing=;
        """.trimIndent()

        val scheme = CustomShuangpinSchemeCodec.parseSpDat(text)

        assertEquals("测试方案", scheme.name)
        assertEquals("o*", scheme.zeroInitial)
        assertEquals("v", scheme.initials["zh"])
        assertEquals("i", scheme.initials["ch"])
        assertEquals("u", scheme.initials["sh"])
        assertEquals("l", scheme.finals["ai"])
        assertEquals("j", scheme.finals["an"])
        assertEquals(";", scheme.finals["ing"])
    }

    @Test
    fun builtInNamesAreRejected() {
        assertFalse(CustomShuangpinSchemeCodec.isValidName("自然码"))
        assertFalse(CustomShuangpinSchemeCodec.isValidName("小鹤"))
        assertFalse(CustomShuangpinSchemeCodec.isValidName("  "))
        assertTrue(CustomShuangpinSchemeCodec.isValidName("我的双拼"))
    }

    @Test
    fun zeroInitialValidationAcceptsLettersAndStar() {
        assertTrue(CustomShuangpinSchemeCodec.isValidZeroInitial("o*"))
        assertTrue(CustomShuangpinSchemeCodec.isValidZeroInitial("a"))
        assertFalse(CustomShuangpinSchemeCodec.isValidZeroInitial(""))
        assertFalse(CustomShuangpinSchemeCodec.isValidZeroInitial("O*"))
        assertFalse(CustomShuangpinSchemeCodec.isValidZeroInitial("o?"))
    }

    @Test
    fun syllableOverridesAreParsedAndGenerated() {
        val text = """
            [方案]
            方案名称=测试方案

            [声母]
            zh=v

            [韵母]
            ai=d

            [音节]
            ju=jv
            shi=ui
            shi=is
            e=ee
        """.trimIndent()

        val scheme = CustomShuangpinSchemeCodec.parseSpDat(text)
        assertEquals(listOf("ui", "is"), scheme.syllables["shi"])
        assertEquals(listOf("jv"), scheme.syllables["ju"])
        assertEquals(listOf("ee"), scheme.syllables["e"])

        val generated = CustomShuangpinSchemeCodec.toSpDat(scheme)
        assertTrue(generated.contains("[音节]"))
        assertTrue(generated.contains("ju=jv"))
        assertTrue(generated.contains("shi=ui"))
        assertTrue(generated.contains("shi=is"))
        assertTrue(generated.contains("e=ee"))
    }
}
