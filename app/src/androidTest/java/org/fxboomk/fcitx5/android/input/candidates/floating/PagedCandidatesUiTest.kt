/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.floating

import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxEvent.PagedCandidateEvent.Data
import org.fxboomk.fcitx5.android.core.FcitxEvent.PagedCandidateEvent.LayoutHint
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedCandidatesUiTest {

    @Test
    fun aboveCursorReversesVerticalPositionsWhileKeepingLabelsAndHighlight() = onMain {
        val fixture = Fixture()
        val data = page()
        fixture.ui.setWindowAboveCursor(true)
        fixture.ui.update(data, FloatingCandidatesOrientation.Vertical, WIDTH)
        layout(fixture.ui)

        assertVerticalOrder(fixture.ui, data.candidates.size, reversed = true)
        data.candidates.forEachIndexed { index, candidate ->
            assertEquals(candidate.label + candidate.text, candidateText(fixture.ui, index))
            if (index == data.cursorIndex) {
                assertNotNull(fixture.ui.root.getChildAt(index).background)
            } else {
                assertNull(fixture.ui.root.getChildAt(index).background)
            }
        }
    }

    @Test
    fun reversedVisualOrderKeepsClickLongClickAndGestureCandidateIdentity() = onMain {
        val fixture = Fixture()
        val data = page()
        fixture.ui.update(data, FloatingCandidatesOrientation.Vertical, WIDTH)
        fixture.ui.setWindowAboveCursor(true)
        layout(fixture.ui)

        val visualOrder = data.candidates.indices
            .map { fixture.ui.root.getChildAt(it) }
            .sortedBy { it.top }
        visualOrder.forEach { view ->
            assertTrue(view.performClick())
            assertTrue(view.performLongClick())
        }

        assertEquals(listOf(2, 1, 0), fixture.clicks)
        assertEquals(listOf(2 to "third", 1 to "second", 0 to "first"), fixture.actions)
        visualOrder.forEachIndexed { visualIndex, view ->
            val engineIndex = data.candidates.lastIndex - visualIndex
            assertSame(view, fixture.actionViews[visualIndex])
            assertEquals(engineIndex to data.candidates[engineIndex].text, fixture.gestureBindings[view])
        }
    }

    @Test
    fun cursorSideChangesWithIdenticalDataReverseAndRestoreExistingChildren() = onMain {
        val fixture = Fixture()
        val data = page()
        fixture.ui.update(data, FloatingCandidatesOrientation.Vertical, WIDTH)
        layout(fixture.ui)
        val originalChildren = data.candidates.indices.map { fixture.ui.root.getChildAt(it) }
        assertVerticalOrder(fixture.ui, data.candidates.size, reversed = false)

        fixture.ui.setWindowAboveCursor(true)
        fixture.ui.update(data, FloatingCandidatesOrientation.Vertical, WIDTH)
        layout(fixture.ui)
        assertVerticalOrder(fixture.ui, data.candidates.size, reversed = true)

        fixture.ui.setWindowAboveCursor(false)
        fixture.ui.update(data, FloatingCandidatesOrientation.Vertical, WIDTH)
        layout(fixture.ui)
        assertVerticalOrder(fixture.ui, data.candidates.size, reversed = false)
        originalChildren.forEachIndexed { index, view ->
            assertSame(view, fixture.ui.root.getChildAt(index))
        }
    }

    @Test
    fun reversedPaginationMovesAboveCandidatesAndKeepsPreviousNextActions() = onMain {
        val fixture = Fixture()
        val data = page().copy(hasPrev = true, hasNext = true)
        fixture.ui.setWindowAboveCursor(true)
        fixture.ui.update(data, FloatingCandidatesOrientation.Vertical, WIDTH)
        layout(fixture.ui)

        val paginationView = fixture.ui.root.getChildAt(data.candidates.size)
        val pagination = paginationView.tag as PaginationUi
        assertVerticalOrder(fixture.ui, data.candidates.size, reversed = true)
        assertTrue(paginationView.height > 0)
        assertTrue(paginationView.bottom <= fixture.ui.root.getChildAt(data.candidates.lastIndex).top)
        pagination.prevIcon.performClick()
        assertEquals(1, fixture.previousPages)
        assertEquals(0, fixture.nextPages)
        pagination.nextIcon.performClick()
        assertEquals(1, fixture.previousPages)
        assertEquals(1, fixture.nextPages)

        fixture.ui.setWindowAboveCursor(false)
        layout(fixture.ui)
        assertTrue(paginationView.top >= fixture.ui.root.getChildAt(data.candidates.lastIndex).bottom)
    }

    @Test
    fun newPageAndActiveOverridePreserveReversalAndRefreshCandidateActions() = onMain {
        val fixture = Fixture()
        fixture.ui.setWindowAboveCursor(true)
        fixture.ui.update(page(), FloatingCandidatesOrientation.Vertical, WIDTH)
        layout(fixture.ui)
        val firstChild = fixture.ui.root.getChildAt(0)
        val nextPage = page().copy(
            candidates = arrayOf(word(1, "new first"), word(2, "new second")),
            cursorIndex = 0,
            hasPrev = true,
        )
        fixture.ui.update(nextPage, FloatingCandidatesOrientation.Vertical, WIDTH, activeIndexOverride = 1)
        layout(fixture.ui)

        assertVerticalOrder(fixture.ui, nextPage.candidates.size, reversed = true)
        assertSame(firstChild, fixture.ui.root.getChildAt(0))
        assertEquals("1. new first", candidateText(fixture.ui, 0))
        assertEquals("2. new second", candidateText(fixture.ui, 1))
        assertNull(firstChild.background)
        assertNotNull(fixture.ui.root.getChildAt(1).background)
        firstChild.performClick()
        firstChild.performLongClick()
        assertEquals(listOf(0), fixture.clicks)
        assertEquals(listOf(0 to "new first"), fixture.actions)
        assertEquals(0 to "new first", fixture.gestureBindings[firstChild])

        fixture.ui.update(nextPage, FloatingCandidatesOrientation.Vertical, WIDTH, activeIndexOverride = 0)
        layout(fixture.ui)
        assertVerticalOrder(fixture.ui, nextPage.candidates.size, reversed = true)
        assertNotNull(firstChild.background)
        assertNull(fixture.ui.root.getChildAt(1).background)
    }

    @Test
    fun explicitHorizontalLayoutStaysInEngineOrderAboveCursor() = onMain {
        val fixture = Fixture()
        fixture.ui.setWindowAboveCursor(true)
        fixture.ui.update(page(LayoutHint.Vertical), FloatingCandidatesOrientation.Horizontal, WIDTH)
        layout(fixture.ui)

        assertHorizontalOrder(fixture.ui, 3)
        fixture.ui.setWindowAboveCursor(false)
        layout(fixture.ui)
        assertHorizontalOrder(fixture.ui, 3)
    }

    @Test
    fun automaticLayoutReversesForVerticalHintOrNarrowWidthAndRestoresHorizontalRow() = onMain {
        val fixture = Fixture()
        fixture.ui.setWindowAboveCursor(true)
        fixture.ui.update(page(LayoutHint.Vertical), FloatingCandidatesOrientation.Automatic, WIDTH)
        layout(fixture.ui)
        assertVerticalOrder(fixture.ui, 3, reversed = true)

        val unhintedPage = page()
        fixture.ui.update(unhintedPage, FloatingCandidatesOrientation.Automatic, 1)
        layout(fixture.ui)
        assertVerticalOrder(fixture.ui, 3, reversed = true)

        fixture.ui.update(unhintedPage, FloatingCandidatesOrientation.Automatic, WIDTH)
        layout(fixture.ui)
        assertHorizontalOrder(fixture.ui, 3)

        fixture.ui.update(unhintedPage, FloatingCandidatesOrientation.Automatic, 1)
        layout(fixture.ui)
        assertVerticalOrder(fixture.ui, 3, reversed = true)
    }

    private class Fixture {
        val clicks = mutableListOf<Int>()
        val actions = mutableListOf<Pair<Int, String>>()
        val actionViews = mutableListOf<View>()
        val gestureBindings = mutableMapOf<View, Pair<Int, String>>()
        var previousPages = 0
        var nextPages = 0

        val ui = PagedCandidatesUi(
            ctx = instrumentation.targetContext,
            theme = ThemePreset.MaterialLight,
            setupTextView = {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
                setSingleLine()
                setPadding(6, 6, 6, 6)
                minimumHeight = 40
            },
            onCandidateClick = { clicks += it },
            onCandidateAction = { index, text, view ->
                actions += index to text
                actionViews += view
            },
            onBindCandidateGesture = { view, index, text -> gestureBindings[view] = index to text },
            onUnbindCandidateGesture = { gestureBindings.remove(it) },
            onPrevPage = { previousPages++ },
            onNextPage = { nextPages++ },
            highlightRadius = 4f,
        )
    }

    private fun page(layoutHint: LayoutHint = LayoutHint.NotSet) = Data(
        candidates = arrayOf(word(1, "first"), word(2, "second"), word(3, "third")),
        cursorIndex = 1,
        layoutHint = layoutHint,
        hasPrev = false,
        hasNext = false,
    )

    private fun word(number: Int, text: String) = CandidateWord("$number. ", text, "")

    private fun candidateText(ui: PagedCandidatesUi, index: Int): String {
        val item = ui.root.getChildAt(index) as ViewGroup
        val candidate = item.tag as LabeledCandidateItemUi
        return item.allViews.filterIsInstance<TextView>()
            .single { it !== candidate.pinyinText }.text.toString()
    }

    private fun layout(ui: PagedCandidatesUi) {
        ui.root.measure(
            MeasureSpec.makeMeasureSpec(WIDTH, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(4096, MeasureSpec.AT_MOST),
        )
        ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
    }

    private fun assertVerticalOrder(ui: PagedCandidatesUi, count: Int, reversed: Boolean) {
        assertEquals(reversed, ui.isReversed)
        for (index in 0 until count) {
            assertTrue("Each candidate must occupy visible height", ui.root.getChildAt(index).height > 0)
        }
        for (index in 0 until count - 1) {
            val current = ui.root.getChildAt(index)
            val next = ui.root.getChildAt(index + 1)
            assertEquals(current.left, next.left)
            assertTrue(
                "Candidate $index must be ${if (reversed) "below" else "above"} candidate ${index + 1}",
                if (reversed) current.top >= next.bottom else current.bottom <= next.top,
            )
        }
    }

    private fun assertHorizontalOrder(ui: PagedCandidatesUi, count: Int) {
        assertFalse(ui.isReversed)
        for (index in 0 until count - 1) {
            val current = ui.root.getChildAt(index)
            val next = ui.root.getChildAt(index + 1)
            assertTrue(current.width > 0)
            assertEquals(current.top, next.top)
            assertTrue(current.right <= next.left)
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private const val WIDTH = 640
    }
}
