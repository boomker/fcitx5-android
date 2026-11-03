/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeTransferTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun toolbarThemeRoundTripsWithItsFormat() {
        val theme = ButtonsLayoutTheme("Compact", ButtonsLayoutConfig.default())
        val decoded = ThemeTransferCodec.decodeToolbar(ThemeTransferCodec.encodeToolbar(theme))
        assertEquals(theme, decoded)
    }

    @Test
    fun keyIconThemeRoundTripsWithItsFormat() {
        val theme = KeyIconTheme("High contrast", mapOf(KeyIconIds.BACKSPACE to "ic_custom_backspace"))
        val decoded = ThemeTransferCodec.decodeKeyIcons(ThemeTransferCodec.encodeKeyIcons(theme))
        assertEquals(theme, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun importingTheOtherThemeTypeIsRejected() {
        ThemeTransferCodec.decodeKeyIcons(
            ThemeTransferCodec.encodeToolbar(
                ButtonsLayoutTheme("Toolbar", ButtonsLayoutConfig.default())
            )
        )
    }

    @Test
    fun oldButtonConfigWithoutActiveIconStillDeserializes() {
        val decoded = json.decodeFromString<ButtonsLayoutConfig>(
            """{"kawaiiBarButtons":[{"id":"voice_input"}],"statusAreaButtons":[]}"""
        )
        assertNull(decoded.kawaiiBarButtons.single().activeIcon)
    }

    @Test
    fun toolbarThemePreservesVoiceActiveIcon() {
        val button = ConfigurableButton(
            id = "voice_input",
            icon = "font:E141",
            activeIcon = "font:E142"
        )
        val theme = ButtonsLayoutTheme(
            "Voice",
            ButtonsLayoutConfig.default().copy(kawaiiBarButtons = listOf(button))
        )

        val decoded = ThemeTransferCodec.decodeToolbar(ThemeTransferCodec.encodeToolbar(theme))

        assertEquals(button.activeIcon, decoded.config.kawaiiBarButtons.single().activeIcon)
    }

    @Test
    fun iconFontCodePointAcceptsCommonUnicodeReferences() {
        assertEquals(0xE141, ButtonIconSpec.codePoint("font:E141"))
        assertEquals(0xE141, ButtonIconSpec.codePoint("U+E141"))
        assertEquals(0xE141, ButtonIconSpec.codePoint("0xE141"))
        assertEquals(0xE141, ButtonIconSpec.codePoint("&#xE141;"))
        assertEquals("\uE141", ButtonIconSpec.glyph("U+E141"))
    }
}
