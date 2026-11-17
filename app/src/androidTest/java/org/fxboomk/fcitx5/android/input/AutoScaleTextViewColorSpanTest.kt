/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoScaleTextViewColorSpanTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun identicalTextRecolorsAndRemovingSpansRestoresThePlainColorWithoutRelayout() = onMain {
        val view = textView()
        val text = "AAA空"
        view.text = colored(text, 3, text.length, Color.RED)
        assertColorPresent(view, Color.RED)
        assertColorPresent(view, Color.BLUE)
        assertFalse(view.isLayoutRequested)

        view.text = colored(text, 3, text.length, Color.GREEN)
        assertEquals(text, view.text.toString())
        assertFalse("Color-only updates must not force glyph layout", view.isLayoutRequested)
        assertColorPresent(view, Color.GREEN)
        assertColorAbsent(view, Color.RED)
        assertColorPresent(view, Color.BLUE)

        view.text = text
        assertFalse("Removing colors must not force glyph layout", view.isLayoutRequested)
        assertColorAbsent(view, Color.GREEN)
        assertColorPresent(view, Color.BLUE)
    }

    @Test
    fun formattedDrawingKeepsGlyphShapesBaselinesGravityAndScaling() = onMain {
        val samples = listOf(
            "office" to "(字)",
            "👩‍💻" to "(空)",
            "السلام" to "(字)"
        )
        AutoScaleTextView.Mode.entries.forEach { mode ->
            listOf(Gravity.LEFT or Gravity.TOP, Gravity.CENTER, Gravity.RIGHT or Gravity.BOTTOM).forEach { gravity ->
                samples.forEach { (candidate, comment) ->
                    val text = candidate + comment
                    val view = textView().apply {
                        scaleMode = mode
                        this.gravity = gravity
                        fontFeatureSettings = "'liga' 1"
                    }
                    val width = if (mode == AutoScaleTextView.Mode.None) 280 else 80
                    val height = if (mode == AutoScaleTextView.Mode.Proportional) 45 else 120
                    view.text = text
                    val plain = render(view, width, height)
                    try {
                        val plainBounds = view.renderedGlyphBounds()
                        val plainBaseline = view.baseline
                        // CandidateItemUi colors whole candidate words and complete comments;
                        // the split never bisects a ligature, surrogate pair or ZWJ sequence.
                        // Keep the same color to isolate geometry from color-dependent glyph AA.
                        view.text = colored(text, candidate.length, text.length, Color.BLUE)
                        val formatted = render(view, width, height)
                        try {
                            assertEquals("Bounds must stay stable: $text / $mode / $gravity", plainBounds, view.renderedGlyphBounds())
                            assertEquals(plainBaseline, view.baseline)
                            for (y in 0 until height) {
                                for (x in 0 until width) {
                                    val difference = abs(Color.alpha(plain.getPixel(x, y)) -
                                        Color.alpha(formatted.getPixel(x, y)))
                                    assertTrue("Glyph geometry changed at $x,$y: $text / $mode / $gravity", difference <= 1)
                                }
                            }
                        } finally {
                            formatted.recycle()
                        }
                    } finally {
                        plain.recycle()
                    }
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun textView() = AutoScaleTextView(instrumentation.targetContext).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 40f)
        typeface = Typeface.SERIF
        setTextColor(Color.BLUE)
        gravity = Gravity.LEFT or Gravity.TOP
    }

    private fun colored(text: String, start: Int, end: Int, color: Int) = SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun render(view: AutoScaleTextView, width: Int = 280, height: Int = 120): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private fun hasColor(view: AutoScaleTextView, color: Int): Boolean {
        val bitmap = render(view)
        try {
            return (0 until bitmap.height).any { y ->
                (0 until bitmap.width).any { x ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.alpha(pixel) >= 32 &&
                        abs(Color.red(pixel) - Color.red(color)) <= 4 &&
                        abs(Color.green(pixel) - Color.green(color)) <= 4 &&
                        abs(Color.blue(pixel) - Color.blue(color)) <= 4
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorPresent(view: AutoScaleTextView, color: Int) =
        assertTrue("Rendered glyphs must include color $color", hasColor(view, color))

    private fun assertColorAbsent(view: AutoScaleTextView, color: Int) =
        assertFalse("Old color $color must be removed", hasColor(view, color))
}
