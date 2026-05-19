/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReturnKeyAccentTintTest {

    @Test
    fun returnKeyUsesAccentKeyTextColorForInitialTint() {
        val returnKey = createKeyboard(theme = ThemePreset.MaterialLight).`return`
            ?: error("Return key not found")

        assertEquals(
            ThemePreset.MaterialLight.accentKeyTextColor,
            returnKey.img.imageTintList?.defaultColor
        )
    }

    @Test
    fun returnKeyUsesAccentKeyTextColorAfterThemeUpdate() {
        val keyboard = createKeyboard(theme = ThemePreset.MaterialLight)
        val returnKey = keyboard.`return` ?: error("Return key not found")

        instrumentation.runOnMainSync {
            returnKey.updateTheme(ThemePreset.MaterialDark)
        }

        assertEquals(
            ThemePreset.MaterialDark.accentKeyTextColor,
            returnKey.img.imageTintList?.defaultColor
        )
    }

    private fun createKeyboard(theme: org.fxboomk.fcitx5.android.data.theme.Theme.Builtin): NumberKeyboard {
        val originalGboardStyleSideKeys = ThemeManager.prefs.gboardStyleSideKeys.getValue()
        try {
            ThemeManager.prefs.gboardStyleSideKeys.setValue(false)
            lateinit var keyboard: NumberKeyboard
            instrumentation.runOnMainSync {
                keyboard = NumberKeyboard(targetContext, theme)
            }
            return keyboard
        } finally {
            ThemeManager.prefs.gboardStyleSideKeys.setValue(originalGboardStyleSideKeys)
        }
    }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val targetContext = instrumentation.targetContext
    }
}
