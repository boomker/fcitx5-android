/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateAction
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp

class PreeditScrollingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val longCode = "ni hc bi hj ".repeat(30).trimEnd()

    @Test
    fun longCodeShowsTheNewestCharactersInBothLayouts() = onMain {
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(longCode, compact), compact)
            layout(row)
            val viewport = viewport(row)
            assertEquals(1, codeText(row).lineCount)
            assertTrue("The prefix should move beyond the left edge", viewport.scrollX > 0)
            assertTrue("Long code must receive IME touches", row.hasInteractiveContent)
            assertOffsetVisible(row, codeText(row).text.length)
            assertEquals(if (compact) View.VISIBLE else View.GONE, row.expandButton.visibility)
            if (compact) {
                row.expandButton.performClick()
                layout(row)
                assertOffsetVisible(row, codeText(row).text.length)
                assertTrue(row.expandedSyllables.bottom < row.codeRow.top)
            }
        }
    }

    @Test
    fun draggingCanBrowseEarlierAndLaterCodeInBothLayouts() = onMain {
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(longCode, compact), compact)
            layout(row)
            val viewport = viewport(row)
            val tail = viewport.scrollX
            assertTrue(tail > 0)
            drag(viewport, towardStart = true)
            val earlier = viewport.scrollX
            assertTrue("Dragging right must reveal earlier code", earlier < tail)
            drag(viewport, towardStart = false)
            assertTrue("Dragging left must reveal later code", viewport.scrollX > earlier)
        }
    }

    @Test
    fun newInputReturnsToItsPositionAfterBrowsingThePrefix() = onMain {
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(longCode, compact), compact)
            layout(row)
            viewport(row).scrollTo(0, 0)
            assertEquals(0, viewport(row).scrollX)
            row.update(panel(longCode + "m", compact), compact)
            layout(row)
            assertOffsetVisible(row, codeText(row).text.length)
            assertTrue(viewport(row).scrollX > 0)
        }
    }

    @Test
    fun newInputStopsAnEarlierFlingFromHidingTheNewestCharacters() {
        for (compact in listOf(false, true)) {
            lateinit var ui: PreeditRowUi
            var tail = 0
            onMain {
                ui = row()
                ui.update(panel(longCode, compact), compact)
                layout(ui)
                tail = viewport(ui).scrollX
                assertTrue(tail > 0)
                viewport(ui).fling(-6000)
            }
            repeat(3) {
                SystemClock.sleep(16)
                onMain { viewport(ui).computeScroll() }
            }
            onMain {
                assertTrue("The fling must be moving toward earlier code", viewport(ui).scrollX < tail)
                ui.update(panel(longCode + "m", compact), compact)
                layout(ui)
                assertOffsetVisible(ui, codeText(ui).text.length)
            }
            repeat(5) {
                SystemClock.sleep(16)
                onMain {
                    viewport(ui).computeScroll()
                    assertOffsetVisible(ui, codeText(ui).text.length)
                }
            }
        }
    }

    @Test
    fun repeatedLayoutsAndCandidateUpdatesKeepTheBrowsingPosition() = onMain {
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(longCode, compact), compact)
            layout(row)
            val viewport = viewport(row)
            val browsing = viewport.scrollX / 2
            assertTrue(browsing > 0)
            viewport.scrollTo(browsing, 0)
            repeat(3) {
                layout(row)
                assertEquals(browsing, viewport.scrollX)
            }
            row.update(panel(longCode, compact), compact)
            layout(row)
            assertEquals("An equal preedit refresh must preserve browsing", browsing, viewport.scrollX)
            row.update(panel(longCode, compact).copy(tabs = arrayOf(action(7, "bao"))), compact)
            layout(row)
            assertEquals("Candidate changes must preserve browsing", browsing, viewport.scrollX)
        }
    }

    @Test
    fun resizingTheViewportRevealsTheInputPositionAgain() = onMain {
        for (compact in listOf(false, true)) {
            var keyboardWidth = context.dp(240)
            val row = row { keyboardWidth }
            row.update(panel(longCode, compact), compact)
            layout(row)
            viewport(row).scrollTo(0, 0)
            keyboardWidth = context.dp(120)
            layout(row)
            assertOffsetVisible(row, codeText(row).text.length)
            assertTrue(row.root.width <= keyboardWidth)
            keyboardWidth = context.dp(360)
            layout(row)
            assertOffsetVisible(row, codeText(row).text.length)
            assertTrue(row.root.width <= keyboardWidth)
        }
    }

    @Test
    fun shorteningAndClearingInputResetTheScrollPosition() = onMain {
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(longCode, compact), compact)
            layout(row)
            assertTrue(viewport(row).scrollX > 0)
            row.update(panel("ni", compact), compact)
            layout(row)
            assertEquals(0, viewport(row).scrollX)
            assertFalse(viewport(row).canScrollHorizontally(-1))
            assertFalse(viewport(row).canScrollHorizontally(1))
            assertOffsetVisible(row, codeText(row).text.length)
            row.update(panel(longCode, compact), compact)
            layout(row)
            row.update(panel("", compact), compact)
            layout(row)
            assertFalse(row.visible)
            assertFalse(row.hasInteractiveContent)
            assertEquals(0, viewport(row).scrollX)
        }
    }

    @Test
    fun movingTheCursorFollowsTheInsertionPointInsideLongCode() = onMain {
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(longCode, compact), compact)
            layout(row)
            val tail = viewport(row).scrollX
            val cursor = longCode.length / 3
            row.update(panel(longCode, compact, cursor), compact)
            layout(row)
            assertOffsetVisible(row, cursor)
            assertTrue("The insertion point must be revealed before the tail", viewport(row).scrollX < tail)
            row.update(panel(longCode, compact, cursor = 0), compact)
            layout(row)
            assertOffsetVisible(row, 0)
        }
    }

    @Test
    fun auxiliaryPrefixIsIncludedWhenFollowingThePreeditCursor() = onMain {
        val prefix = "ni hc ".repeat(40)
        val code = "bi hj ".repeat(20).trimEnd()
        val cursor = code.length / 2
        for (compact in listOf(false, true)) {
            val row = row()
            row.update(panel(code, compact, cursor).copy(
                auxUp = FormattedText(arrayOf(prefix), intArrayOf(0), -1)), compact)
            layout(row)
            assertTrue(viewport(row).scrollX > 0)
            assertOffsetVisible(row, prefix.length + cursor)
        }
    }

    private fun row(width: () -> Int = { context.dp(240) }) =
        PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {
            setPadding(context.dp(8), 0, context.dp(8), 0)
        }, availableWidth = width)

    private fun layout(row: PreeditRowUi) {
        row.root.requestLayout()
        row.root.measure(View.MeasureSpec.makeMeasureSpec(context.dp(2000), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.root.layout(0, 0, row.root.measuredWidth, row.root.measuredHeight)
    }

    private fun viewport(row: PreeditRowUi) = row.codeRow.allViews
        .filterIsInstance<HorizontalScrollView>().single { it !== row.syllables }

    private fun codeText(row: PreeditRowUi) = viewport(row).getChildAt(0).allViews
        .filterIsInstance<TextView>().first { it.visibility == View.VISIBLE }

    private fun assertOffsetVisible(row: PreeditRowUi, offset: Int) {
        val viewport = viewport(row)
        val text = codeText(row)
        val textLayout = requireNotNull(text.layout)
        val origin = viewport.getChildAt(0).left + text.left + text.totalPaddingLeft -
            text.scrollX - viewport.scrollX
        val cursorX = origin + textLayout.getPrimaryHorizontal(offset)
        val previousX = origin + textLayout.getPrimaryHorizontal((offset - 1).coerceAtLeast(0))
        assertTrue("The input position is clipped on the left: $cursorX", cursorX >= 0f)
        assertTrue("The input position is clipped on the right: $cursorX", cursorX <= viewport.width)
        assertTrue("The newest character is clipped on the left: $previousX", previousX >= 0f)
        assertTrue("The newest character is clipped on the right: $previousX", previousX <= viewport.width)
    }

    private fun drag(view: View, towardStart: Boolean) {
        val start = view.width * if (towardStart) 0.2f else 0.8f
        val end = view.width * if (towardStart) 0.8f else 0.2f
        val downTime = SystemClock.uptimeMillis()
        send(view, MotionEvent.ACTION_DOWN, downTime, downTime, start)
        repeat(8) { step ->
            send(view, MotionEvent.ACTION_MOVE, downTime, downTime + (step + 1) * 32L,
                start + (end - start) * (step + 1) / 8f)
        }
        // End without a fling so the assertion measures the drag itself.
        send(view, MotionEvent.ACTION_CANCEL, downTime, downTime + 288, end)
    }

    private fun send(view: View, action: Int, downTime: Long, eventTime: Long, x: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, view.height / 2f, 0)
        try {
            assertTrue("The code viewport must accept touches", view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun panel(text: String, compact: Boolean, cursor: Int = text.length) =
        FcitxEvent.InputPanelEvent.Data(
            FormattedText(arrayOf(text), intArrayOf(0), cursor), FormattedText.Empty,
            FormattedText.Empty, if (compact) arrayOf(action(7, "ni"), action(11, "bi")) else emptyArray())

    private fun action(id: Int, text: String) = CandidateAction(id, text, false, "", false, false)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
