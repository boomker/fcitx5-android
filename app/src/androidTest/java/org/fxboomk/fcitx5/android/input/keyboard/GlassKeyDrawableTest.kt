/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassKeyDrawableTest {
    @Test
    fun glassEdgePreservesTranslucentCenterAndBackgroundInsets() {
        val color = Color.argb(96, 40, 80, 120)
        val original = insetRadiusDrawable(8, 6, 8f, color)
        val decorated = GlassKeyDrawable(original, 8, 6, 8f, 1f, dark = false)
        val image = render(decorated)

        assertEquals(96, Color.alpha(image.getPixel(40, 30)))
        assertEquals(0, Color.alpha(image.getPixel(1, 30)))
        assertEquals(Rect(8, 6, 8, 6), Rect().also { decorated.getPadding(it) })
    }

    @Test
    fun ovalEdgeKeepsCornersTransparentAndRespondsToPress() {
        val decorated = GlassKeyDrawable(
            insetOvalDrawable(10, 0, Color.argb(96, 40, 80, 120)),
            10, 0, 0f, 2f, dark = true, oval = true
        )
        val released = render(decorated)
        decorated.state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
        val pressed = render(decorated)

        assertEquals(0, Color.alpha(pressed.getPixel(11, 1)))
        assertEquals(released.getPixel(40, 30), pressed.getPixel(40, 30))
        val before = IntArray(80 * 60).also { released.getPixels(it, 0, 80, 0, 0, 80, 60) }
        val after = IntArray(80 * 60).also { pressed.getPixels(it, 0, 80, 0, 0, 80, 60) }
        assertFalse(before.contentEquals(after))
    }

    @Test
    fun glassOnlyChangesKeySurfaceAndKeepsRippleAndTextOpaque() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val originalEnabled = prefs.keyboardGlassEnabled.getValue()
            val originalOpacity = prefs.keyOpacity.getValue()
            val originalRipple = prefs.keyRippleEffect.getValue()
            try {
                prefs.keyboardGlassEnabled.setValue(true)
                prefs.keyOpacity.setValue(50)
                prefs.keyRippleEffect.setValue(true)
                val key = TextKeyView(
                    instrumentation.targetContext,
                    ThemePreset.MaterialLight,
                    KeyDef.Appearance.Text(
                        "a", 20f, border = KeyDef.Appearance.Border.On, margin = false,
                        backgroundColor = Color.rgb(40, 80, 120),
                        shadowColor = Color.BLACK,
                        textColor = Color.BLACK
                    )
                )
                val appearance = key.getChildAt(0)
                assertTrue(appearance.background is GlassKeyDrawable)
                assertEquals(127, Color.alpha(render(appearance.background).getPixel(40, 30)))
                assertEquals(Color.BLACK, key.mainText.currentTextColor)
                assertEquals(1f, key.mainText.alpha, 0f)
                assertTrue(appearance.foreground is RippleDrawable)

                key.updateTheme(ThemePreset.MaterialDark)
                assertEquals(127, Color.alpha(render(appearance.background).getPixel(40, 30)))
                assertEquals(Color.BLACK, key.mainText.currentTextColor)

                prefs.keyboardGlassEnabled.setValue(false)
                key.updateTheme(ThemePreset.MaterialLight)
                assertFalse(appearance.background is GlassKeyDrawable)
                assertEquals(127, Color.alpha(render(appearance.background).getPixel(40, 30)))

                prefs.keyOpacity.setValue(100)
                key.updateTheme(ThemePreset.MaterialLight)
                assertEquals(255, Color.alpha(render(appearance.background).getPixel(40, 30)))
            } finally {
                prefs.keyboardGlassEnabled.setValue(originalEnabled)
                prefs.keyOpacity.setValue(originalOpacity)
                prefs.keyRippleEffect.setValue(originalRipple)
            }
        }
    }

    @Test
    fun oneOpacityAppliesOnceToNormalFunctionAndAccentKeyColors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val originalEnabled = prefs.keyboardGlassEnabled.getValue()
            val originalOpacity = prefs.keyOpacity.getValue()
            try {
                prefs.keyOpacity.setValue(50)
                for (glassEnabled in listOf(false, true)) {
                    prefs.keyboardGlassEnabled.setValue(glassEnabled)
                    for (variant in listOf(
                        KeyDef.Appearance.Variant.Normal,
                        KeyDef.Appearance.Variant.Alternative,
                        KeyDef.Appearance.Variant.Accent
                    )) {
                        for (sourceAlpha in listOf(255, 128)) {
                            val key = TextKeyView(
                                instrumentation.targetContext,
                                ThemePreset.MaterialLight,
                                KeyDef.Appearance.Text(
                                    "a", 20f, variant = variant,
                                    border = KeyDef.Appearance.Border.On, margin = false,
                                    backgroundColor = Color.argb(sourceAlpha, 40, 80, 120),
                                    shadowColor = Color.BLACK
                                )
                            )
                            val actual = Color.alpha(render(key.getChildAt(0).background).getPixel(40, 30))
                            assertEquals("glass=$glassEnabled, variant=$variant", sourceAlpha / 2, actual)
                        }
                    }
                }
            } finally {
                prefs.keyboardGlassEnabled.setValue(originalEnabled)
                prefs.keyOpacity.setValue(originalOpacity)
            }
        }
    }

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, 80, 60)
            drawable.draw(Canvas(it))
        }
}
