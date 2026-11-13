/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompactKeyboardLayoutResolverTest {
    @Test
    fun schemeCompactRowsWinOverBothOrdinaryAndPublicLayouts() {
        val json = json("""
            {
              "shuangpin": {"__compact18__": [[{"type":"ShuangpinGroupKey","groupId":"q"}]]},
              "shuangpin-xiaohe": {
                "default": [[{"type":"AlphabetKey","main":"a"}]],
                "__compact18__": [[{"type":"ShuangpinGroupKey","groupId":"we"}]]
              }
            }
        """)
        val result = compact(json)

        assertEquals("shuangpin-xiaohe:__compact18__", result.sourceKey)
        assertEquals("we", result.rows!!.firstKey("groupId"))
        assertEquals("a", resolveTextKeyboardLayout(json, "shuangpin-xiaohe", "小鹤双拼", "")!!.rows!!.firstKey("main"))
    }

    @Test
    fun schemeWithOnlyOrdinaryRowsStillInheritsPublicCompactRows() {
        val result = compact(json("""
            {
              "shuangpin": {"__compact18__": [[{"type":"ShuangpinGroupKey","groupId":"we"}]]},
              "shuangpin-xiaohe": [[{"type":"AlphabetKey","main":"a"}]]
            }
        """))

        assertEquals("shuangpin:__compact18__", result.sourceKey)
        assertEquals("shuangpin", result.layoutKey)
        assertEquals("we", result.rows!!.firstKey("groupId"))
    }

    @Test
    fun builtInCompactRowsDoNotInheritOrdinaryRowsAndKeepExistingHeights() {
        val result = compact(json("""
            {
              "default": {"__meta__":{"keyboard_height_percent":80},"default":[[{"type":"AlphabetKey","main":"g"}]]},
              "shuangpin": {"__meta__":{"keyboard_height_percent_landscape":51},"default":[[{"type":"AlphabetKey","main":"s"}]]},
              "shuangpin-xiaohe": {"__meta__":{"keyboard_height_percent":44},"default":[[{"type":"AlphabetKey","main":"a"}]]}
            }
        """))

        assertNull(result.rows)
        assertEquals("compact18-default", result.sourceKey)
        assertEquals(44, result.keyboardHeightPercentOverride(false))
        assertEquals(51, result.keyboardHeightPercentOverride(true))
    }

    @Test
    fun compactHeightSourcesResolveEachOrientationIndependently() {
        val result = compact(json("""
            {
              "shuangpin": {
                "__meta__":{"keyboard_height_percent":40,"keyboard_height_percent_landscape":45},
                "__compact18__":{"__meta__":{"keyboard_height_percent_landscape":51},"default":[[{"type":"ShuangpinGroupKey","groupId":"q"}]]}
              },
              "shuangpin-xiaohe": {
                "__meta__":{"keyboard_height_percent":44},
                "__compact18__":{"__meta__":{"keyboard_height_percent":60},"default":[[{"type":"ShuangpinGroupKey","groupId":"we"}]]}
              }
            }
        """))

        assertEquals(60, result.keyboardHeightPercentOverride(false))
        assertEquals(51, result.keyboardHeightPercentOverride(true))
        assertEquals(KeyboardHeightOverrideTarget.SubMode("shuangpin-xiaohe", "__compact18__"), result.keyboardHeightOverrideTarget(false))
        assertEquals(KeyboardHeightOverrideTarget.SubMode("shuangpin", "__compact18__"), result.keyboardHeightOverrideTarget(true))
    }

    @Test
    fun invalidOwnVariantDoesNotSilentlyActivatePublicRows() {
        val result = compact(json("""
            {
              "shuangpin":{"__compact18__":[[{"type":"ShuangpinGroupKey","groupId":"q"}]]},
              "shuangpin-xiaohe":{"__compact18__":"invalid"}
            }
        """))

        assertEquals("shuangpin-xiaohe:__compact18__", result.sourceKey)
        assertNull(result.rows)
    }

    @Test
    fun compactHeightWritePreservesOrdinaryRowsAndOtherOrientation() {
        val original = json("""
            {"shuangpin-xiaohe":{
              "default":[[{"type":"AlphabetKey","main":"a"}]],
              "__compact18__":{"__meta__":{"keyboard_height_percent_landscape":51},"default":[[{"type":"ShuangpinGroupKey","groupId":"we"}]]}
            }}
        """)
        val result = compact(original)
        val updated = requireNotNull(original.withKeyboardHeightPercentOverride(
            KeyboardHeightOverrideTarget.SubMode(result.layoutKey, requireNotNull(result.subModeKey)),
            landscape = false,
            percent = 57,
        ))

        assertEquals(57, compact(updated).keyboardHeightPercentOverride(false))
        assertEquals(51, compact(updated).keyboardHeightPercentOverride(true))
        assertEquals("a", updated["shuangpin-xiaohe"]!!.jsonObject["default"]!!.jsonArray.firstKey("main"))
        assertEquals("we", compact(updated).rows!!.firstKey("groupId"))
    }

    @Test
    fun compactVariantCannotResolveForAnUnrelatedInputMethod() {
        assertNull(resolveTextKeyboardLayout(
            json = json("""{"rime":{"__compact18__":[]}}"""),
            uniqueName = "rime", displayName = "Rime", subModeLabel = "",
            layoutVariant = ShuangpinKeyboardLayout.Compact18,
        ))
    }

    @Test
    fun previewVariantLivesInTheInstanceState() {
        fun entry(name: String) = InputMethodEntry(name, name, "", "", "双", "zh", "pinyin", false)
        val real = TextKeyboardLayoutState(entry("shuangpin-xiaohe"))
        val preview = TextKeyboardLayoutState(entry("shuangpin-ms"))
        preview.layoutVariantOverride = ShuangpinKeyboardLayout.Compact18
        preview.ime = entry("shuangpin-ziranma")

        assertNull(real.layoutVariantOverride)
        assertEquals(ShuangpinKeyboardLayout.Compact18, preview.layoutVariant())
        assertEquals("shuangpin-xiaohe", real.ime!!.uniqueName)
        preview.ime = InputMethodEntry("rime", "Rime", "", "", "㞢", "zh", "rime", false)
        assertEquals(ShuangpinKeyboardLayout.Full, preview.layoutVariant())
    }

    @Test
    fun parsedLayoutCacheDoesNotReuseAnotherVariantOrSemicolonPresentation() {
        val key = TextKeyboard.KeyDefLayoutCacheKey(
            sourceKey = "shuangpin", subModeLabel = "", subModeName = "", subModeKey = null,
            showLangSwitch = true, theme = ThemePreset.MaterialLight,
            layoutFile = "/tmp/layout.json", layoutRevision = 1,
        )

        assertNotEquals(key, key.copy(layoutVariant = ShuangpinKeyboardLayout.Compact18))
        assertNotEquals(key, key.copy(showShuangpinSemicolon = false))
    }

    private fun compact(json: JsonObject) = requireNotNull(resolveTextKeyboardLayout(
        json, "shuangpin-xiaohe", "小鹤双拼", "", layoutVariant = ShuangpinKeyboardLayout.Compact18,
    ))

    private fun json(source: String) = Json.parseToJsonElement(source.trimIndent()).jsonObject

    private fun JsonArray.firstKey(field: String): String = this[0].jsonArray[0].jsonObject[field]!!.jsonPrimitive.content
}
