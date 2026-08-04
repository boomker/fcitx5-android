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
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    fun structuredRow_backgroundColorReference_roundTrips() {
        val rowsArray = Json.parseToJsonElement(
            """
            [
              {
                "backgroundStyle": "solid",
                "backgroundColorMonet": "theme:accentKeyBackgroundColor",
                "keys": [
                  {"type": "AlphabetKey", "main": "q", "alt": "1"}
                ]
              }
            ]
            """.trimIndent()
        ).jsonArray

        val rows = LayoutJsonUtils.parseLayoutRows(rowsArray)
        val rowStyle = KeyboardRowStyleUtils.rowStyle(rows.single())
        val json = LayoutJsonUtils.convertToSaveJson(mapOf("rime" to rows))
        val rowObject = json["rime"]!!.jsonArray.single().jsonObject

        assertEquals("theme:accentKeyBackgroundColor", rowStyle.backgroundColorMonet)
        assertEquals(
            "theme:accentKeyBackgroundColor",
            rowObject["backgroundColorMonet"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun createKeyDef_preservesSolidRowBackgroundColorReference() {
        val keyDef = LayoutJsonUtils.createKeyDef(
            key = LayoutJsonUtils.KeyJson(type = "AlphabetKey", main = "q", alt = "1"),
            rowStyle = KeyboardRowStyleUtils.RowStyle(
                backgroundStyle = KeyboardRowStyleUtils.BackgroundStyle.Solid,
                backgroundColorMonet = "theme:accentKeyBackgroundColor"
            )
        )

        assertNull(keyDef.appearance.backgroundColor)
        assertEquals(
            "theme:accentKeyBackgroundColor",
            keyDef.appearance.backgroundColorMonet
        )
    }

    @Test
    fun convertToSaveJson_writesPortraitAndLandscapeHeightOverrides() {
        val rows: List<List<Map<String, Any?>>> = listOf(
            listOf(
                mapOf(
                    "type" to "AlphabetKey",
                    "main" to "q"
                )
            )
        )

        val json = LayoutJsonUtils.convertToSaveJson(
            entries = mapOf("rime" to rows),
            layoutHeightPercentOverrides = mapOf(
                "rime" to LayoutHeightPercentOverrides(portrait = 34, landscape = 49)
            )
        )
        val metadata = json["rime"]!!.jsonObject["__meta__"]!!.jsonObject

        assertEquals("34", metadata["keyboard_height_percent"]!!.jsonPrimitive.content)
        assertEquals("49", metadata["keyboard_height_percent_landscape"]!!.jsonPrimitive.content)
    }

    @Test
    fun alphabetKey_secondAltCharacter_roundTripsToAppearance() {
        val keyJson = LayoutJsonUtils.parseKeyJson(
            Json.parseToJsonElement(
                """{"type":"AlphabetKey","main":"q","alt":"1","alt1":"@"}"""
            ).jsonObject
        )!!
        val keyDef = LayoutJsonUtils.createKeyDef(
            key = keyJson,
            rowStyle = KeyboardRowStyleUtils.RowStyle(
                altTextPosition = KeyboardRowStyleUtils.AltTextPosition.TopBottom
            )
        )
        val appearance = keyDef.appearance as KeyDef.Appearance.AltText

        assertEquals("@", keyJson.alt1)
        assertEquals("@", appearance.altText1)
        assertEquals(KeyDef.Appearance.AltTextPosition.TopBottom, appearance.altTextPositionOverride)
        assertEquals("@", LayoutJsonUtils.keyDefToJson(keyDef)["alt1"])
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
