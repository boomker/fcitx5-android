/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fxboomk.fcitx5.android.input.config.KeyIconIds
import org.fxboomk.fcitx5.android.input.config.KeyIconTheme
import org.fxboomk.fcitx5.android.input.config.KeyIconThemesConfig
import org.fxboomk.fcitx5.android.input.config.SvgIconDrawable
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.font.ButtonIconFont
import org.fxboomk.fcitx5.android.input.font.TextIconDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandedCandidateReturnIconTest {

    @Test
    fun composingReturnKeepsSvgThemeAfterAttachAndReopen() = withIconConfig {
        val svg = """<svg viewBox="0 0 24 24"><path d="M2 2h7v20H2zM12 4h10v5H12z"/></svg>"""
        saveIcons(mapOf(KeyIconIds.RETURN to "svg:$svg"))
        for (gboardStyle in listOf(false, true)) {
            ThemeManager.prefs.gboardStyleSideKeys.setValue(gboardStyle)
            val keyboard = ExpandedCandidateLayout.Keyboard(context, theme)
            repeat(2) {
                keyboard.onAttach()
                // The expanded window sends this update immediately after attaching.
                keyboard.onReturnDrawableUpdate(R.drawable.ic_baseline_keyboard_return_24)
                assertIcon(keyboard, requireNotNull(SvgIconDrawable.parse(svg, density)))
                keyboard.onDetach()
            }
        }
    }

    @Test
    fun actionReturnUsesItsThemedGlyphAndSwitchesBackToComposingIcon() = withIconConfig {
        val icons = returnActions.mapIndexed { index, (id, _) ->
            id to "font:${(0xe600 + index).toString(16)}"
        }.toMap()
        saveIcons(icons)
        val keyboard = ExpandedCandidateLayout.Keyboard(context, theme)
        keyboard.onAttach()
        for ((index, action) in returnActions.withIndex()) {
            keyboard.onReturnDrawableUpdate(action.second)
            assertTrue(keyboard.`return`!!.img.drawable is TextIconDrawable)
            assertIcon(keyboard, glyph(0xe600 + index))
        }
        keyboard.onReturnDrawableUpdate(R.drawable.ic_baseline_keyboard_return_24)
        assertTrue(keyboard.`return`!!.img.drawable is TextIconDrawable)
        assertIcon(keyboard, glyph(0xe600))
        keyboard.onDetach()
    }

    @Test
    fun returnUsesDrawableResourceFromIconTheme() = withIconConfig {
        saveIcons(mapOf(KeyIconIds.RETURN to "@drawable/ic_baseline_search_24"))
        val keyboard = ExpandedCandidateLayout.Keyboard(context, theme)
        keyboard.onAttach()
        keyboard.onReturnDrawableUpdate(R.drawable.ic_baseline_keyboard_return_24)
        assertIcon(keyboard, resource(R.drawable.ic_baseline_search_24))
        keyboard.onDetach()
    }

    @Test
    fun unconfiguredReturnActionsKeepDefaultAndGboardFallbacks() = withIconConfig {
        saveIcons(emptyMap())
        for (gboardStyle in listOf(false, true)) {
            ThemeManager.prefs.gboardStyleSideKeys.setValue(gboardStyle)
            val keyboard = ExpandedCandidateLayout.Keyboard(context, theme)
            keyboard.onAttach()
            for ((id, drawable) in returnActions) {
                keyboard.onReturnDrawableUpdate(drawable)
                val expected = if (gboardStyle && id == KeyIconIds.RETURN) {
                    R.drawable.ic_rounded_keyboard_return_24
                } else drawable
                assertIcon(keyboard, resource(expected))
            }
            keyboard.onDetach()
        }
    }

    private fun saveIcons(icons: Map<String, String>) {
        val file = requireNotNull(UserConfigFiles.keyIconThemesConfig())
        file.parentFile?.mkdirs()
        file.writeText(Json.encodeToString(KeyIconThemesConfig(
            activeTheme = "Expanded return test",
            themes = listOf(KeyIconTheme("Expanded return test", icons)),
        )))
    }

    private fun assertIcon(keyboard: ExpandedCandidateLayout.Keyboard, expected: Drawable) {
        val image = requireNotNull(keyboard.`return`).img
        assertEquals(theme.accentKeyTextColor, image.imageTintList?.defaultColor)
        expected.setTint(theme.accentKeyTextColor)
        val actualBitmap = render(requireNotNull(image.drawable))
        val expectedBitmap = render(expected)
        try {
            assertTrue("Expanded Enter icon must match the selected theme/action", actualBitmap.sameAs(expectedBitmap))
        } finally {
            actualBitmap.recycle()
            expectedBitmap.recycle()
        }
    }

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, it.width, it.height)
            drawable.draw(Canvas(it))
        }

    private fun glyph(codePoint: Int) = TextIconDrawable(
        String(Character.toChars(codePoint)), ButtonIconFont.typeface(context), density,
    )

    private fun resource(id: Int) = requireNotNull(AppCompatResources.getDrawable(context, id))

    private fun withIconConfig(block: () -> Unit) = instrumentation.runOnMainSync {
        val file = requireNotNull(UserConfigFiles.keyIconThemesConfig())
        val originalBytes = file.takeIf { it.exists() }?.readBytes()
        val style = ThemeManager.prefs.gboardStyleSideKeys
        val wasStored = style.sharedPreferences.contains(style.key)
        val originalStyle = style.getValue()
        try {
            style.setValue(false)
            block()
        } finally {
            if (originalBytes != null) file.writeBytes(originalBytes) else file.delete()
            if (wasStored) style.setValue(originalStyle)
            else style.sharedPreferences.edit { remove(style.key) }
        }
    }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val theme = ThemePreset.MaterialLight
        private val density = context.resources.displayMetrics.density
        private val returnActions = listOf(
            KeyIconIds.RETURN to R.drawable.ic_baseline_keyboard_return_24,
            KeyIconIds.RETURN_GO to R.drawable.ic_baseline_arrow_forward_24,
            KeyIconIds.RETURN_SEARCH to R.drawable.ic_baseline_search_24,
            KeyIconIds.RETURN_SEND to R.drawable.ic_baseline_send_24,
            KeyIconIds.RETURN_NEXT to R.drawable.ic_baseline_keyboard_tab_24,
            KeyIconIds.RETURN_PREVIOUS to R.drawable.ic_baseline_keyboard_tab_reverse_24,
            KeyIconIds.RETURN_DONE to R.drawable.ic_baseline_done_24,
        )
    }
}
