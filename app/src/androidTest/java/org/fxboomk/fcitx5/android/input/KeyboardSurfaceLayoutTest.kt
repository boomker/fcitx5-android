/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardSurfaceLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun topCornersClipBackgroundAndChildrenButKeepBottomCornersSquare() = onMain {
        val surface = KeyboardSurfaceLayout(instrumentation.targetContext)
        assertEquals(false, surface.willNotDraw())
        surface.addView(View(surface.context).apply { setBackgroundColor(Color.BLUE) },
            ConstraintLayout.LayoutParams(100, 80))
        surface.setCornerRadii(24f)
        val bitmap = render(surface)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(99, 0))
        assertEquals(Color.BLUE, bitmap.getPixel(50, 0))
        assertEquals(Color.BLUE, bitmap.getPixel(0, 79))
        assertEquals(Color.BLUE, bitmap.getPixel(99, 79))
        bitmap.recycle()
    }

    @Test
    fun zeroRestoresSquareCornersAndFloatingBottomRadiusStaysIndependent() = onMain {
        val surface = KeyboardSurfaceLayout(instrumentation.targetContext)
        surface.setBackgroundColor(Color.BLUE)
        surface.setCornerRadii(24f, 10f)
        render(surface).also {
            assertEquals(Color.TRANSPARENT, it.getPixel(0, 79))
            it.recycle()
        }
        surface.setCornerRadii(0f, 10f)
        render(surface).also {
            assertEquals(Color.BLUE, it.getPixel(0, 0))
            assertEquals(Color.TRANSPARENT, it.getPixel(0, 79))
            it.recycle()
        }
        surface.setCornerRadii(0f)
        render(surface).also {
            assertEquals(Color.BLUE, it.getPixel(0, 0))
            assertEquals(Color.BLUE, it.getPixel(99, 0))
            assertEquals(Color.BLUE, it.getPixel(0, 79))
            it.recycle()
        }
    }

    @Test
    fun radiusIsClampedToMeasuredBoundsAndRecomputedAfterResize() = onMain {
        val surface = KeyboardSurfaceLayout(instrumentation.targetContext)
        surface.setCornerRadii(200f, 100f)
        render(surface).recycle()
        assertEquals(List(8) { 40f }, surface.cornerRadii().toList())
        surface.layout(0, 0, 30, 20)
        assertEquals(List(8) { 10f }, surface.cornerRadii().toList())
        surface.setCornerRadii(Float.NaN, -1f)
        assertEquals(List(8) { 0f }, surface.cornerRadii().toList())
    }

    private fun render(surface: KeyboardSurfaceLayout): Bitmap {
        surface.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        surface.layout(0, 0, 100, 80)
        return Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).also { surface.draw(Canvas(it)) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
