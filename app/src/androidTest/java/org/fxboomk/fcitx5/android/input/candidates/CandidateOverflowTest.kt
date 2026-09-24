/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class CandidateOverflowTest {

    @Test
    fun horizontalTextKeepsNaturalWidthAndShowsTailInsidePadding() = onMain {
        val ui = candidate()
        val viewport = viewport(ui)
        assertTrue("The fixture must overflow", maxOffset(ui) > 0f)
        assertEquals(AutoScaleTextView.Mode.None, ui.text.scaleMode)
        assertEquals(
            ceil(ui.text.paint.measureText(LONG_TEXT)).toInt() +
                ui.text.paddingLeft + ui.text.paddingRight,
            ui.text.width,
        )
        assertEquals(24f, ui.text.textSize, EPSILON)
        assertEquals(viewport.paddingLeft, ui.text.left)
        assertTrue("Exercise nonzero viewport padding", viewport.paddingRight > 0)
        assertAtTail(ui)
        assertEquals(
            (viewport.width - viewport.paddingRight).toFloat(),
            ui.text.right + ui.text.translationX,
            EPSILON,
        )
    }

    @Test
    fun draggingToHeadAndBackSuppressesClicksIncludingEndpointDrags() = onMain {
        val ui = candidate()
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }
        tap(ui)
        assertEquals("Control tap must reach the listener", 1, clicks)
        clicks = 0

        val distance = maxOffset(ui) + touchSlop(ui) + 16f
        drag(ui, distance)
        assertEquals(0f, ui.text.translationX, EPSILON)
        drag(ui, distance) // Outward drag while already at the head.
        assertEquals(0f, ui.text.translationX, EPSILON)
        drag(ui, -distance)
        assertAtTail(ui)
        drag(ui, -distance) // Outward drag while already at the tail.
        assertAtTail(ui)
        assertEquals(0, clicks)
    }

    @Test
    fun pointerHandoffKeepsDragDeltaAndSuppressesClick() = onMain {
        val ui = candidate()
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }
        val time = SystemClock.uptimeMillis()
        val primaryX = 40f + touchSlop(ui) + 32f
        val secondaryX = ui.root.width - 24f
        val initialTranslation = ui.text.translationX
        multiTouch(ui, MotionEvent.ACTION_DOWN, time, 0, 0 to 40f)
        multiTouch(ui, MotionEvent.ACTION_MOVE, time, 16, 0 to primaryX)
        val dragTranslation = ui.text.translationX
        assertTrue("Horizontal dragging must have started", dragTranslation > initialTranslation)
        assertTrue("Keep enough headroom to detect a ten-pixel move", dragTranslation < -10f)
        assertTrue("Pointers must be widely separated", secondaryX - primaryX > 100f)

        multiTouch(
            ui,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            time, 32, 0 to primaryX, 7 to secondaryX,
        )
        assertEquals(dragTranslation, ui.text.translationX, EPSILON)
        // Pointer 0 lifts; pointer ID 7 becomes index 0 without moving on screen.
        multiTouch(ui, MotionEvent.ACTION_POINTER_UP, time, 48, 0 to primaryX, 7 to secondaryX)
        assertEquals("Handoff must not change the offset", dragTranslation, ui.text.translationX, EPSILON)

        multiTouch(ui, MotionEvent.ACTION_MOVE, time, 64, 7 to secondaryX + 10f)
        assertEquals(
            "Only the remaining pointer's ten-pixel movement should scroll",
            dragTranslation + 10f,
            ui.text.translationX,
            EPSILON,
        )
        multiTouch(ui, MotionEvent.ACTION_UP, time, 80, 7 to secondaryX + 10f)
        assertEquals(dragTranslation + 10f, ui.text.translationX, EPSILON)
        assertEquals(0, clicks)
    }

    @Test
    fun identicalRelayoutPreservesManualOffset() = onMain {
        val ui = candidate()
        drag(ui, maxOffset(ui) / 2f)
        val manualOffset = ui.text.translationX
        assertTrue(manualOffset < 0f && manualOffset > -maxOffset(ui))

        layout(ui)
        layout(ui)

        assertEquals(manualOffset, ui.text.translationX, EPSILON)
    }

    @Test
    fun growingTextFollowsTailAfterManualScroll() = onMain {
        val ui = candidate()
        drag(ui, maxOffset(ui) / 2f)
        val oldWidth = ui.text.width

        ui.updateCandidate(word(LONG_TEXT + LONG_TEXT))
        layout(ui)

        assertTrue(ui.text.width > oldWidth)
        assertAtTail(ui)
    }

    @Test
    fun changedTextWithSameWidthStillFollowsTail() = onMain {
        val ui = candidate(text = "i".repeat(160))
        drag(ui, maxOffset(ui) / 2f)
        val oldWidth = ui.text.width
        assertTrue(ui.text.translationX > -maxOffset(ui))

        // Monospace letters guarantee that this update does not resize the child.
        ui.updateCandidate(word("l".repeat(160)))
        layout(ui)

        assertEquals(oldWidth, ui.text.width)
        assertAtTail(ui)
    }

    @Test
    fun viewportWidthChangeFollowsTailAfterManualScroll() = onMain {
        val ui = candidate()
        drag(ui, maxOffset(ui) / 2f)
        val oldOffset = maxOffset(ui)

        layout(ui, WIDTH + 80)

        assertTrue(maxOffset(ui) < oldOffset)
        assertAtTail(ui)
    }

    @Test
    fun shorterTextClearsTranslationAndFitsInsideViewport() = onMain {
        val ui = candidate()
        assertTrue(ui.text.translationX < 0f)
        ui.updateCandidate(word("ok"))
        layout(ui)

        val viewport = viewport(ui)
        assertEquals(0f, maxOffset(ui), EPSILON)
        assertEquals(0f, ui.text.translationX, EPSILON)
        assertTrue(ui.text.left >= viewport.paddingLeft)
        assertTrue(ui.text.right <= viewport.width - viewport.paddingRight)
        drag(ui, touchSlop(ui) + 16f)
        assertEquals(0f, ui.text.translationX, EPSILON)
    }

    @Test
    fun nonHorizontalCandidateKeepsConstrainedProportionalScaling() = onMain {
        val ui = candidate(horizontal = false)
        val viewport = viewport(ui)
        val available = viewport.width - viewport.paddingLeft - viewport.paddingRight

        assertEquals(AutoScaleTextView.Mode.Proportional, ui.text.scaleMode)
        assertTrue(ui.text.paint.measureText(LONG_TEXT) > available)
        assertTrue(ui.text.width <= available)
        assertEquals(0f, ui.text.translationX, EPSILON)
        drag(ui, touchSlop(ui) + 16f)
        assertEquals(0f, ui.text.translationX, EPSILON)
    }

    @Test
    fun horizontalAdapterEnablesOverflowAtNonzeroPosition() = onMain {
        val adapter = HorizontalCandidateViewAdapter(ThemePreset.MaterialLight)
        adapter.updateCandidates(arrayOf(word("first"), word(LONG_TEXT)), total = 2)
        val holder = adapter.onCreateViewHolder(FrameLayout(targetContext), 0)
        adapter.onBindViewHolder(holder, 1)
        configureText(holder.ui)
        layout(holder.ui)

        assertEquals(1, holder.idx)
        assertEquals(LONG_TEXT, holder.ui.text.text.toString())
        assertEquals(AutoScaleTextView.Mode.None, holder.ui.text.scaleMode)
        assertTrue(maxOffset(holder.ui) > 0f)
        assertAtTail(holder.ui)
        drag(holder.ui, maxOffset(holder.ui) + touchSlop(holder.ui) + 16f)
        assertEquals(0f, holder.ui.text.translationX, EPSILON)
    }

    @SuppressLint("ClickableViewAccessibility")
    @Test
    fun dragCancelsConsumingTouchListenerBeforeTakingOver() = onMain {
        val ui = candidate()
        val actions = mutableListOf<Int>()
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }
        ui.root.setOnTouchListener { view, event ->
            actions += event.actionMasked
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }

        drag(ui, maxOffset(ui) + touchSlop(ui) + 16f)

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), actions)
        assertEquals(0f, ui.text.translationX, EPSILON)
        assertEquals(0, clicks)
    }

    @Test
    fun cancelDuringDragDoesNotClickOrLeakIntoNextGesture() = onMain {
        val ui = candidate()
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }
        drag(ui, maxOffset(ui) / 2f, endAction = MotionEvent.ACTION_CANCEL)
        val cancelledOffset = ui.text.translationX
        assertTrue(cancelledOffset < 0f && cancelledOffset > -maxOffset(ui))
        assertEquals(0, clicks)
        assertFalse(ui.root.isPressed)

        tap(ui)
        assertEquals("A new tap must not inherit the cancelled drag", 1, clicks)
        assertEquals(cancelledOffset, ui.text.translationX, EPSILON)
        drag(ui, -maxOffset(ui))
        assertAtTail(ui)
        assertEquals(1, clicks)
    }

    @Test
    fun cancelBeforeDragDoesNotClickAndNextTapWorks() = onMain {
        val ui = candidate()
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }
        val time = SystemClock.uptimeMillis()
        touch(ui, MotionEvent.ACTION_DOWN, WIDTH / 2f, time, 0)
        touch(ui, MotionEvent.ACTION_CANCEL, WIDTH / 2f, time, 16)
        assertEquals(0, clicks)
        assertFalse(ui.root.isPressed)
        assertAtTail(ui)

        tap(ui)
        assertEquals(1, clicks)
        assertAtTail(ui)
    }

    @SuppressLint("ClickableViewAccessibility")
    @Test
    fun handledLongPressKeepsArmedTouchListenerInControl() = onMain {
        val ui = candidate()
        val actions = mutableListOf<Int>()
        var longClicks = 0
        var clicks = 0
        ui.root.setOnClickListener { clicks++ }
        ui.root.setOnLongClickListener {
            longClicks++
            true
        }
        // These unattached views have no lifecycle owner for the long-press coroutine.
        ui.root.longPressEnabled = false
        ui.root.setOnTouchListener { _, event ->
            actions += event.actionMasked
            true
        }
        val time = SystemClock.uptimeMillis()
        val x = WIDTH / 2f
        touch(ui, MotionEvent.ACTION_DOWN, x, time, 0)
        assertTrue(ui.root.performLongClick())
        touch(ui, MotionEvent.ACTION_MOVE, x + touchSlop(ui) + 16f, time, 16)
        touch(ui, MotionEvent.ACTION_UP, x + touchSlop(ui) + 16f, time, 32)

        assertEquals(1, longClicks)
        assertEquals(0, clicks)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            actions,
        )
        assertAtTail(ui)
    }

    private fun candidate(text: String = LONG_TEXT, horizontal: Boolean = true): CandidateItemUi {
        val ui = CandidateItemUi(targetContext, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        configureText(ui)
        ui.configureHorizontalHighlightSpacing(outerPadding = 7, highlightPadding = 11, verticalPadding = 3)
        if (horizontal) ui.enableHorizontalOverflow()
        ui.updateCandidate(word(text))
        layout(ui)
        return ui
    }

    private fun configureText(ui: CandidateItemUi) {
        ui.text.typeface = Typeface.MONOSPACE
        ui.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f)
        ui.root.longPressEnabled = false
    }

    private fun layout(ui: CandidateItemUi, width: Int = WIDTH) {
        // Force a real layout pass even when all bounds are identical.
        ui.text.forceLayout()
        viewport(ui).forceLayout()
        ui.root.forceLayout()
        ui.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        ui.root.layout(0, 0, width, HEIGHT)
    }

    private fun viewport(ui: CandidateItemUi) = ui.root.getChildAt(0) as FrameLayout

    private fun maxOffset(ui: CandidateItemUi): Float {
        val viewport = viewport(ui)
        return (ui.text.width - (viewport.width - viewport.paddingLeft - viewport.paddingRight))
            .coerceAtLeast(0).toFloat()
    }

    private fun assertAtTail(ui: CandidateItemUi) {
        assertEquals("Text should follow the tail", -maxOffset(ui), ui.text.translationX, EPSILON)
    }

    private fun touchSlop(ui: CandidateItemUi) =
        ViewConfiguration.get(ui.root.context).scaledTouchSlop.toFloat()

    private fun drag(ui: CandidateItemUi, distance: Float, endAction: Int = MotionEvent.ACTION_UP) {
        val time = SystemClock.uptimeMillis()
        val start = ui.root.width / 2f
        touch(ui, MotionEvent.ACTION_DOWN, start, time, 0)
        touch(ui, MotionEvent.ACTION_MOVE, start + distance / 2f, time, 16)
        touch(ui, MotionEvent.ACTION_MOVE, start + distance, time, 32)
        touch(ui, endAction, start + distance, time, 48)
    }

    private fun tap(ui: CandidateItemUi) {
        val time = SystemClock.uptimeMillis()
        val x = ui.root.width / 2f
        touch(ui, MotionEvent.ACTION_DOWN, x, time, 0)
        touch(ui, MotionEvent.ACTION_UP, x, time, 16)
    }

    private fun touch(ui: CandidateItemUi, action: Int, x: Float, downTime: Long, elapsed: Long) {
        val event = MotionEvent.obtain(downTime, downTime + elapsed, action, x, HEIGHT / 2f, 0)
        try {
            assertTrue("Touch action $action should be handled", ui.root.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun multiTouch(
        ui: CandidateItemUi,
        action: Int,
        downTime: Long,
        elapsed: Long,
        vararg pointers: Pair<Int, Float>,
    ) {
        val properties = pointers.map { (id, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coordinates = pointers.map { (_, x) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                y = HEIGHT / 2f
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, downTime + elapsed, action, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertTrue("Multi-pointer action $action should be handled", ui.root.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun word(text: String) = CandidateWord("", text, "", false)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val targetContext = instrumentation.targetContext
        private const val WIDTH = 320
        private const val HEIGHT = 100
        private const val EPSILON = 0.01f
        private val LONG_TEXT = "candidate overflow ".repeat(12)
    }
}
