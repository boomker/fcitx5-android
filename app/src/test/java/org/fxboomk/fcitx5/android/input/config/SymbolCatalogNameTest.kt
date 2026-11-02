/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.config

import org.junit.Assert
import org.junit.Test

/**
 * 用户自备符号 catalog 的文件名校验。
 *
 * 这是**安全边界**：名字会被拼进 `config/symbol_catalogs/<kind>/` 下的路径，
 * 若允许路径分隔符或控制字符，就能越出目录读写任意文件。
 */
class SymbolCatalogNameTest {

    @Test
    fun testAcceptsNormalNames() {
        Assert.assertTrue(UserConfigFiles.isValidCatalogName("my-symbols.json"))
        Assert.assertTrue(UserConfigFiles.isValidCatalogName("符号.json"))
        Assert.assertTrue(UserConfigFiles.isValidCatalogName("my emoji.json"))
        Assert.assertTrue(UserConfigFiles.isValidCatalogName("a.json"))
    }

    @Test
    fun testRejectsPathSeparators() {
        // 关键用例：任何形式的分隔符都必须被拒
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("../evil.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("sub/dir.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("sub\\dir.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("/etc/passwd.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("..\\..\\x.json"))
    }

    @Test
    fun testRejectsTooShortNames() {
        // ".json" 本身只有 5 个字符，去掉后缀后没有任何名字，与 Foxy 的 length > 5 一致
        Assert.assertFalse(UserConfigFiles.isValidCatalogName(".json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName(""))
    }

    @Test
    fun testRejectsNonJsonExtension() {
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("symbols.txt"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("symbols.json.bak"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("symbols"))
    }

    @Test
    fun testRejectsControlCharsAndReservedChars() {
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a\nb.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a\u0000b.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a:b.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a*b.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a?b.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a\"b.json"))
        Assert.assertFalse(UserConfigFiles.isValidCatalogName("a|b.json"))
    }

    @Test
    fun testRejectsOverlongName() {
        // {1,120} 上限，避免构造超长文件名
        val long = "a".repeat(200) + ".json"
        Assert.assertFalse(UserConfigFiles.isValidCatalogName(long))
        val justRight = "a".repeat(120) + ".json"
        Assert.assertTrue(UserConfigFiles.isValidCatalogName(justRight))
    }

    @Test
    fun testBuiltinSentinelIsNotAValidFileName() {
        // 哨兵值绝不能被当成真实文件名（否则会去读一个叫 __builtin__.json 的文件）
        // 注意：它本身确实不以 .json 结尾，故必然被拒
        Assert.assertFalse(UserConfigFiles.isValidCatalogName(UserConfigFiles.SYMBOL_CATALOG_BUILTIN))
    }
}
