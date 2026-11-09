/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import android.view.MotionEvent
import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEditingUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density

    @Test
    fun selectionButtonsOccupyEqualPositionsAndBothBottomCorners() = onMain {
        val ui = createUi(GestureEditingMode.Selection)
        val root = ui.root
        assertEquals(3, root.buttons.size)
        assertEquals(0, ui.exitButton.left)
        assertEquals(root.width, ui.deleteButton.right)
        listOf(ui.exitButton, ui.actionsButton, ui.deleteButton).forEach {
            assertEquals(root.height, it.bottom)
        }
        val centers = root.buttons.map { (it.left + it.right) / 2 }
        assertTrue(kotlin.math.abs((centers[1] - centers[0]) - (centers[2] - centers[1])) <= 1)
    }

    @Test
    fun cursorModePlacesExitSwitchPasteAndDeleteAtEqualIntervals() = onMain {
        val ui = createUi(GestureEditingMode.Cursor)
        assertEquals(listOf(ui.exitButton, ui.switchButton, ui.pasteButton, ui.deleteButton), ui.root.buttons)
        assertEquals(0, ui.exitButton.left)
        assertEquals(ui.root.width, ui.deleteButton.right)
        val centers = ui.root.buttons.map { (it.left + it.right) / 2 }
        val distances = centers.zipWithNext { left, right -> right - left }
        assertTrue(requireNotNull(distances.maxOrNull()) - requireNotNull(distances.minOrNull()) <= 1)
    }

    @Test
    fun allBottomControlsUseIconsWithAccessibleLabels() = onMain {
        GestureEditingMode.entries.forEach { mode ->
            createUi(mode).root.buttons.forEach { control ->
                val button = control as TextEditingButton
                assertTrue(button.imageView.parent === button)
                assertEquals(null, button.textView.parent)
                assertTrue(button.contentDescription?.isNotBlank() == true)
                assertEquals(button.contentDescription, button.tooltipText)
                assertEquals((64 * density).toInt(), button.width)
                assertEquals((48 * density).toInt(), button.height)
            }
        }
    }

    @Test
    fun draggingFromActionButtonMovesAndNeverClicksTheButton() = onMain {
        val steps = mutableListOf<GestureMovementAccumulator.Movement>()
        val ui = createUi(GestureEditingMode.Selection, steps::add)
        var clicks = 0
        ui.actionsButton.setOnClickListener { clicks++ }
        val x = centerX(ui.actionsButton)
        val y = centerY(ui.actionsButton)
        send(ui.root, MotionEvent.ACTION_DOWN, x, y)
        send(ui.root, MotionEvent.ACTION_MOVE, x + 48f * density, y)
        send(ui.root, MotionEvent.ACTION_UP, x + 48f * density, y)
        assertEquals(0, clicks)
        assertEquals(3, steps.sumOf { it.horizontal })
    }

    @Test
    fun tapsWorkButCancelledTouchesAndLargeReleaseTravelCannotClick() = onMain {
        val ui = createUi(GestureEditingMode.Selection)
        var clicks = 0
        ui.exitButton.setOnClickListener { clicks++ }
        val x = centerX(ui.exitButton)
        val y = centerY(ui.exitButton)
        send(ui.root, MotionEvent.ACTION_DOWN, x, y)
        send(ui.root, MotionEvent.ACTION_UP, x, y)
        assertEquals(1, clicks)

        send(ui.root, MotionEvent.ACTION_DOWN, x, y)
        send(ui.root, MotionEvent.ACTION_CANCEL, x, y)
        send(ui.root, MotionEvent.ACTION_UP, x, y)
        send(ui.root, MotionEvent.ACTION_DOWN, x, y)
        send(ui.root, MotionEvent.ACTION_UP, x + 48f * density, y)
        assertEquals(1, clicks)
    }

    @Test
    fun eachStrokeHasItsOwnBaselineAndEntryMovesAreIgnored() = onMain {
        val steps = mutableListOf<GestureMovementAccumulator.Movement>()
        val ui = createUi(GestureEditingMode.Cursor, steps::add)
        send(ui.root, MotionEvent.ACTION_MOVE, 160f * density, 60f * density)
        send(ui.root, MotionEvent.ACTION_UP, 160f * density, 60f * density)
        assertTrue(steps.isEmpty())
        repeat(2) {
            send(ui.root, MotionEvent.ACTION_DOWN, 100f * density, 60f * density)
            send(ui.root, MotionEvent.ACTION_MOVE, 116f * density, 60f * density)
            send(ui.root, MotionEvent.ACTION_UP, 116f * density, 60f * density)
        }
        assertEquals(2, steps.sumOf { it.horizontal })
    }

    @Test
    fun addingAnotherFingerCancelsTheStrokeWithoutActivatingButtons() = onMain {
        val steps = mutableListOf<GestureMovementAccumulator.Movement>()
        val ui = createUi(GestureEditingMode.Selection, steps::add)
        var clicks = 0
        ui.deleteButton.setOnClickListener { clicks++ }
        val x = centerX(ui.deleteButton)
        val y = centerY(ui.deleteButton)
        send(ui.root, MotionEvent.ACTION_DOWN, x, y)
        val pointers = Array(2) { index -> MotionEvent.PointerProperties().apply {
            id = index
            toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
            this.x = x - index * 32f * density
            this.y = y
            pressure = 1f
            size = 1f
        } }
        val event = MotionEvent.obtain(
            0L, 0L, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, pointers, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0
        )
        try {
            ui.root.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
        send(ui.root, MotionEvent.ACTION_MOVE, x - 48f * density, y)
        send(ui.root, MotionEvent.ACTION_UP, x - 48f * density, y)
        assertTrue(steps.isEmpty())
        assertEquals(0, clicks)
    }

    @Test
    fun bothModesDrawAColoredTrailThatFadesAfterFingerUp() {
        GestureEditingMode.entries.forEach { mode ->
            lateinit var ui: GestureEditingUi
            val x = 60f * density
            val y = 24f * density
            var before = 0
            onMain {
                ui = createUi(mode)
                before = pixel(ui.root, x, y)
                send(ui.root, MotionEvent.ACTION_DOWN, 20f * density, y)
                send(ui.root, MotionEvent.ACTION_MOVE, 100f * density, y)
                assertEquals(ThemePreset.MaterialLight.accentKeyBackgroundColor, pixel(ui.root, x, y))
                send(ui.root, MotionEvent.ACTION_UP, 100f * density, y)
            }
            SystemClock.sleep(350)
            onMain { assertEquals(before, pixel(ui.root, x, y)) }
        }
    }

    @Test
    fun cancelledStrokesClearTheTrailAndEntryMovesDoNotDraw() = onMain {
        val ui = createUi(GestureEditingMode.Cursor)
        val x = 60f * density
        val y = 24f * density
        val before = pixel(ui.root, x, y)
        send(ui.root, MotionEvent.ACTION_MOVE, 100f * density, y)
        assertEquals(before, pixel(ui.root, x, y))
        send(ui.root, MotionEvent.ACTION_DOWN, 20f * density, y)
        send(ui.root, MotionEvent.ACTION_MOVE, 100f * density, y)
        assertTrue(before != pixel(ui.root, x, y))
        send(ui.root, MotionEvent.ACTION_CANCEL, 100f * density, y)
        assertEquals(before, pixel(ui.root, x, y))
    }

    @Test
    fun fasterStrokesMoveMoreCharactersInBothModes() = onMain {
        GestureEditingMode.entries.forEach { mode ->
            fun moved(duration: Long): Int {
                var characters = 0
                val ui = createUi(mode) { characters += it.horizontal }
                val x = 50f * density
                val y = 24f * density
                send(ui.root, MotionEvent.ACTION_DOWN, x, y, 1000L)
                repeat(4) { step ->
                    send(ui.root, MotionEvent.ACTION_MOVE, x + (step + 1) * 16f * density, y,
                        1000L + duration * (step + 1) / 4)
                }
                send(ui.root, MotionEvent.ACTION_UP, x + 64f * density, y, 1000L + duration)
                return characters
            }
            val slow = moved(400L)
            val fast = moved(40L)
            assertEquals(4, slow)
            assertTrue("Fast stroke should advance farther", fast > slow)
            assertTrue("Acceleration remains bounded", fast <= slow * 4)
        }
    }

    @Test
    fun batchedTouchSamplesKeepTheCurvedFingerTrail() = onMain {
        val ui = createUi(GestureEditingMode.Selection)
        send(ui.root, MotionEvent.ACTION_DOWN, 20f * density, 30f * density, 1000L)
        val event = MotionEvent.obtain(1000L, 1010L, MotionEvent.ACTION_MOVE, 40f * density, 30f * density, 0)
        try {
            event.addBatch(1020L, 80f * density, 70f * density, 1f, 1f, 0)
            event.addBatch(1030L, 120f * density, 30f * density, 1f, 1f, 0)
            ui.root.dispatchTouchEvent(event)
            assertEquals(ThemePreset.MaterialLight.accentKeyBackgroundColor, pixel(ui.root, 80f * density, 70f * density))
        } finally { event.recycle(); ui.root.cancelGesture() }
    }

    private fun pixel(view: View, x: Float, y: Float): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return bitmap.getPixel(x.toInt(), y.toInt())
        } finally { bitmap.recycle() }
    }

    private fun createUi(
        mode: GestureEditingMode,
        onMovement: (GestureMovementAccumulator.Movement) -> Unit = {}
    ) = GestureEditingUi(context, ThemePreset.MaterialLight, mode, false, true, 4f, onMovement).also {
        val width = (400f * density).toInt()
        val height = (240f * density).toInt()
        it.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        it.root.layout(0, 0, width, height)
    }

    private fun centerX(view: View) = (view.left + view.right) / 2f
    private fun centerY(view: View) = (view.top + view.bottom) / 2f

    private fun send(view: View, action: Int, x: Float, y: Float, eventTime: Long = 0L) {
        val event = MotionEvent.obtain(0L, eventTime, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
