/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardCategory
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardCategoryButtonsTest {

    @Test
    fun clipboardCategorySelectionKeepsBackgroundInstancesAndUpdatesState() {
        instrumentation.runOnMainSync {
            val ui = ClipboardUi(targetContext, theme)
            val buttons = categoryButtons<ClipboardCategory>(ui)
            val backgrounds = buttons.mapValues { it.value.background }

            assertInitiallyUnselected(buttons.values)

            ui.setSelectedCategory(ClipboardCategory.Favorites)
            assertSelection(buttons, ClipboardCategory.Favorites)
            ui.setSelectedCategory(ClipboardCategory.Local)
            ui.setSelectedCategory(ClipboardCategory.Local)
            assertSelection(buttons, ClipboardCategory.Local)

            buttons.forEach { (category, button) ->
                assertSame("background changed for $category", backgrounds.getValue(category), button.background)
            }
        }
    }

    @Test
    fun searchCategorySelectionKeepsBackgroundInstancesAndPreservesClickCallbacks() {
        instrumentation.runOnMainSync {
            val ui = ClipboardSearchUi(targetContext, theme)
            val buttons = categoryButtons<ClipboardSearchCategory>(ui)
            val backgrounds = buttons.mapValues { it.value.background }
            val selected = mutableListOf<ClipboardSearchCategory>()

            assertInitiallyUnselected(buttons.values)
            ui.setOnCategorySelectedListener(selected::add)
            ui.setSelectedCategory(ClipboardSearchCategory.Local)

            buttons.getValue(ClipboardSearchCategory.Local).performClick()
            buttons.getValue(ClipboardSearchCategory.Local).performClick()

            assertEquals(
                listOf(ClipboardSearchCategory.Local, ClipboardSearchCategory.Local),
                selected
            )
            assertSelection(buttons, ClipboardSearchCategory.Local)
            buttons.forEach { (category, button) ->
                assertSame("background changed for $category", backgrounds.getValue(category), button.background)
            }
        }
    }

    @Test
    fun searchSurfaceColorsBecomeFrostedOnlyForImageThemes() {
        val translucentThemeColor = 0x80ff0000.toInt()
        assertEquals(
            translucentThemeColor,
            clipboardSearchSurfaceColor(theme, translucentThemeColor)
        )

        val imageTheme = theme.copy(backgroundAssetPath = "unused-test-background.png")
        val frostedColor = clipboardSearchSurfaceColor(imageTheme, translucentThemeColor)
        assertTrue(Color.alpha(frostedColor) >= 230)
        assertTrue(Color.red(frostedColor) > Color.blue(frostedColor))
    }

    private fun assertInitiallyUnselected(buttons: Collection<TextView>) {
        buttons.forEach { button ->
            assertFalse(button.isSelected)
            assertEquals(theme.keyBackgroundColor, fillColor(button))
            assertEquals(theme.keyTextColor, button.currentTextColor)
        }
    }

    private fun <T> assertSelection(buttons: Map<T, TextView>, selectedCategory: T) {
        buttons.forEach { (category, button) ->
            val selected = category == selectedCategory
            assertEquals(selected, button.isSelected)
            assertEquals(
                if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor,
                fillColor(button)
            )
            assertEquals(
                if (selected) theme.accentKeyTextColor else theme.keyTextColor,
                button.currentTextColor
            )
            if (selected) assertTrue(button.isSelected)
        }
    }

    private fun fillColor(button: TextView): Int {
        val ripple = button.background as RippleDrawable
        val fill = ripple.getDrawable(0) as GradientDrawable
        return fill.color?.defaultColor ?: error("Category fill color is missing")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> categoryButtons(ui: Any): Map<T, TextView> =
        ui.javaClass.getDeclaredField("categoryButtons").run {
            isAccessible = true
            get(ui) as Map<T, TextView>
        }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val targetContext = instrumentation.targetContext
        private val theme = ThemePreset.MaterialLight
    }
}
