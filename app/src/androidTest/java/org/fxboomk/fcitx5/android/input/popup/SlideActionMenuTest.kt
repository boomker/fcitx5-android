/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.popup

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.input.predict.AiCandidateAction
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp

class SlideActionMenuTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun slidingEachLabelHighlightsItAndReleaseRunsOnlyThatAction() = withSession { session ->
        AiCandidateAction.entries.forEachIndexed { index, action ->
            session.open()
            assertEquals(listOf("润", "译", "答", "调"), session.labels().map { it.text.toString() })
            session.moveTo(index)
            session.labels().forEachIndexed { position, label ->
                assertEquals(position == index, label.isSelected)
            }
            assertTrue("Moving must not run the action", session.selected.isEmpty())
            session.releaseAt(index)
            assertEquals(listOf(action), session.selected)
            assertNull(session.menu.root.parent)
            assertNull(session.anchor.onHeldTouchEvent)
            assertEquals(0, session.clicks)
            // Finishing and dismissing again must not deliver a second action.
            session.menu.dismiss()
            assertEquals(listOf(action), session.selected)
            session.selected.clear()
        }
    }

    @Test
    fun holdingWithoutSlidingDoesNotExecuteAnAction() = withSession { session ->
        session.open()
        assertTrue(session.labels().none { it.isSelected })
        session.send(MotionEvent.ACTION_UP, session.anchorCenter())
        assertTrue(session.selected.isEmpty())
        assertEquals(0, session.clicks)
        assertNull(session.menu.root.parent)
    }

    @Test
    fun horizontalSlidingAtButtonHeightSelectsEveryActionWithoutEnteringBubble() = withSession { session ->
        AiCandidateAction.entries.forEachIndexed { index, action ->
            session.open()
            val point = session.labelCenter(index).first to session.anchorCenter().second
            session.send(MotionEvent.ACTION_MOVE, point)
            session.labels().forEachIndexed { position, label ->
                assertEquals(position == index, label.isSelected)
            }
            assertTrue(session.selected.isEmpty())
            session.send(MotionEvent.ACTION_UP, point)
            assertEquals(listOf(action), session.selected)
            assertEquals(0, session.clicks)
            session.selected.clear()
        }
    }

    @Test
    fun slidingAcrossTheGapKeepsTheSameHighlightedAction() = withSession { session ->
        session.open()
        val x = session.labelCenter(1).first
        val buttonY = session.anchorCenter().second
        val bubbleY = session.labelCenter(1).second
        for (y in listOf(buttonY, (buttonY + bubbleY) / 2, bubbleY)) {
            session.send(MotionEvent.ACTION_MOVE, x to y)
            assertTrue(session.labels()[1].isSelected)
            assertTrue(session.selected.isEmpty())
        }
        session.send(MotionEvent.ACTION_UP, x to buttonY)
        assertEquals(listOf(AiCandidateAction.Translate), session.selected)
    }

    @Test
    fun crossingEitherHorizontalEdgeCancelsEvenIfTheFingerReturnsBeforeRelease() = withSession { session ->
        for (right in listOf(false, true)) {
            session.open()
            val point = session.labelCenter(2).first to session.anchorCenter().second
            session.send(MotionEvent.ACTION_MOVE, point)
            val location = IntArray(2)
            session.menu.root.getLocationOnScreen(location)
            val outsideX = if (right) location[0] + session.menu.root.width + 1f else location[0] - 1f
            session.send(MotionEvent.ACTION_MOVE, outsideX to point.second)
            assertNull(session.menu.root.parent)
            assertTrue(session.labels().none { it.isSelected })
            session.send(MotionEvent.ACTION_MOVE, point)
            session.send(MotionEvent.ACTION_UP, point)
            assertTrue(session.selected.isEmpty())
            assertEquals(0, session.clicks)
        }
        session.shortClick()
        assertEquals(1, session.clicks)
    }

    @Test
    fun slidingBeyondTheBubbleTopOrButtonBottomCancels() = withSession { session ->
        for (above in listOf(false, true)) {
            session.open()
            val x = session.labelCenter(0).first
            session.send(MotionEvent.ACTION_MOVE, x to session.anchorCenter().second)
            val location = IntArray(2)
            val y = if (above) {
                session.menu.root.getLocationOnScreen(location)
                location[1] - 1f
            } else {
                session.anchor.getLocationOnScreen(location)
                location[1] + session.anchor.height + 1f
            }
            session.send(MotionEvent.ACTION_MOVE, x to y)
            assertNull(session.menu.root.parent)
            session.send(MotionEvent.ACTION_UP, x to y)
            assertTrue(session.selected.isEmpty())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun leavingTheBubbleRemovesHighlightAndCancelsTheAction() = withSession { session ->
        session.open()
        session.moveTo(1)
        assertTrue(session.labels()[1].isSelected)
        val outside = session.outsideBubble()
        session.send(MotionEvent.ACTION_MOVE, outside)
        assertTrue(session.labels().none { it.isSelected })
        session.send(MotionEvent.ACTION_UP, outside)
        assertTrue(session.selected.isEmpty())
        assertEquals(0, session.clicks)
        assertNull(session.menu.root.parent)
    }

    @Test
    fun releaseUsesItsFinalPositionInsteadOfThePreviousHighlight() = withSession { session ->
        session.open()
        session.moveTo(0)
        session.releaseAt(3)
        assertEquals(listOf(AiCandidateAction.Settings), session.selected)
        assertEquals(0, session.clicks)
    }

    @Test
    fun cancelDismissesWithoutExecutingAndNextShortClickStillWorks() = withSession { session ->
        session.open()
        session.moveTo(2)
        session.send(MotionEvent.ACTION_CANCEL, session.labelCenter(2))
        assertTrue(session.selected.isEmpty())
        assertNull(session.menu.root.parent)
        assertNull(session.anchor.onHeldTouchEvent)
        session.shortClick()
        assertEquals(1, session.clicks)
        assertTrue(session.selected.isEmpty())
    }

    @Test
    fun addingAnotherFingerCancelsAndDoesNotTurnReleaseIntoAClick() = withSession { session ->
        session.open()
        session.moveTo(0)
        session.addSecondPointer()
        assertNull(session.menu.root.parent)
        assertNull(session.anchor.onHeldTouchEvent)
        session.send(MotionEvent.ACTION_UP, session.anchorCenter())
        assertTrue(session.selected.isEmpty())
        assertEquals(0, session.clicks)
        session.shortClick()
        assertEquals(1, session.clicks)
    }

    @Test
    fun anotherFingerBeforeTheLongPressTimeoutPreventsTheMenuAndClick() = withSession(onMain = false) { session ->
        var timeout = 0L
        instrumentation.runOnMainSync {
            session.beginTouch()
            session.addSecondPointer()
            timeout = CustomGestureView.longPressDelay.toLong() + 200L
        }
        // Keep the main looper running so an uncancelled long-press job would fire.
        Thread.sleep(timeout)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertFalse("The cancelled timeout must not create a menu", session.hasMenu())
            assertNull(session.anchor.onHeldTouchEvent)
            session.send(MotionEvent.ACTION_UP, session.anchorCenter())
            assertTrue(session.selected.isEmpty())
            assertEquals(0, session.clicks)
            session.shortClick()
            assertEquals(1, session.clicks)
        }
    }

    @Test
    fun aMoveWithMultiplePointersCancelsAnAlreadyOpenMenu() = withSession { session ->
        session.open()
        session.moveTo(1)
        session.sendMultiplePointers(MotionEvent.ACTION_MOVE, session.labelCenter(2))
        assertNull(session.menu.root.parent)
        assertNull(session.anchor.onHeldTouchEvent)
        session.send(MotionEvent.ACTION_UP, session.anchorCenter())
        assertTrue(session.selected.isEmpty())
        assertEquals(0, session.clicks)
        session.shortClick()
        assertEquals(1, session.clicks)
    }

    @Test
    fun removingTheBubbleCancelsTheHeldGestureUntilRelease() = withSession { session ->
        session.open()
        session.moveTo(1)
        session.overlay.removeView(session.menu.root)
        assertNull(session.anchor.onHeldTouchEvent)
        session.send(MotionEvent.ACTION_UP, session.anchorCenter())
        assertTrue(session.selected.isEmpty())
        assertEquals(0, session.clicks)
        session.shortClick()
        assertEquals(1, session.clicks)
    }

    @Test
    fun removingTheAnchorDismissesTheBubbleAndDoesNotExecute() = withSession(onMain = false) { session ->
        lateinit var originalCenter: Pair<Float, Float>
        instrumentation.runOnMainSync {
            session.open()
            session.moveTo(3)
            originalCenter = session.anchorCenter()
            session.overlay.removeView(session.anchor)
            assertNull(session.anchor.onHeldTouchEvent)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertNull(session.menu.root.parent)
            session.send(MotionEvent.ACTION_UP, originalCenter)
            assertTrue(session.selected.isEmpty())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun removingTheOverlayCancelsWithoutChangingItsChildrenDuringDetach() = withSession(onMain = false) { session ->
        lateinit var originalCenter: Pair<Float, Float>
        instrumentation.runOnMainSync {
            session.open()
            session.moveTo(2)
            originalCenter = session.anchorCenter()
            val parent = session.overlay.parent as ViewGroup
            parent.removeView(session.overlay)
            assertNull(session.anchor.onHeldTouchEvent)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertNull(session.overlay.parent)
            assertNull(session.menu.root.parent)
            session.send(MotionEvent.ACTION_UP, originalCenter)
            assertTrue(session.selected.isEmpty())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun popupStaysWithinTheOverlayForLeftMiddleAndRightButtons() = withSession { session ->
        for (left in listOf(0, (session.width - session.anchor.width) / 2, session.width - session.anchor.width)) {
            val params = session.anchor.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = left
            session.anchor.layoutParams = params
            session.layout()
            session.open()
            assertTrue("Popup left edge must remain visible", session.menu.root.left >= 0)
            assertTrue("Popup right edge must remain visible", session.menu.root.right <= session.overlay.width)
            assertTrue("Popup must sit above the held button", session.menu.root.bottom < session.anchor.top)
            assertEquals(listOf("润", "译", "答", "调"), session.labels().map { it.text.toString() })
            session.moveTo(3)
            session.releaseAt(3)
            assertEquals(listOf(AiCandidateAction.Settings), session.selected)
            session.selected.clear()
        }
    }

    @Test
    fun labelsAndSelectionUseTheCurrentLightOrDarkKeyboardTheme() {
        for (theme in listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark)) {
            withSession(theme) { session ->
                session.open()
                assertNotNull(session.menu.root.background)
                session.labels().forEachIndexed { index, label ->
                    assertEquals(theme.popupTextColor, label.currentTextColor)
                    assertEquals(session.anchor.context.getString(AiCandidateAction.entries[index].titleRes), label.contentDescription)
                }
                session.moveTo(0)
                assertEquals(theme.genericActiveForegroundColor, session.labels()[0].currentTextColor)
                assertNotNull(session.labels()[0].background)
                session.moveTo(2)
                assertEquals(theme.popupTextColor, session.labels()[0].currentTextColor)
                assertNull(session.labels()[0].background)
                assertEquals(theme.genericActiveForegroundColor, session.labels()[2].currentTextColor)
            }
        }
    }

    @Test
    fun accessibilityCanClickAnActionAfterOpeningTheMenu() = withSession { session ->
        session.open()
        assertTrue(session.labels()[2].performClick())
        assertEquals(listOf(AiCandidateAction.Reply), session.selected)
        assertNull(session.menu.root.parent)
        session.send(MotionEvent.ACTION_UP, session.anchorCenter())
        assertEquals(0, session.clicks)
    }

    private fun withSession(
        theme: Theme = ThemePreset.MaterialLight,
        onMain: Boolean = true,
        block: (Session) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        var session: Session? = null
        try {
            instrumentation.runOnMainSync {
                val created = Session(activity, theme)
                session = created
                activity.setContentView(FrameLayout(activity).apply {
                    addView(created.overlay, FrameLayout.LayoutParams(created.width, created.height))
                })
            }
            instrumentation.waitForIdleSync()
            val current = requireNotNull(session)
            instrumentation.runOnMainSync {
                current.layout()
                assertTrue("Fixture must attach to a real window", current.anchor.isAttachedToWindow)
            }
            if (onMain) instrumentation.runOnMainSync { block(current) } else block(current)
        } finally {
            instrumentation.runOnMainSync {
                session?.close()
                activity.finish()
            }
        }
    }

    private class Session(activity: MainActivity, private val theme: Theme) {
        val width = activity.dp(320)
        val height = activity.dp(400)
        val overlay = FrameLayout(activity)
        val anchor = ToolButton(activity, R.drawable.ic_baseline_auto_awesome_24, theme)
        val selected = mutableListOf<AiCandidateAction>()
        var clicks = 0
        lateinit var menu: SlideActionMenu
        private var downTime = 0L

        init {
            overlay.addView(anchor, FrameLayout.LayoutParams(activity.dp(48), activity.dp(48)).apply {
                leftMargin = (width - activity.dp(48)) / 2
                topMargin = activity.dp(240)
            })
            anchor.setOnClickListener { clicks++ }
            anchor.setOnLongClickListener {
                menu = SlideActionMenu(anchor, overlay, theme) { selected += it }
                menu.show()
                true
            }
        }

        fun layout() {
            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            overlay.layout(0, 0, width, height)
        }

        fun open() {
            beginTouch()
            assertTrue(anchor.performLongClick())
            // performLongClick simulates the timeout; discard the separately scheduled timer.
            anchor.cancelGestures()
            layout()
            assertNotNull(menu.root.parent)
        }

        fun beginTouch() {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, anchorCenter())
        }

        fun hasMenu(): Boolean = ::menu.isInitialized

        fun labels(): List<TextView> = descendants(menu.root).filterIsInstance<TextView>()

        fun moveTo(index: Int) = send(MotionEvent.ACTION_MOVE, labelCenter(index))

        fun releaseAt(index: Int) = send(MotionEvent.ACTION_UP, labelCenter(index))

        fun labelCenter(index: Int): Pair<Float, Float> = center(labels()[index])

        fun anchorCenter(): Pair<Float, Float> = center(anchor)

        fun outsideBubble(): Pair<Float, Float> {
            val location = IntArray(2)
            menu.root.getLocationOnScreen(location)
            return location[0] - anchor.context.dp(20).toFloat() to location[1].toFloat()
        }

        fun send(action: Int, point: Pair<Float, Float>) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            // Preserve raw screen coordinates while reporting local coordinates to the button.
            event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
            try {
                assertTrue(anchor.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun addSecondPointer() = sendMultiplePointers(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            anchorCenter(),
        )

        fun sendMultiplePointers(action: Int, point: Pair<Float, Float>) {
            val properties = Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(2) { index ->
                MotionEvent.PointerCoords().apply {
                    x = point.first + index * anchor.context.dp(20)
                    y = point.second
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
            try {
                assertTrue(anchor.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }

        fun shortClick() {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, anchorCenter())
            send(MotionEvent.ACTION_UP, anchorCenter())
        }

        fun close() {
            if (::menu.isInitialized) menu.dismiss()
            anchor.cancelGestures()
        }

        private fun center(view: View): Pair<Float, Float> {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            return location[0] + view.width / 2f to location[1] + view.height / 2f
        }

        private fun descendants(view: View): List<View> = buildList {
            add(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
            }
        }
    }
}
