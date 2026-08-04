/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextKeyboardLayoutResolverTest {

    @Test
    fun keyDefLayoutCacheKey_changesWithThemeColors() {
        val lightKey = TextKeyboard.KeyDefLayoutCacheKey(
            sourceKey = "default",
            subModeLabel = "",
            showLangSwitch = true,
            theme = ThemePreset.MaterialLight,
        )
        val darkKey = TextKeyboard.KeyDefLayoutCacheKey(
            sourceKey = "default",
            subModeLabel = "",
            showLangSwitch = true,
            theme = ThemePreset.MaterialDark,
        )

        assertNotEquals(lightKey, darkKey)
    }

    @Test
    fun metadataBearingDefaultLayoutProvidesRowsAndOrientationSpecificHeights() {
        val resolution = resolveTextKeyboardLayout(
            json = layoutJson(),
            uniqueName = "pinyin",
            displayName = "Pinyin",
            subModeLabel = "",
        )

        assertNotNull(resolution)
        assertEquals("default", resolution!!.sourceKey)
        assertTrue(resolution.rows is JsonArray)
        assertEquals(34, resolution.keyboardHeightPercentOverride(landscape = false))
        assertEquals(49, resolution.keyboardHeightPercentOverride(landscape = true))
    }

    @Test
    fun exactLayoutAndSubModeHeightOverrideDefaultLayoutHeight() {
        val resolution = resolveTextKeyboardLayout(
            json = Json.parseToJsonElement(
                """
                {
                  "default": {
                    "__meta__": {"keyboard_height_percent": 34},
                    "default": [[{"type": "AlphabetKey", "main": "q"}]]
                  },
                  "pinyin": {
                    "__meta__": {"keyboard_height_percent": 40},
                    "double_pinyin": {
                      "__meta__": {"keyboard_height_percent": 46},
                      "rows": [[{"type": "AlphabetKey", "main": "w"}]]
                    },
                    "default": [[{"type": "AlphabetKey", "main": "e"}]]
                  }
                }
                """.trimIndent()
            ) as JsonObject,
            uniqueName = "pinyin",
            displayName = "Pinyin",
            subModeLabel = "double_pinyin",
        )

        assertNotNull(resolution)
        assertEquals("pinyin", resolution!!.sourceKey)
        assertTrue(resolution.rows is JsonArray)
        assertEquals(46, resolution.keyboardHeightPercentOverride(landscape = false))
    }

    private fun layoutJson(): JsonObject {
        return Json.parseToJsonElement(
            """
            {
              "default": {
                "__meta__": {
                  "keyboard_height_percent": 34,
                  "keyboard_height_percent_landscape": 49
                },
                "default": [[{"type": "AlphabetKey", "main": "q"}]]
              }
            }
            """.trimIndent()
        ) as JsonObject
    }
}
