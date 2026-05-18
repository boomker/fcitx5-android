/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutJsonUtilsRowStyleTest {

    @Test
    fun parseLayoutRows_readsStructuredRowMetaBeforeKeys() {
        val rowsArray = Json.parseToJsonElement(
            """
            [
              {
                "heightMultiplier": 1.4,
                "altTextPosition": "top",
                "backgroundStyle": "gradient",
                "backgroundColor": -12298906,
                "keys": [
                  {"type": "AlphabetKey", "main": "q", "alt": "1"}
                ]
              }
            ]
            """.trimIndent()
        ).jsonArray

        val rows = LayoutJsonUtils.parseLayoutRows(rowsArray)

        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
        assertTrue(KeyboardRowStyleUtils.isRowMeta(rows[0].first()))
        assertEquals("AlphabetKey", rows[0][1]["type"])
    }

    @Test
    fun convertToSaveJson_writesStructuredRowWhenMetaExists() {
        val rowStyle = KeyboardRowStyleUtils.RowStyle(
            heightMultiplier = 1.25f,
            altTextPosition = KeyboardRowStyleUtils.AltTextPosition.Bottom,
            backgroundStyle = KeyboardRowStyleUtils.BackgroundStyle.Solid,
            backgroundColor = 0xFF224466.toInt()
        )
        val row = mutableListOf(
            KeyboardRowStyleUtils.buildMeta(rowStyle),
            mutableMapOf<String, Any?>(
                "type" to "AlphabetKey",
                "main" to "q",
                "alt" to "1"
            )
        )

        val json = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to listOf(row)))
        val rowObject = json["rime"]!!.jsonArray[0].jsonObject

        assertEquals("bottom", rowObject["altTextPosition"]!!.jsonPrimitive.content)
        assertEquals("solid", rowObject["backgroundStyle"]!!.jsonPrimitive.content)
        assertEquals(1, rowObject["keys"]!!.jsonArray.size)
    }

    @Test
    fun createKeyDef_appliesRowStyleToAppearance() {
        val rowStyle = KeyboardRowStyleUtils.RowStyle(
            heightMultiplier = 1.6f,
            altTextPosition = KeyboardRowStyleUtils.AltTextPosition.Top,
            backgroundStyle = KeyboardRowStyleUtils.BackgroundStyle.Gradient,
            backgroundColor = 0xFF667788.toInt()
        )

        val first = LayoutJsonUtils.createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "q", alt = "1"),
            rowStyle = rowStyle,
            visibleIndex = 0,
            visibleCount = 3
        )
        val middle = LayoutJsonUtils.createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "w", alt = "2"),
            rowStyle = rowStyle,
            visibleIndex = 1,
            visibleCount = 3
        )
        val last = LayoutJsonUtils.createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "e", alt = "3"),
            rowStyle = rowStyle,
            visibleIndex = 2,
            visibleCount = 3
        )

        assertEquals(1.6f, first.appearance.rowHeightMultiplier, 0.001f)
        assertEquals(KeyDef.Appearance.AltTextPosition.Top, first.appearance.altTextPositionOverride)
        assertNotEquals(first.appearance.backgroundColor, middle.appearance.backgroundColor)
        assertNotEquals(middle.appearance.backgroundColor, last.appearance.backgroundColor)
    }
}
