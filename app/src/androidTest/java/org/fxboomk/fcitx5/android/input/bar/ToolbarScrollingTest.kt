/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.bar.ui.IdleUi
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fxboomk.fcitx5.android.input.popup.PopupComponent
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp

class ToolbarScrollingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun overflowingButtonsDragInBothDirectionsWithoutClicking() = withToolbar(12) { session ->
        onMain {
            val list = session.ui.buttonsUi.root
            assertTrue("Buttons must extend beyond the viewport", list.canScrollHorizontally(1))
            assertFalse(list.canScrollVertically(1))
            assertFalse(list.canScrollVertically(-1))
            assertFullyVisible(list, 0)

            drag(list, towardEnd = true)
            assertTrue("Dragging must advance the button strip", list.computeHorizontalScrollOffset() > 0)
            assertEquals(0, session.buttonClicks)

            list.scrollBy(session.activity.dp(2000), 0)
            assertFullyVisible(list, 11)
            assertFalse(list.canScrollHorizontally(1))
            val tailOffset = list.computeHorizontalScrollOffset()
            drag(list, towardEnd = false)
            assertTrue(list.computeHorizontalScrollOffset() < tailOffset)
            assertEquals(0, session.buttonClicks)

            list.scrollBy(-session.activity.dp(2000), 0)
            assertFullyVisible(list, 0)
            assertFalse(list.canScrollHorizontally(-1))
            val first = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
            tap(first)
            assertEquals("A regular tap must still execute its callback", 1, session.buttonClicks)
        }
    }

    @Test
    fun buttonsDistributeEvenlyWhenTheyFit() = withToolbar(3) { session ->
        onMain { assertEvenDistribution(session, 3) }
    }

    @Test
    fun resizingBetweenNarrowAndWideRecalculatesButtonWidths() = withToolbar(3) { session ->
        onMain { assertEvenDistribution(session, 3) }
        session.resize(180)
        onMain {
            val list = session.ui.buttonsUi.root
            assertTrue(list.canScrollHorizontally(1))
            list.children.forEach { assertEquals(session.activity.dp(40), it.width) }
            list.scrollBy(session.activity.dp(2000), 0)
        }
        session.resize(340)
        onMain { assertEvenDistribution(session, 3) }
        session.resize(180)
        onMain {
            val list = session.ui.buttonsUi.root
            assertTrue(list.canScrollHorizontally(-1) || list.canScrollHorizontally(1))
            list.children.forEach { assertEquals(session.activity.dp(40), it.width) }
        }
    }

    @Test
    fun scrollingKeepsEdgeButtonsFixedAndClickable() = withToolbar(12) { session ->
        onMain {
            val ui = session.ui
            val menuBounds = bounds(ui.menuButton)
            val hideBounds = bounds(ui.hideKeyboardButton)
            val menuParent = ui.menuButton.parent
            val hideParent = ui.hideKeyboardButton.parent
            var menuClicks = 0
            var hideClicks = 0
            ui.menuButton.setOnClickListener { menuClicks++ }
            ui.hideKeyboardButton.setOnClickListener { hideClicks++ }

            assertSame(menuParent, hideParent)
            assertTrue(ui.buttonsUi.root.parent !== menuParent)
            assertEquals(0, menuBounds.left)
            assertEquals(ui.root.width, hideBounds.right)
            ui.buttonsUi.root.scrollBy(session.activity.dp(2000), 0)

            assertEquals(menuBounds, bounds(ui.menuButton))
            assertEquals(hideBounds, bounds(ui.hideKeyboardButton))
            assertSame(menuParent, ui.menuButton.parent)
            assertSame(hideParent, ui.hideKeyboardButton.parent)
            tap(ui.menuButton)
            tap(ui.hideKeyboardButton)
            assertEquals(1, menuClicks)
            assertEquals(1, hideClicks)
            assertEquals(0, session.buttonClicks)
        }
    }

    @Test
    fun changingButtonConfigurationRecalculatesOverflowAndExcludesFixedButtons() = withToolbar(12) { session ->
        onMain {
            session.ui.buttonsUi.root.scrollBy(session.activity.dp(2000), 0)
            session.ui.updateConfig(
                buttons(2) + ButtonsLayoutConfig.defaultMoreButton() + ButtonsLayoutConfig.defaultHideKeyboardButton()
            )
        }
        session.layout()
        onMain { assertEvenDistribution(session, 2) }
        onMain {
            session.ui.updateConfig(
                buttons(12) + ButtonsLayoutConfig.defaultMoreButton() + ButtonsLayoutConfig.defaultHideKeyboardButton()
            )
        }
        session.layout()
        onMain {
            val list = session.ui.buttonsUi.root
            assertEquals(12, list.adapter?.itemCount)
            assertTrue(list.canScrollHorizontally(1))
            assertFalse(list.canScrollHorizontally(-1))
            list.scrollBy(session.activity.dp(2000), 0)
            assertFullyVisible(list, 11)
        }
    }

    @Test
    fun offscreenModeButtonsKeepTheirStateWhenFirstScrolledIntoView() = withToolbar(12) { session ->
        onMain {
            session.ui.updateConfig(
                buttons(10) + ConfigurableButton("floating_toggle") + ConfigurableButton("one_handed_keyboard")
            )
        }
        session.layout()
        onMain {
            val list = session.ui.buttonsUi.root
            assertTrue(list.findViewHolderForAdapterPosition(10) == null)
            assertTrue(list.findViewHolderForAdapterPosition(11) == null)
            session.ui.buttonsUi.setFloatingState(true)
            session.ui.buttonsUi.setOneHandKeyboardState(true)
            list.scrollBy(session.activity.dp(2000), 0)

            val floating = requireNotNull(list.findViewHolderForAdapterPosition(10)).itemView as ToolButton
            val oneHanded = requireNotNull(list.findViewHolderForAdapterPosition(11)).itemView as ToolButton
            assertEquals(ThemePreset.MaterialLight.accentKeyBackgroundColor, floating.image.imageTintList?.defaultColor)
            assertEquals(ThemePreset.MaterialLight.accentKeyBackgroundColor, oneHanded.image.imageTintList?.defaultColor)

            session.ui.buttonsUi.setFloatingState(false)
            session.ui.buttonsUi.setOneHandKeyboardState(false)
            assertEquals(ThemePreset.MaterialLight.altKeyTextColor, floating.image.imageTintList?.defaultColor)
            assertEquals(ThemePreset.MaterialLight.altKeyTextColor, oneHanded.image.imageTintList?.defaultColor)
        }
    }

    @Test
    fun fixedOnlyConfigurationIsSafeAndRestoringButtonsKeepsClickAndLongClickCallbacks() = withToolbar(0) { session ->
        fun assertEmptyStrip() = onMain {
            val list = session.ui.buttonsUi.root
            assertEquals(0, list.adapter?.itemCount)
            assertEquals(0, list.childCount)
            assertFalse(list.canScrollHorizontally(-1))
            assertFalse(list.canScrollHorizontally(1))
            assertTrue(session.ui.menuButton.width > 0)
            assertTrue(session.ui.hideKeyboardButton.width > 0)
        }

        assertEmptyStrip()
        onMain { session.ui.updateConfig(buttons(1)) }
        session.layout()
        onMain {
            val button = requireNotNull(session.ui.buttonsUi.root.findViewHolderForAdapterPosition(0)).itemView
            assertTrue(button.performLongClick())
            assertEquals(1, session.buttonLongClicks)
            tap(button)
            assertEquals(1, session.buttonClicks)
            session.ui.updateConfig(
                listOf(ButtonsLayoutConfig.defaultMoreButton(), ButtonsLayoutConfig.defaultHideKeyboardButton())
            )
        }
        session.layout()
        assertEmptyStrip()
    }

    private fun assertEvenDistribution(session: Session, count: Int) {
        val list = session.ui.buttonsUi.root
        assertEquals(count, list.adapter?.itemCount)
        assertEquals(count, list.childCount)
        assertFalse(list.canScrollHorizontally(-1))
        assertFalse(list.canScrollHorizontally(1))
        assertEquals(0, list.computeHorizontalScrollOffset())
        val margins = list.children.sumOf {
            val params = it.layoutParams as RecyclerView.LayoutParams
            params.leftMargin + params.rightMargin
        }
        val expected = (list.width - list.paddingLeft - list.paddingRight - margins) / count
        assertTrue(expected >= session.activity.dp(40))
        list.children.forEach { assertEquals(expected, it.width) }
    }

    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)

    private fun assertFullyVisible(list: RecyclerView, position: Int) {
        val button = requireNotNull(list.findViewHolderForAdapterPosition(position)) {
            "Button $position must be attached at the scroll endpoint"
        }.itemView
        assertTrue("Button $position must not be clipped at the start", button.left >= list.paddingLeft)
        assertTrue("Button $position must not be clipped at the end", button.right <= list.width - list.paddingRight)
    }

    private fun drag(view: View, towardEnd: Boolean) {
        val start = if (towardEnd) view.width * 0.85f else view.width * 0.15f
        val end = if (towardEnd) view.width * 0.15f else view.width * 0.85f
        val time = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, time, time, start)
        for (step in 1..12) {
            send(view, MotionEvent.ACTION_MOVE, time, time + step * 16, start + (end - start) * step / 12)
        }
        // The pause represented in event time avoids a fling obscuring the drag assertion.
        send(view, MotionEvent.ACTION_UP, time, time + 500, end)
        (view as? RecyclerView)?.stopScroll()
    }

    private fun tap(view: View) {
        val time = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, time, time, view.width / 2f)
        send(view, MotionEvent.ACTION_UP, time, time + 16, view.width / 2f)
    }

    private fun send(view: View, action: Int, downTime: Long, eventTime: Long, x: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, view.height / 2f, 0)
        try {
            assertTrue("The toolbar must handle touch events", view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun buttons(count: Int) = List(count) { ConfigurableButton("toolbar_test_$it") }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun withToolbar(count: Int, block: (Session) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var session: Session? = null
        var originalHeight = 0
        try {
            onMain {
                originalHeight = ThemeManager.prefs.toolbarHeight.getValue()
                ThemeManager.prefs.toolbarHeight.setValue(40)
                session = Session(activity, count)
                activity.setContentView(FrameLayout(activity).apply {
                    addView(requireNotNull(session).ui.root, FrameLayout.LayoutParams(activity.dp(340), activity.dp(40)))
                })
            }
            instrumentation.waitForIdleSync()
            val current = requireNotNull(session)
            current.layout()
            onMain { assertTrue(current.ui.buttonsUi.root.isAttachedToWindow) }
            block(current)
        } finally {
            onMain {
                session?.ui?.dispose()
                ThemeManager.prefs.toolbarHeight.setValue(originalHeight)
                activity.finish()
            }
        }
    }

    private inner class Session(val activity: MainActivity, count: Int) {
        val ui = IdleUi(
            activity,
            ThemePreset.MaterialLight,
            PopupComponent(),
            CommonKeyActionListener(),
            buttons(count) + ButtonsLayoutConfig.defaultMoreButton() + ButtonsLayoutConfig.defaultHideKeyboardButton()
        )
        var buttonClicks = 0
        var buttonLongClicks = 0
        private var widthDp = 340

        init {
            buttons(12).forEach { button ->
                ui.buttonsUi.setOnClickListener(button.id) { buttonClicks++ }
                ui.buttonsUi.setOnLongClickListener(button.id) {
                    buttonLongClicks++
                    true
                }
            }
        }

        fun resize(widthDp: Int) {
            this.widthDp = widthDp
            onMain {
                ui.root.layoutParams = ui.root.layoutParams.apply { width = activity.dp(widthDp) }
            }
            layout()
        }

        fun layout() {
            repeat(2) {
                onMain {
                    val width = activity.dp(widthDp)
                    val height = activity.dp(40)
                    ui.root.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                    )
                    ui.root.layout(0, 0, width, height)
                }
                instrumentation.waitForIdleSync()
            }
        }
    }
}
