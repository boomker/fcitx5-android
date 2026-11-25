/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class AutoScaleTextViewMetricSpanTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun styledRunsMeasureTheirOwnTypefaceAndSize() = onMain {
        val view = textView()
        val content = styled("AAA mmmm", 4, FontSpan(Typeface.MONOSPACE, 72f))
        view.text = content
        naturalLayout(view)

        val annotationPaint = TextPaint(view.paint).apply {
            typeface = Typeface.MONOSPACE
            textSize = 72f
        }
        val top = min(view.paint.fontMetrics.top, annotationPaint.fontMetrics.top)
        val bottom = max(view.paint.fontMetrics.bottom, annotationPaint.fontMetrics.bottom)
        assertEquals(ceil(Layout.getDesiredWidth(content, TextPaint(view.paint))).toInt(), view.width)
        assertEquals(ceil(bottom - top).toInt(), view.height)
        assertEquals((-top).roundToInt(), view.measuredBaseline())
        assertEquals("Spans must not replace the body's typeface", Typeface.SERIF, view.typeface)
        assertEquals(40f, view.textSize, 0.01f)
    }

    @Test
    fun mixedFontDrawingMatchesAndroidLayoutAtTheMeasuredBaseline() = onMain {
        val view = textView()
        val content = styled("AAA mmmm", 4, FontSpan(Typeface.MONOSPACE, 72f)).apply {
            setSpan(ForegroundColorSpan(Color.RED), 4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = content
        naturalLayout(view)
        val actual = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(actual))
            val reference = StaticLayout.Builder.obtain(content, 0, content.length,
                TextPaint(view.paint), view.width)
                .setIncludePad(false).setMaxLines(1).build()
            Canvas(expected).apply {
                translate(-reference.getLineLeft(0),
                    view.measuredBaseline() - reference.getLineBaseline(0).toFloat())
                reference.draw(this)
            }
            for (y in 0 until view.height) {
                for (x in 0 until view.width) {
                    val actualPixel = actual.getPixel(x, y)
                    val expectedPixel = expected.getPixel(x, y)
                    assertTrue("Styled glyph alpha differs at $x,$y",
                        abs(Color.alpha(actualPixel) - Color.alpha(expectedPixel)) <= 1)
                    if (Color.alpha(expectedPixel) > 32) {
                        assertEquals("Styled glyph color differs at $x,$y", expectedPixel, actualPixel)
                    }
                }
            }
        } finally {
            actual.recycle()
            expected.recycle()
        }
    }

    @Test
    fun resettingIdenticalTextRefreshesMutableSpansAndRemovingThemRestoresPlainMetrics() = onMain {
        val view = textView()
        val text = "AAA mmmm"
        view.text = text
        naturalLayout(view)
        val plainWidth = view.width
        val plainHeight = view.height
        val plainBaseline = view.measuredBaseline()

        val span = FontSpan(Typeface.MONOSPACE, 24f)
        val content = styled(text, 4, span)
        view.text = content
        naturalLayout(view)
        val smallWidth = view.width
        assertTrue(smallWidth < plainWidth)
        assertFalse(view.isLayoutRequested)

        span.size = 72f
        view.text = content
        assertTrue("An identical string with changed metrics must request layout", view.isLayoutRequested)
        naturalLayout(view)
        assertTrue(view.width > smallWidth)
        assertTrue(view.height > plainHeight)
        assertEquals(ceil(Layout.getDesiredWidth(content, TextPaint(view.paint))).toInt(), view.width)

        view.text = text
        assertTrue("Removing metric spans must request layout", view.isLayoutRequested)
        naturalLayout(view)
        assertEquals(plainWidth, view.width)
        assertEquals(plainHeight, view.height)
        assertEquals(plainBaseline, view.measuredBaseline())
    }

    @Test
    fun aSpanCoveringAllTextUsesItsActualFontMetrics() = onMain {
        val view = textView()
        val span = FontSpan(Typeface.MONOSPACE, 16f)
        view.text = styled("mmmm", 0, span)
        naturalLayout(view)
        val annotationPaint = TextPaint(view.paint).apply { span.updateMeasureState(this) }
        assertEquals(ceil(annotationPaint.measureText("mmmm")).toInt(), view.width)
        assertEquals(ceil(annotationPaint.fontMetrics.bottom - annotationPaint.fontMetrics.top).toInt(), view.height)
    }

    private class FontSpan(private val font: Typeface, var size: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)
        override fun updateDrawState(textPaint: TextPaint) = apply(textPaint)
        private fun apply(textPaint: TextPaint) {
            textPaint.typeface = font
            textPaint.textSize = size
        }
    }

    private fun styled(text: String, start: Int, span: MetricAffectingSpan) = SpannableString(text).apply {
        setSpan(span, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun textView() = AutoScaleTextView(instrumentation.targetContext).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 40f)
        typeface = Typeface.SERIF
        setTextColor(Color.BLUE)
        gravity = Gravity.LEFT or Gravity.TOP
        scaleMode = AutoScaleTextView.Mode.None
    }

    private fun naturalLayout(view: AutoScaleTextView) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
