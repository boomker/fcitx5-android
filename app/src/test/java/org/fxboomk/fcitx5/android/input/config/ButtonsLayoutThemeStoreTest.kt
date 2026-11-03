/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonsLayoutThemeStoreTest {

    @Test
    fun existingUserThemeIsNotOverwrittenByBuiltinTheme() {
        val userIcon = "font:E141"
        val userConfig = ButtonsLayoutConfig.default().copy(
            kawaiiBarButtons = listOf(ConfigurableButton("voice_input", icon = userIcon))
        )
        val stored = ButtonsLayoutThemes(
            activeTheme = "Custom",
            themes = listOf(ButtonsLayoutTheme("Custom", userConfig))
        )
        val builtin = ButtonsLayoutTheme(
            "Custom",
            ButtonsLayoutConfig.default().copy(
                kawaiiBarButtons = listOf(ConfigurableButton("voice_input", icon = "svg:<svg/>"))
            )
        )

        val seeded = ButtonsLayoutThemeStore.seedBuiltinThemes(stored, listOf(builtin))

        assertEquals(userConfig, seeded.themes.single { it.name == "Custom" }.config)
    }

    @Test
    fun missingBuiltinThemeIsStillSeeded() {
        val custom = ButtonsLayoutTheme("Custom", ButtonsLayoutConfig.default())
        val builtin = ButtonsLayoutTheme("Builtin", ButtonsLayoutConfig.default())
        val stored = ButtonsLayoutThemes(activeTheme = "Custom", themes = listOf(custom))

        val seeded = ButtonsLayoutThemeStore.seedBuiltinThemes(stored, listOf(builtin))

        assertEquals(listOf("Custom", "Builtin"), seeded.themes.map { it.name })
        assertEquals("Custom", seeded.activeTheme)
    }
}
