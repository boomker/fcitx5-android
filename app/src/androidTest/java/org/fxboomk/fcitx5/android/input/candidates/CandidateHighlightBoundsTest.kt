/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp

class CandidateHighlightBoundsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val theme = ThemePreset.MaterialLight

    @Test
    fun activeAndPressedHighlightsKeepEqualGapsWhenTheToolbarGrows() = withSquareHighlights {
        val adapter = HorizontalCandidateViewAdapter(theme)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val word = CandidateWord("", "候选词", "", false)
        var expectedHeight = 0
        for (height in listOf(64, 112, 40)) {
            adapter.updateCandidates(arrayOf(word), 1, 0)
            adapter.onBindViewHolder(holder, 0)
            layout(holder.ui, height)
            val active = paintedHighlight(holder.ui)
            assertFalse("A body-sized highlight must not mark the whole candidate as opaque",
                holder.ui.root.isOpaque)
            assertEqualGaps(holder.ui, active)
            if (height == 64) expectedHeight = active.height()
            if (height == 112) assertEquals("Highlight height must depend on the word, not the toolbar",
                expectedHeight, active.height())

            holder.ui.setActive(false)
            holder.ui.root.isPressed = true
            val pressed = paintedHighlight(holder.ui)
            assertEquals("Pressed and selected highlights must use the same geometry", active, pressed)
            assertEqualGaps(holder.ui, pressed)
            holder.ui.root.isPressed = false
            assertTrue("An unselected candidate must not retain its background",
                paintedHighlight(holder.ui).isEmpty)
        }
    }

    @Test
    fun topPinyinStaysOutsideTheHighlightWhenTheCandidateIsReused() = withSquareHighlights {
        val adapter = HorizontalCandidateViewAdapter(theme)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        for (top in listOf(true, false, true)) {
            val word = CandidateWord("", "你", if (top) "nǐ nǐ nǐ" else "(nǐ)", false,
                isPinyinAnnotationOnTop = top)
            adapter.updateCandidates(arrayOf(word), 1, 0)
            adapter.onBindViewHolder(holder, 0)
            layout(holder.ui, 112)
            val highlight = paintedHighlight(holder.ui)
            assertEqualGaps(holder.ui, highlight)
            if (top) {
                val pinyin = glyphBounds(holder.ui, holder.ui.pinyinText)
                assertFalse("Top pinyin must remain outside the highlighted body",
                    RectF.intersects(RectF(highlight), pinyin))
                assertEquals("Top pinyin must use the candidate surface color",
                    theme.candidateCommentColor, holder.ui.pinyinText.currentTextColor)
            }
        }
    }

    @Test
    fun firstCandidateFillBorderAndPressOverlayUseTheSameBodyBounds() = withSquareHighlights {
        val adapter = HorizontalCandidateViewAdapter(theme)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val ui = holder.ui
        ui.updateCandidate(CandidateWord("", "候选词", "hòu xuǎn cí", false,
            isPinyinAnnotationOnTop = true))
        ui.applyFirstCandidateStyle(theme.genericActiveBackgroundColor, theme.dividerColor, theme.keyPressHighlightColor)
        layout(ui, 112)
        val normal = paintedHighlight(ui)
        assertEqualGaps(ui, normal)
        ui.root.isPressed = true
        assertEquals(normal, paintedHighlight(ui))
        ui.root.isPressed = false
        ui.resetToDefaultBackground(theme.keyPressHighlightColor)
        assertTrue(paintedHighlight(ui).isEmpty)
    }

    @Test
    fun rightAnnotationInkUpdatesAfterChangingTheTextAndFontScale() = withSquareHighlights {
        val adapter = HorizontalCandidateViewAdapter(theme)
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        for ((word, scale) in listOf("你" to 1f, "候选" to 1.4f, "我" to 0.8f)) {
            holder.ui.updateCandidate(CandidateWord("", word, "(pīn yīn)", false))
            holder.ui.setFontScale(scale)
            holder.ui.setActive(true)
            layout(holder.ui, 112)
            assertEqualGaps(holder.ui, paintedHighlight(holder.ui))
        }
    }

    @Test
    fun annotationsAndSelectionNumbersKeepTheirSurfaceColorsInBothThemes() = withSquareHighlights {
        for (palette in listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark)) {
            val adapter = HorizontalCandidateViewAdapter(palette)
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.updateCandidates(arrayOf(CandidateWord("", "你", "(nǐ)", false,
                isPinyinAnnotationOnTop = true)), 1, 0)
            adapter.onBindViewHolder(holder, 0)
            holder.ui.setIndexLabel(1)
            layout(holder.ui, 112)
            assertEqualGaps(holder.ui, paintedHighlight(holder.ui))
            assertEquals(palette.candidateCommentColor, holder.ui.pinyinText.currentTextColor)
            assertEquals(palette.candidateCommentColor,
                (holder.ui.root.getChildAt(1) as TextView).currentTextColor)
        }
    }

    private fun layout(ui: CandidateItemUi, heightDp: Int) {
        val width = context.dp(240)
        val height = context.dp(heightDp)
        ui.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        ui.root.layout(0, 0, width, height)
    }

    private fun glyphBounds(ui: CandidateItemUi, text: org.fxboomk.fcitx5.android.input.AutoScaleTextView): RectF {
        val bitmap = Bitmap.createBitmap(text.width, text.height, Bitmap.Config.ARGB_8888)
        val ink = Rect()
        try {
            text.draw(Canvas(bitmap))
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) ink.union(x, y, x + 1, y + 1)
            }
        } finally {
            bitmap.recycle()
        }
        val bounds = RectF(ink)
        var view: View = text
        while (view !== ui.root) {
            bounds.offset(view.x, view.y)
            view = view.parent as View
        }
        return bounds
    }

    private fun assertEqualGaps(ui: CandidateItemUi, highlight: Rect) {
        assertFalse("The candidate must have a painted highlight", highlight.isEmpty)
        val ink = glyphBounds(ui, ui.text)
        val gaps = listOf(ink.left - highlight.left, highlight.right - ink.right,
            ink.top - highlight.top, highlight.bottom - ink.bottom)
        assertTrue("Equal padding must fit the word: $gaps", gaps.min() >= -1f)
        assertTrue("Highlight padding must be equal on all four sides: $gaps",
            gaps.max() - gaps.min() <= 2f)
        assertTrue("The highlight must leave the candidate's outer spacing clear",
            highlight.left > ui.root.paddingLeft)
    }

    private fun paintedHighlight(ui: CandidateItemUi): Rect {
        val bitmap = Bitmap.createBitmap(ui.root.width, ui.root.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // View.draw initializes background bounds and propagates the pressed state.
            ui.root.draw(canvas)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            ui.root.background?.draw(canvas)
            val content = ui.root.getChildAt(0) as ViewGroup
            canvas.save()
            canvas.translate(content.x, content.y)
            content.background?.draw(canvas)
            canvas.restore()
            val result = Rect()
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) result.union(x, y, x + 1, y + 1)
            }
            return result
        } finally {
            bitmap.recycle()
        }
    }

    private fun withSquareHighlights(block: () -> Unit) = instrumentation.runOnMainSync {
        val preference = AppPrefs.getInstance().candidates.candidateHighlightRadius
        val original = preference.sharedPreferences.all[preference.key]
        try {
            preference.setValue(0)
            block()
        } finally {
            val editor = preference.sharedPreferences.edit()
            if (original == null) editor.remove(preference.key) else editor.putInt(preference.key, original as Int)
            assertTrue(editor.commit())
        }
    }
}
