/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.preview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinLayoutVariants
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class KeyboardPreviewManagerTest {
    private val defaultRows = listOf(listOf(mapOf<String, Any?>("main" to "a")))
    private val subModeRows = listOf(listOf(mapOf<String, Any?>("main" to "A")))

    private val sixColumnRows = listOf(
        "q", "we", "rt", "y", "u", "io", "p", "a", "sd",
        "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m"
    ).map { mapOf<String, Any?>("type" to "ShuangpinGroupKey", "groupId" to it) }.chunked(6)

    @Test
    fun compactPreviewInheritsSixColumnParentEvenWithDedicatedFullLayout() {
        val entries = mapOf(
            "default" to defaultRows,
            "shuangpin-xiaohe" to subModeRows,
            ShuangpinLayoutVariants.entryKey("shuangpin") to sixColumnRows,
        )
        val resolved = resolvePreviewRows(
            entries, "shuangpin-xiaohe", null, layoutVariant = ShuangpinKeyboardLayout.Compact18
        )
        assertSame(sixColumnRows, resolved)
        assertEquals(listOf(6, 6, 6), resolved!!.map { it.size })
        val preview = buildPreviewSubModeMap(
            entries, "shuangpin-xiaohe", null, resolved, null, ShuangpinKeyboardLayout.Compact18
        )
        assertEquals(setOf(ShuangpinLayoutVariants.CHILD_KEY), preview.keys)
        assertEquals(rowsJson(sixColumnRows), preview[ShuangpinLayoutVariants.CHILD_KEY])
        assertEquals(3, entries.size)
    }

    @Test
    fun compactPreviewNeverUsesFullRowsAndPrefersItsOwnVariant() {
        val fullEntries = mapOf("shuangpin" to defaultRows, "shuangpin-xiaohe" to subModeRows)
        assertNull(resolvePreviewRows(
            fullEntries, "shuangpin-xiaohe", null, layoutVariant = ShuangpinKeyboardLayout.Compact18
        ))
        val ownRows = sixColumnRows.reversed()
        val entries = fullEntries + mapOf(
            ShuangpinLayoutVariants.entryKey("shuangpin") to sixColumnRows,
            ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to ownRows,
        )
        assertSame(ownRows, resolvePreviewRows(
            entries, "shuangpin-xiaohe", null, layoutVariant = ShuangpinKeyboardLayout.Compact18
        ))
        assertSame(subModeRows, resolvePreviewRows(entries, "shuangpin-xiaohe", null))
    }

    @Test
    fun compactPreviewHeightUsesOwnAndParentVariantIndependently() {
        val parentKey = ShuangpinLayoutVariants.entryKey("shuangpin")
        val ownKey = ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe")
        val entries = mapOf(
            "shuangpin" to defaultRows, "shuangpin-xiaohe" to subModeRows,
            parentKey to sixColumnRows, ownKey to sixColumnRows,
        )
        val heights = mapOf(
            "shuangpin" to LayoutHeightPercentOverrides(30, 31),
            "shuangpin-xiaohe" to LayoutHeightPercentOverrides(40, 41),
            parentKey to LayoutHeightPercentOverrides(50, 51),
            ownKey to LayoutHeightPercentOverrides(portrait = 60),
        )
        assertEquals(LayoutHeightPercentOverrides(60, 51), resolvePreviewHeightOverrides(
            entries, "shuangpin-xiaohe", null, heights::get,
            layoutVariant = ShuangpinKeyboardLayout.Compact18,
        ))
    }

    @Test
    fun missingBaseUsesProvidedFactoryRowsAsPreviewDefault() {
        val entries = mapOf(LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY to defaultRows)
        // 未定制专属布局的输入法改为回退到传入的出厂 26 键布局，而非 English（default）条目。
        val resolvedRows = resolvePreviewRows(entries, "virtual-ime", null, defaultRows)

        val result = buildPreviewSubModeMap(
            entries = entries,
            layoutName = "virtual-ime",
            subModeKey = null,
            currentRows = requireNotNull(resolvedRows),
            previewSubModeLabel = null
        )

        assertSame(defaultRows, resolvedRows)
        assertEquals(setOf("default"), result.keys)
        assertEquals(rowsJson(defaultRows), result.getValue("default"))
    }

    @Test
    fun previewMacroDisplayTextUsesLabelForBaseAndSubModeRowsWithoutMutation() {
        val baseMacro = mutableMapOf<String, Any?>(
            "type" to "MacroKey",
            "label" to "B",
            "displayText" to "b",
            "tap" to mapOf("macro" to emptyList<Any>())
        )
        val subModeMacro = mutableMapOf<String, Any?>(
            "type" to "MacroKey",
            "label" to "Q",
            "displayText" to "q",
            "tap" to mapOf("macro" to emptyList<Any>())
        )
        val baseRows = listOf(listOf(baseMacro))
        val subModeRows = listOf(listOf(subModeMacro))

        val previewSubModes = buildPreviewSubModeMap(
            entries = mapOf("ime" to baseRows, "ime:child" to subModeRows),
            layoutName = "ime",
            subModeKey = "ime:child",
            currentRows = subModeRows,
            previewSubModeLabel = "child"
        )
        val basePreviewMacro = previewSubModes.getValue("default").jsonArray[0].jsonArray[0].jsonObject
        val subModePreviewMacro = previewSubModes.getValue("child").jsonArray[0].jsonArray[0].jsonObject

        assertEquals(JsonPrimitive("B"), basePreviewMacro["label"])
        assertEquals(JsonPrimitive("B"), basePreviewMacro["displayText"])
        assertEquals(JsonPrimitive("Q"), subModePreviewMacro["label"])
        assertEquals(JsonPrimitive("Q"), subModePreviewMacro["displayText"])
        assertEquals("b", baseMacro["displayText"])
        assertEquals("q", subModeMacro["displayText"])
    }

    @Test
    fun missingBaseWithoutFactoryRowsHasNoPreviewRows() {
        val entries = mapOf(LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY to defaultRows)

        // 不再回退到可被用户定制的 English（default）条目，避免布局污染。
        assertNull(resolvePreviewRows(entries, "virtual-ime", null))
    }

    @Test
    fun shuangpinSchemeFallsBackToParentBaseRows() {
        val entries = mapOf(ShuangpinLayoutKeys.BASE_LAYOUT_KEY to subModeRows)

        assertSame(
            subModeRows,
            resolvePreviewRows(entries, "shuangpin-xiaohe", null)
        )
    }

    @Test
    fun missingBaseSubModeUsesDefaultEntryAsFallback() {
        val subModeKey = "virtual-ime:uppercase"
        val entries = mapOf(
            LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY to defaultRows,
            subModeKey to subModeRows
        )

        val result = buildPreviewSubModeMap(
            entries = entries,
            layoutName = "virtual-ime",
            subModeKey = subModeKey,
            currentRows = subModeRows,
            previewSubModeLabel = "uppercase"
        )

        assertEquals(rowsJson(defaultRows), result.getValue("default"))
        assertEquals(rowsJson(subModeRows), result.getValue("uppercase"))
    }

    private fun rowsJson(rows: List<List<Map<String, Any?>>>): JsonArray =
        JsonArray(rows.map(LayoutJsonUtils::rowToJsonElement))

    @Test
    fun inheritedChildUsesBaseRowsAndOrientationSpecificHeights() {
        val entries = mapOf("default" to defaultRows, "ime" to subModeRows)
        val heights = mapOf(
            "default" to LayoutHeightPercentOverrides(80, 81),
            "ime" to LayoutHeightPercentOverrides(portrait = 42)
        )
        assertSame(subModeRows, resolvePreviewRows(entries, "ime", "ime:child"))
        assertEquals(
            LayoutHeightPercentOverrides(42, 31),
            resolvePreviewHeightOverrides(entries, "ime", "child", heights::get,
                LayoutHeightPercentOverrides(30, 31))
        )
        assertEquals(setOf("default", "ime"), entries.keys)
    }

    @Test
    fun childAndBaseHeightsFallBackIndependentlyInBothOrientations() {
        val entries = mapOf("ime" to defaultRows, "ime:child" to subModeRows)
        for (child in listOf(
            LayoutHeightPercentOverrides(portrait = 51),
            LayoutHeightPercentOverrides(landscape = 52)
        )) {
            val heights = mapOf("ime" to LayoutHeightPercentOverrides(41, 42), "ime:child" to child)
            assertEquals(
                LayoutHeightPercentOverrides(child.portrait ?: 41, child.landscape ?: 42),
                resolvePreviewHeightOverrides(entries, "ime", "child", heights::get,
                    LayoutHeightPercentOverrides(30, 31))
            )
        }
    }

    @Test
    fun explicitBaseWithoutHeightSkipsGlobalDefaultMetadata() {
        val entries = mapOf("default" to defaultRows, "ime" to subModeRows)
        val heights = mapOf("default" to LayoutHeightPercentOverrides(80, 81))
        assertEquals(
            LayoutHeightPercentOverrides(30, 31),
            resolvePreviewHeightOverrides(entries, "ime", "child", heights::get,
                LayoutHeightPercentOverrides(30, 31))
        )
        assertEquals(
            LayoutHeightPercentOverrides(),
            resolvePreviewHeightOverrides(entries, "ime", null, heights::get)
        )
    }

    @Test
    fun virtualBaseAndInheritedChildUseDefaultThenProfilePerOrientation() {
        val entries = mapOf("default" to defaultRows)
        for (default in listOf(
            LayoutHeightPercentOverrides(portrait = 41),
            LayoutHeightPercentOverrides(landscape = 42)
        )) {
            val heights = mapOf("default" to default)
            for (subMode in listOf(null, "", "child")) {
                assertEquals(
                    LayoutHeightPercentOverrides(default.portrait ?: 30, default.landscape ?: 31),
                    resolvePreviewHeightOverrides(entries, "ime", subMode, heights::get,
                        LayoutHeightPercentOverrides(30, 31))
                )
            }
        }
    }

    @Test
    fun childOnlyLayoutUsesChildThenDefaultPerOrientation() {
        val entries = mapOf("default" to defaultRows, "ime:child" to subModeRows)
        for (child in listOf(
            LayoutHeightPercentOverrides(portrait = 51),
            LayoutHeightPercentOverrides(landscape = 52)
        )) {
            val heights = mapOf("default" to LayoutHeightPercentOverrides(41, 42), "ime:child" to child)
            assertEquals(
                LayoutHeightPercentOverrides(child.portrait ?: 41, child.landscape ?: 42),
                resolvePreviewHeightOverrides(entries, "ime", "child", heights::get,
                    LayoutHeightPercentOverrides(30, 31))
            )
        }
    }

    @Test
    fun missingOrientationWithoutProfileRemainsUnsetForGlobalPreferenceFallback() {
        val entries = mapOf("default" to defaultRows, "ime:child" to subModeRows)
        val heights = mapOf("ime:child" to LayoutHeightPercentOverrides(portrait = 51))
        assertEquals(
            LayoutHeightPercentOverrides(portrait = 51),
            resolvePreviewHeightOverrides(entries, "ime", "child", heights::get)
        )
    }
}
