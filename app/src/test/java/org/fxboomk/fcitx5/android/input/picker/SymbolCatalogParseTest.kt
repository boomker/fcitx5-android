/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.picker

import org.junit.Assert
import org.junit.Test

class SymbolCatalogParseTest {

    private val sample = """
        {
          "multiLine": false,
          "groups": [
            {
              "names": { "zh": "英文标点", "zh-Hant": "英文標點", "en": "Western Punctuation" },
              "symbols": ["1", "2", "3"]
            },
            {
              "names": { "zh": "数学符号", "zh-Hant": "數學符號", "en": "Mathematical Symbols" },
              "symbols": ["\u00b1", "\u00d7"]
            }
          ]
        }
    """.trimIndent()

    @Test fun exactScriptAndRegionalLabelsWin() {
        val names = mapOf("zh-Hant" to "繁體", "zh-Hans" to "简体", "zh-TW" to "臺灣")
        Assert.assertEquals("简体", SymbolCatalogs.resolveName(names, "zh-Hans"))
        Assert.assertEquals("臺灣", SymbolCatalogs.resolveName(names, "zh-TW"))
        Assert.assertEquals("繁體", SymbolCatalogs.resolveName(names, "zh-HK"))
    }

    @Test
    fun testParseGroupsAndSymbols() {
        val catalog = SymbolCatalogs.parse(sample, "zh-Hans-CN")
        Assert.assertEquals(2, catalog.groups.size)
        Assert.assertEquals("英文标点", catalog.groups[0].name)
        Assert.assertEquals(listOf("1", "2", "3"), catalog.groups[0].symbols)
        Assert.assertEquals("数学符号", catalog.groups[1].name)
        Assert.assertFalse(catalog.multiLine)
    }

    @Test
    fun testMultiLineFlagIsRead() {
        val text = """{ "multiLine": true, "groups": [
            { "names": { "zh": "生气" }, "symbols": ["(=A=)"] } ] }"""
        val catalog = SymbolCatalogs.parse(text, "zh-Hans")
        Assert.assertTrue(catalog.multiLine)
        Assert.assertEquals(1, catalog.groups.size)
    }

    @Test
    fun testTraditionalChinesePrefersZhHant() {
        // zh-TW 走 zh-Hant 专线，而不是简体的 zh
        val catalog = SymbolCatalogs.parse(sample, "zh-TW")
        Assert.assertEquals("英文標點", catalog.groups[0].name)
    }

    @Test
    fun testEnglishLocaleFallsBackToEnglish() {
        val catalog = SymbolCatalogs.parse(sample, "en-US")
        Assert.assertEquals("Western Punctuation", catalog.groups[0].name)
    }

    @Test
    fun testUnknownLocaleFallsBackToFirstValue() {
        // 无匹配语言时兜底取字典第一个值，而不是丢组
        val catalog = SymbolCatalogs.parse(sample, "fr-FR")
        Assert.assertEquals(2, catalog.groups.size)
        Assert.assertEquals("英文标点", catalog.groups[0].name)
    }

    @Test
    fun testEmptyGroupIsDropped() {
        val text = """{ "multiLine": false, "groups": [
          { "names": { "zh": "空组" }, "symbols": [] },
          { "names": { "zh": "有内容" }, "symbols": ["a"] } ] }"""
        val catalog = SymbolCatalogs.parse(text, "zh-Hans")
        Assert.assertEquals(1, catalog.groups.size)
        Assert.assertEquals("有内容", catalog.groups[0].name)
    }

    @Test
    fun testUnknownKeysAreIgnored() {
        // 上游格式演进时不应导致整份 catalog 解析失败
        val text = """{ "multiLine": false, "extra": 42, "groups": [
          { "names": { "zh": "组" }, "symbols": ["a"], "future": true } ] }"""
        val catalog = SymbolCatalogs.parse(text, "zh-Hans")
        Assert.assertEquals(1, catalog.groups.size)
    }

    @Test
    fun testResolveNamePrefersExactTagOverLanguage() {
        // 组名解析按 Foxy 的回退链：完整标签 > 语言主标签
        val names = mapOf("zh" to "简", "zh-Hant" to "繁", "en" to "En")
        Assert.assertEquals("繁", SymbolCatalogs.resolveName(names, "zh-HK"))
        Assert.assertEquals("简", SymbolCatalogs.resolveName(names, "zh-CN"))
        Assert.assertEquals("En", SymbolCatalogs.resolveName(names, "en-US"))
    }

    @Test
    fun testResolveNameReturnsNullForEmptyMap() {
        Assert.assertNull(SymbolCatalogs.resolveName(emptyMap(), "zh-Hans"))
    }
}
