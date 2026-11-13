/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.UppercasePosition
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class Shuangpin18LabelLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun landscapeGroupedLettersShareTheirRenderedSizeAndTypeface() {
        withBottomHintsOnMain {
            var sawHeightConstraint = false
            for (density in listOf(240, 480)) {
                val context = landscapeContext(density)
                for (font in listOf(Typeface.SANS_SERIF, Typeface.SERIF, Typeface.MONOSPACE)) {
                    for (uppercase in listOf(false, true)) {
                        for (semicolon in listOf(false, true)) {
                            val keys = Shuangpin18Keys.layout(false, semicolon).take(3).flatten()
                                .filter { definition ->
                                    definition.behaviors.filterIsInstance<KeyDef.Behavior.Press>().any {
                                        val action = it.action as? KeyAction.SymAction
                                        action != null && Shuangpin18Keys.isGroupAction(action.sym)
                                    }
                                }.map { definition ->
                                    val appearance = definition.appearance as KeyDef.Appearance.AltText
                                    AltTextKeyView(context, ThemePreset.MaterialLight, appearance).also {
                                        it.mainText.typeface = font
                                        it.mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                                        it.mainText.text = if (uppercase) appearance.displayText.uppercase()
                                            else appearance.displayText.lowercase()
                                    }
                                }
                            assertEquals(18, keys.size)
                            keys.forEachIndexed { index, key ->
                                layoutKey(key, widthDp = if (index < 7) 90 else 106, heightDp = 38)
                            }
                            AltTextKeyView.alignRemainingSpaceMainLabels(keys)
                            val case = "density=$density uppercase=$uppercase semicolon=$semicolon font=$font"
                            val placements = keys.map { requireNotNull(it.mainText.glyphPlacement) }
                            val scale = placements.first().scale
                            sawHeightConstraint = sawHeightConstraint || scale < 1f
                            assertTrue("The landscape labels must fit their short rows: $case", scale in 0f..1f)
                            keys.forEachIndexed { index, key ->
                                val label = key.mainText
                                val placement = placements[index]
                                assertEquals("Unequal letter height scale: $case ${label.text}", scale,
                                    placement.scale, 0.001f)
                                assertEquals("Unequal letter width scale: $case ${label.text}", scale,
                                    placement.horizontalScale ?: placement.scale, 0.001f)
                                assertEquals("Unequal typeface: $case ${label.text}", font, label.typeface)
                                assertFalse("A grouped label acquired synthetic bold: $case ${label.text}",
                                    label.paint.isFakeBoldText)
                                val sourceInk = Rect().also {
                                    label.paint.getTextBounds(label.text.toString(), 0, label.text.length, it)
                                }
                                assertEquals("The drawn glyph uses a different height scale: $case ${label.text}",
                                    (sourceInk.height() * scale).toDouble(),
                                    label.renderedGlyphBounds().height().toDouble(), 0.01)
                                assertTrue("No visible landscape letter: $case ${label.text}",
                                    !inkBounds(label).isEmpty)
                            }
                        }
                    }
                }
            }
            assertTrue("At least one short landscape row must constrain the glyph size", sawHeightConstraint)
        }
    }

    @Test
    fun repeatedGlyphsKeepTheirHeightAndLongCustomLabelsDoNotResizeLetters() {
        withBottomHintsOnMain {
            val context = landscapeContext(480)
            for (uppercase in listOf(false, true)) {
                val letter = if (uppercase) "Q" else "q"
                fun group(label: String) = AltTextKeyView(
                    context, ThemePreset.MaterialLight,
                    KeyDef.Appearance.AltText(label, "1", label.lowercase(), textSize = 23f,
                        isLetterGroup = true)
                ).also {
                    it.mainText.typeface = Typeface.SANS_SERIF
                    it.mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                }
                val single = group(letter)
                val paired = group(letter.repeat(2))
                val letters = listOf(single, paired)
                letters.forEach { layoutKey(it, widthDp = 100, heightDp = 38) }
                AltTextKeyView.alignRemainingSpaceMainLabels(letters)
                assertEquals("A paired label must draw the same glyph height as a single label",
                    inkBounds(single.mainText).height().toDouble(),
                    inkBounds(paired.mainText).height().toDouble(), 1.0)
                val originalScale = requireNotNull(single.mainText.glyphPlacement).scale

                val custom = AltTextKeyView(
                    context, ThemePreset.MaterialLight,
                    KeyDef.Appearance.AltText("Very long action label", "", "action", textSize = 23f)
                )
                layoutKey(custom, widthDp = 18, heightDp = 24)
                AltTextKeyView.alignRemainingSpaceMainLabels(letters + custom)
                assertEquals("A custom action label changed the shared letter scale", originalScale,
                    requireNotNull(single.mainText.glyphPlacement).scale, 0.001f)
                assertEquals("A custom action label changed the paired letter scale", originalScale,
                    requireNotNull(paired.mainText.glyphPlacement).scale, 0.001f)
            }
        }
    }

    private fun withBottomHintsOnMain(block: () -> Unit) {
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val oldPunctuation = prefs.punctuationPosition.getValue()
            val oldUppercase = prefs.uppercasePosition.getValue()
            try {
                prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
                prefs.uppercasePosition.setValue(UppercasePosition.None)
                block()
            } finally {
                prefs.punctuationPosition.setValue(oldPunctuation)
                prefs.uppercasePosition.setValue(oldUppercase)
            }
        }
    }

    private fun landscapeContext(density: Int): Context =
        instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 900
                screenHeightDp = 400
                densityDpi = density
                fontScale = 1f
            }
        )

    private fun layoutKey(key: AltTextKeyView, widthDp: Int, heightDp: Int) {
        val density = key.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        repeat(3) {
            key.refreshLayout()
            key.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            key.layout(0, 0, width, height)
        }
    }

    private fun inkBounds(view: AutoScaleTextView): Rect {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val originalColor = view.currentTextColor
        try {
            view.setTextColor(Color.BLACK)
            view.draw(Canvas(bitmap))
            val bounds = Rect(view.width, view.height, 0, 0)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (Color.alpha(bitmap.getPixel(x, y)) >= 128) {
                        bounds.left = minOf(bounds.left, x)
                        bounds.top = minOf(bounds.top, y)
                        bounds.right = maxOf(bounds.right, x + 1)
                        bounds.bottom = maxOf(bounds.bottom, y + 1)
                    }
                }
            }
            return bounds
        } finally {
            view.setTextColor(originalColor)
            bitmap.recycle()
        }
    }
}
