/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android

import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.data.theme.CustomThemeSerializer
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert
import org.junit.Test

class ThemeSerializationTest {

    private fun Theme.Custom.toJson() = Json.encodeToString(CustomThemeSerializer, this)
    private fun String.toCustomTheme() =
        Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    @Test
    fun preservation() {
        val fakeCustomTheme =
            ThemePreset
                .TransparentDark
                .deriveCustomBackground("", "", "")

        val (decoded, migrated) = fakeCustomTheme.toJson().toCustomTheme()

        Assert.assertEquals("Migration shouldn't happen", false, migrated)
        Assert.assertEquals("Versioning preserves the original structure", fakeCustomTheme, decoded)
    }

    @Test
    fun version1() {
        // Version 1.0, outdated
        val raw = """
            {
                "name": "",
                "backgroundImage": {
                    "croppedFilePath": "",
                    "srcFilePath": "",
                    "cropRect": null
                },
                "backgroundColor": -13816531,
                "barColor": 1275068416,
                "keyboardColor": 0,
                "keyBackgroundColor": 1275068415,
                "keyTextColor": -1,
                "altKeyBackgroundColor": 218103807,
                "altKeyTextColor": -905969665,
                "accentKeyBackgroundColor": -10577930,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 520093696,
                "keyShadowColor": 0,
                "spaceBarColor": 1275068415,
                "dividerColor": 536870911,
                "clipboardEntryColor": 855638015,
                "isDark": true,
                "version": "1.0"
            }
        """.trimIndent()
        val (decoded, migrated) = raw.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version2() {
        // Version 2.0, outdated after candidate color fields were added in 2.1
        val raw = """
            {
               "name":"",
               "backgroundImage":{
                  "croppedFilePath":"",
                  "srcFilePath":"",
                  "cropRect": null
               },
               "backgroundColor":-13816531,
               "barColor":1275068416,
               "keyboardColor":0,
               "keyBackgroundColor":1275068415,
               "keyTextColor":-1,
               "altKeyBackgroundColor":218103807,
               "altKeyTextColor":-905969665,
               "accentKeyBackgroundColor":-10577930,
               "accentKeyTextColor":-1,
               "keyPressHighlightColor":520093696,
               "keyShadowColor":0,
               "popupBackgroundColor":-13158601,
               "popupTextColor":-1,
               "spaceBarColor":1275068415,
               "dividerColor":536870911,
               "clipboardEntryColor":855638015,
               "genericActiveBackgroundColor":-10577930,
               "genericActiveForegroundColor":-1,
               "isDark":true,
               "version":"2.0"
            }
        """.trimIndent()
        val (decoded, migrated) = raw.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun foreignForkFields() {
        // A fork on top of the 2.1 schema added waterRippleColor; the unknown field
        // must be dropped instead of rejecting the whole theme
        val raw = """
            {"name":"渐变·天蓝","isDark":false,"backgroundImage":null,"backgroundColor":-1707268,"barColor":-1118482,"keyboardColor":-328966,"keyBackgroundColor":-1711276033,"keyTextColor":-14606047,"candidateTextColor":-14606047,"candidateLabelColor":-14606047,"candidateCommentColor":-9539986,"altKeyBackgroundColor":1442840575,"altKeyTextColor":-16419381,"accentKeyBackgroundColor":-8471305,"accentKeyTextColor":-1,"keyPressHighlightColor":520451531,"keyShadowColor":-8471305,"popupBackgroundColor":-1707268,"popupTextColor":-14606047,"spaceBarColor":-2368549,"dividerColor":520093696,"clipboardEntryColor":-1,"genericActiveBackgroundColor":-699051,"genericActiveForegroundColor":-1,"waterRippleColor":-4335116,"version":"2.1"}
        """.trimIndent()

        val (decoded, migrated) = raw.toCustomTheme()

        Assert.assertEquals("Same schema version, no migration", false, migrated)
        Assert.assertEquals("渐变·天蓝", decoded.name)
        Assert.assertEquals(null, decoded.backgroundImage)
        Assert.assertFalse("Unknown field must not survive", decoded.toJson().contains("waterRipple"))
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun foreignForkFieldsInsideBackgroundImage() {
        val raw = """
            {
                "name": "fork-bg",
                "isDark": true,
                "backgroundImage": {
                    "croppedFilePath": "cropped.png",
                    "srcFilePath": "src.png",
                    "cropRect": null,
                    "vignette": 0.5
                },
                "backgroundColor": -13816531,
                "barColor": 1275068416,
                "keyboardColor": 0,
                "keyBackgroundColor": 1275068415,
                "keyTextColor": -1,
                "candidateTextColor": -1,
                "candidateLabelColor": -1,
                "candidateCommentColor": -1,
                "altKeyBackgroundColor": 218103807,
                "altKeyTextColor": -905969665,
                "accentKeyBackgroundColor": -10577930,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 520093696,
                "keyShadowColor": 0,
                "popupBackgroundColor": -13158601,
                "popupTextColor": -1,
                "spaceBarColor": 1275068415,
                "dividerColor": 536870911,
                "clipboardEntryColor": 855638015,
                "genericActiveBackgroundColor": -10577930,
                "genericActiveForegroundColor": -1,
                "version": "2.1"
            }
        """.trimIndent()

        val (decoded, migrated) = raw.toCustomTheme()

        Assert.assertEquals(false, migrated)
        Assert.assertNotNull(decoded.backgroundImage)
        Assert.assertFalse("Unknown nested field must not survive", decoded.toJson().contains("vignette"))
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }
}
