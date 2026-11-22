/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.UppercasePosition
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class VerticalSubLabelLayoutTest {

    @Test
    fun bothOrdersStayAtEdgesWithSeparateMainLabel() = withPreferences {
        for (punctuationOnTop in listOf(true, false)) {
            ThemeManager.prefs.punctuationPosition.setValue(
                if (punctuationOnTop) PunctuationPosition.Top else PunctuationPosition.Bottom
            )
            ThemeManager.prefs.uppercasePosition.setValue(
                if (punctuationOnTop) UppercasePosition.Bottom else UppercasePosition.Top
            )
            for (punctuation in listOf(",", "！", "…")) {
                val key = createKey(punctuation)
                layoutKey(key, heightDp = 52)
                assertSeparatedAtEdges(key, punctuationOnTop)
            }
        }
    }

    @Test
    fun shortRowsAndLargeFontsKeepAllThreeLabelsApartAfterResize() = withPreferences {
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Top)
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Bottom)
        val key = createKey("?")
        key.setTextScale(2.4f)
        for (height in listOf(60, 32, 48)) {
            layoutKey(key, heightDp = height)
            if (key.altText.visibility == View.VISIBLE && key.altText1.visibility == View.VISIBLE) {
                assertSeparatedAtEdges(key, punctuationOnTop = true)
            }
        }
    }

    @Test
    fun changingToCornerOrSingleLabelRestoresNormalMainLabelLayout() = withPreferences {
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Top)
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Bottom)
        val key = createKey("?")
        layoutKey(key, heightDp = 52)
        assertSeparatedAtEdges(key, punctuationOnTop = true)

        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Top)
        layoutKey(key, heightDp = 52)
        assertTrue(key.mainText.useGlyphBounds)
        assertEquals(0, (key.mainText.layoutParams as ConstraintLayout.LayoutParams).height)
        assertEquals(View.VISIBLE, key.altText1.visibility)

        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.None)
        layoutKey(key, heightDp = 52)
        assertTrue(key.mainText.useGlyphBounds)
        assertEquals(0, (key.mainText.layoutParams as ConstraintLayout.LayoutParams).height)
        assertEquals(View.GONE, key.altText1.visibility)
        // Since d1f167f60 the visible main glyph is centred on the key face (clamped and scaled
        // to keep its clearance), not balanced in the space left below the label.
        val mainInk = inkBounds(key.mainText)
        assertEquals(
            "Visible main glyph must stay on the key-face centre",
            key.getChildAt(0).height / 2f,
            (mainInk.top + mainInk.bottom) / 2f,
            2f
        )
        assertTrue("Main glyph must clear the top label", mainInk.top - inkBounds(key.altText).bottom >= 1)

        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
        layoutKey(key, heightDp = 52)
        assertNormalMainLabelLayout(key)
    }

    private fun assertSeparatedAtEdges(key: AltTextKeyView, punctuationOnTop: Boolean) {
        val topLabel = if (punctuationOnTop) key.altText else key.altText1
        val bottomLabel = if (punctuationOnTop) key.altText1 else key.altText
        assertEquals(View.VISIBLE, topLabel.visibility)
        assertEquals(View.VISIBLE, bottomLabel.visibility)
        val topInk = inkBounds(topLabel)
        val mainInk = inkBounds(key.mainText)
        val bottomInk = inkBounds(bottomLabel)
        val appearanceHeight = key.getChildAt(0).height
        val edgeInset = key.vMargin

        assertEquals(edgeInset.toFloat(), topInk.top.toFloat(), 1f)
        assertEquals((appearanceHeight - edgeInset).toFloat(), bottomInk.bottom.toFloat(), 1f)
        // 本测试的行高在 2.4x 字号下全部等效短行（60/32/48dp → ~25/13/20dp），
        // 只要求三个标签互不贴住（≥1px 墨迹间隙）；主标签居中于键面由下方断言约束
        assertTrue("Top label must leave room for main text: $mainInk/$topInk", mainInk.top - topInk.bottom >= 1)
        assertTrue("Bottom label must leave room for main text: $mainInk/$bottomInk", bottomInk.top - mainInk.bottom >= 1)
        // Since d1f167f60 the main glyph is centred on the key face rather than between the two
        // sublabels, so the gaps differ whenever the top and bottom labels have different ink heights.
        assertEquals(
            "Visible main glyph must stay on the key-face centre",
            appearanceHeight / 2f,
            (mainInk.top + mainInk.bottom) / 2f,
            2f
        )
    }

    private fun assertNormalMainLabelLayout(key: AltTextKeyView) {
        assertFalse(key.altText.useGlyphBounds)
        assertFalse(key.altText1.useGlyphBounds)
        assertEquals(Int.MAX_VALUE, key.altText.maxHeight)
        val params = key.mainText.layoutParams as ConstraintLayout.LayoutParams
        assertEquals(ConstraintLayout.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(ConstraintLayout.LayoutParams.UNSET, params.topToBottom)
        assertEquals(0, params.bottomMargin)
    }

    /** Inspect rendered pixels, so font leading cannot masquerade as edge alignment. */
    private fun inkBounds(view: AutoScaleTextView): Rect {
        assertTrue("Label needs a nonempty drawing region", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.setTextColor(Color.BLACK)
        view.draw(Canvas(bitmap))
        val bounds = Rect(view.width, view.height, 0, 0)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    bounds.left = minOf(bounds.left, x)
                    bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x + 1)
                    bounds.bottom = maxOf(bounds.bottom, y + 1)
                }
            }
        }
        bitmap.recycle()
        assertFalse("Label must remain visible", bounds.isEmpty)
        bounds.offset(view.left, view.top)
        return bounds
    }

    private fun createKey(punctuation: String) = AltTextKeyView(
        targetContext,
        ThemePreset.MaterialLight,
        KeyDef.Appearance.AltText(
            displayText = "g",
            character = "g",
            altText = punctuation,
            supportsUppercaseHint = true,
            textSize = 24f,
        ),
    )

    private fun layoutKey(key: AltTextKeyView, heightDp: Int) {
        repeat(2) {
            key.refreshLayout()
            key.measure(
                View.MeasureSpec.makeMeasureSpec(dp(48), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY),
            )
            key.layout(0, 0, dp(48), dp(heightDp))
        }
    }

    private fun withPreferences(block: () -> Unit) {
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val punctuation = prefs.punctuationPosition.getValue()
            val uppercase = prefs.uppercasePosition.getValue()
            val margin = prefs.keyVerticalMargin.getValue()
            val landscapeMargin = prefs.keyVerticalMarginLandscape.getValue()
            try {
                prefs.keyVerticalMargin.setValue(2)
                prefs.keyVerticalMarginLandscape.setValue(2)
                block()
            } finally {
                prefs.punctuationPosition.setValue(punctuation)
                prefs.uppercasePosition.setValue(uppercase)
                prefs.keyVerticalMargin.setValue(margin)
                prefs.keyVerticalMarginLandscape.setValue(landscapeMargin)
            }
        }
    }

    private fun dp(value: Int) = (value * targetContext.resources.displayMetrics.density).roundToInt()

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val targetContext = instrumentation.targetContext
    }
}
