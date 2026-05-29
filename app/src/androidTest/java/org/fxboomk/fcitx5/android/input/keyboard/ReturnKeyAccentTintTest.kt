/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
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

    @Test
    fun returnKeyRippleMaskMatchesRoundedBackgroundInsets() {
        val originalGboardStyleSideKeys = ThemeManager.prefs.gboardStyleSideKeys.getValue()
        val originalKeyRippleEffect = ThemeManager.prefs.keyRippleEffect.getValue()
        val originalKeyHorizontalMargin = ThemeManager.prefs.keyHorizontalMargin.getValue()
        val originalKeyVerticalMargin = ThemeManager.prefs.keyVerticalMargin.getValue()
        val originalKeyHorizontalMarginLandscape =
            ThemeManager.prefs.keyHorizontalMarginLandscape.getValue()
        val originalKeyVerticalMarginLandscape =
            ThemeManager.prefs.keyVerticalMarginLandscape.getValue()

        try {
            ThemeManager.prefs.gboardStyleSideKeys.setValue(false)
            ThemeManager.prefs.keyRippleEffect.setValue(true)
            ThemeManager.prefs.keyHorizontalMargin.setValue(2)
            ThemeManager.prefs.keyVerticalMargin.setValue(3)
            ThemeManager.prefs.keyHorizontalMarginLandscape.setValue(2)
            ThemeManager.prefs.keyVerticalMarginLandscape.setValue(3)

            lateinit var backgroundInsets: Rect
            lateinit var maskInsets: Rect
            instrumentation.runOnMainSync {
                val keyboard = NumberKeyboard(targetContext, ThemePreset.MaterialLight)
                keyboard.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
                )
                keyboard.layout(0, 0, 1080, 480)

                val returnKey = keyboard.`return` ?: error("Return key not found")
                val appearanceView = returnKey.getChildAt(0)
                val background = appearanceView.background as LayerDrawable
                val ripple = appearanceView.foreground as RippleDrawable
                val mask = ripple.findDrawableByLayerId(android.R.id.mask)
                    ?: error("Return key ripple mask not found")

                backgroundInsets = Rect(
                    background.getLayerInsetLeft(1),
                    background.getLayerInsetTop(1),
                    background.getLayerInsetRight(1),
                    background.getLayerInsetBottom(1)
                )
                maskInsets = Rect().also { mask.getPadding(it) }
            }

            assertEquals(backgroundInsets, maskInsets)
        } finally {
            ThemeManager.prefs.gboardStyleSideKeys.setValue(originalGboardStyleSideKeys)
            ThemeManager.prefs.keyRippleEffect.setValue(originalKeyRippleEffect)
            ThemeManager.prefs.keyHorizontalMargin.setValue(originalKeyHorizontalMargin)
            ThemeManager.prefs.keyVerticalMargin.setValue(originalKeyVerticalMargin)
            ThemeManager.prefs.keyHorizontalMarginLandscape.setValue(originalKeyHorizontalMarginLandscape)
            ThemeManager.prefs.keyVerticalMarginLandscape.setValue(originalKeyVerticalMarginLandscape)
        }
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
