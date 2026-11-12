/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateAction
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.candidates.expanded.CandidateTabActionsAdapter
import org.junit.Assert.*
import org.junit.Test
import splitties.dimensions.dp

class PreeditSyllableStripTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun stripOffersEveryPendingLowercaseSyllableAction() = onMain {
        val ni = action(7, "ni")
        val bi = action(11, "bi")
        val unrelated = arrayOf(action(-1, "单字"), action(-2, "笔画"),
            action(-9, "", separator = true), action(12, "W"), action(-13, "return"))
        assertEquals(listOf(ni, bi), preeditSyllableActions(arrayOf(ni, bi) + unrelated, true))
        assertTrue(preeditSyllableActions(arrayOf(ni, bi), false).isEmpty())
        assertEquals(listOf(ni), preeditSyllableActions(arrayOf(ni), true))
    }

    @Test
    fun clicksAdvanceToTheNextSyllableWithoutASelectedState() = onMain {
        val strip = SyllableStrip(context, ThemePreset.MaterialLight)
        val picks = mutableListOf<Pair<CandidateAction, FcitxEvent.InputPanelEvent.Data>>()
        strip.onSelect = { action, panel -> picks += action to panel }
        val original = panel("ni hc", arrayOf(action(7, "ni"), action(11, "bi")))
        strip.update(original, true)
        assertTrue(labels(strip).single { it.text.toString() == "bi" }.performClick())
        assertEquals(11, picks.single().first.id)
        assertEquals(original, picks.single().second)

        val next = panel("bi hc", arrayOf(action(7, "hao"), action(11, "hua")))
        strip.update(next, true)
        val hua = labels(strip).single { it.text.toString() == "hua" }
        assertFalse(hua.isActivated)
        assertFalse(hua.isSelected)
        val info = AccessibilityNodeInfo.obtain()
        try {
            hua.onInitializeAccessibilityNodeInfo(info)
            assertFalse(info.isCheckable)
            assertFalse(info.isChecked)
        } finally {
            info.recycle()
        }
        assertTrue(hua.performClick())
        assertEquals(11, picks.last().first.id)
        assertEquals(next, picks.last().second)
        strip.update(panel("bi hx", emptyArray()), true)
        assertEquals(View.GONE, strip.visibility)
    }

    @Test
    fun aSinglePendingSyllableCanBeConfirmed() = onMain {
        val strip = SyllableStrip(context, ThemePreset.MaterialLight)
        val picks = mutableListOf<Int>()
        strip.onSelect = { action, _ -> picks += action.id }
        strip.update(panel("ni", arrayOf(action(7, "ni"))), true)
        assertEquals(View.VISIBLE, strip.visibility)
        assertTrue(labels(strip).single().performClick())
        assertEquals(listOf(7), picks)
    }

    @Test
    fun legacyCheckableActionsKeepTheirAccessibilityState() = onMain {
        val strip = SyllableStrip(context, ThemePreset.MaterialLight)
        strip.update(panel("bi hc", arrayOf(action(11, "bi", checkable = true, checked = true))), true)
        val bi = labels(strip).single()
        assertTrue(bi.isActivated)
        assertTrue(bi.isSelected)
        val info = AccessibilityNodeInfo.obtain()
        try {
            bi.onInitializeAccessibilityNodeInfo(info)
            assertTrue(info.isCheckable)
            assertTrue(info.isChecked)
        } finally {
            info.recycle()
        }
    }

    @Test
    fun replacedOrHiddenItemsCannotSendAnOldCandidateId() = onMain {
        val strip = SyllableStrip(context, ThemePreset.MaterialLight)
        val ids = mutableListOf<Int>()
        strip.onSelect = { action, _ -> ids += action.id }
        strip.update(panel("ni", arrayOf(action(7, "ni"), action(11, "bi"))), true)
        val oldBi = labels(strip).single { it.text.toString() == "bi" }
        strip.update(panel("hc", arrayOf(action(7, "hao"), action(11, "hua"))), true)
        oldBi.performClick()
        assertTrue(ids.isEmpty())
        val hua = labels(strip).single { it.text.toString() == "hua" }
        strip.update(panel("", arrayOf(action(7, "hao"), action(11, "hua"))), true)
        hua.performClick()
        assertEquals(View.GONE, strip.visibility)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun identicalNextPairLabelsStillInvalidateThePreviousPanel() = onMain {
        val strip = SyllableStrip(context, ThemePreset.MaterialLight)
        val picks = mutableListOf<FcitxEvent.InputPanelEvent.Data>()
        strip.onSelect = { _, panel -> picks += panel }
        val first = panel("ni ni", arrayOf(action(7, "ni"), action(11, "bi")))
        val next = panel("ni ni", arrayOf(action(7, "ni"), action(11, "bi")))
        assertEquals(first, next)
        assertNotSame(first, next)
        strip.update(first, true)
        val oldNi = labels(strip).single { it.text.toString() == "ni" }
        strip.update(next, true)
        oldNi.performClick()
        assertTrue("The previous pair dispatched after an identical new panel", picks.isEmpty())
        labels(strip).single { it.text.toString() == "ni" }.performClick()
        assertSame(next, picks.single())
    }

    @Test
    fun expandedTabsRejectOldBindingsEvenWhenTheNextPairReusesTheirIds() = onMain {
        for (indicatorStyle in listOf(false, true)) {
            val ids = mutableListOf<Int>()
            val adapter = object : CandidateTabActionsAdapter(ThemePreset.MaterialLight, indicatorStyle) {
                override fun onTriggerTabAction(id: Int) { ids += id }
            }
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.updateTabs(listOf(action(11, "bi")))
            adapter.onBindViewHolder(holder, 0)
            assertTrue(holder.itemView.performClick())
            assertEquals(listOf(11), ids)

            adapter.updateTabs(listOf(action(11, "hua")))
            holder.itemView.performClick()
            assertEquals("A binding from the previous pair dispatched its reused ID", listOf(11), ids)
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.performClick()
            assertEquals(listOf(11, 11), ids)

            // A repeated pair may offer exactly the same text and ID.
            adapter.updateTabs(listOf(action(11, "hua")))
            holder.itemView.performClick()
            assertEquals(listOf(11, 11), ids)
            adapter.onBindViewHolder(holder, 0)
            holder.itemView.performClick()
            assertEquals(listOf(11, 11, 11), ids)

            adapter.updateTabs(emptyList())
            holder.itemView.performClick()
            assertEquals(listOf(11, 11, 11), ids)
            adapter.onViewRecycled(holder)
            holder.itemView.performClick()
            assertEquals(listOf(11, 11, 11), ids)
        }
    }

    @Test
    fun longCodeAndSyllablesShareOnlyTheActualKeyboardWidth() = onMain {
        val keyboardWidth = context.dp(240)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        val code = "ni hc ".repeat(40).trimEnd()
        val data = panel(code, arrayOf(action(7, "ni"), action(11, "bi"), action(12, "nuo"),
            action(13, "bo"), action(14, "bu"), action(15, "bing")))
        row.update(data, true)
        row.root.measure(View.MeasureSpec.makeMeasureSpec(context.dp(400), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.root.layout(0, 0, row.root.measuredWidth, row.root.measuredHeight)
        assertTrue(row.root.width <= keyboardWidth)
        assertTrue(row.syllables.width <= (keyboardWidth * PreeditRowUi.MAX_SYLLABLE_SHARE).toInt())
        assertTrue(row.root.width - row.syllables.width > 0)
        assertTrue(labels(row.root).any { it.text.toString() == code })

        row.update(data, false)
        assertEquals(View.GONE, row.syllables.visibility)
        assertTrue(row.visible)
        assertTrue(labels(row.root).any { it.text.toString() == code })
    }

    private fun labels(view: View): List<TextView> = view.allViews.filterIsInstance<TextView>().toList()

    private fun panel(text: String, tabs: Array<CandidateAction>) = FcitxEvent.InputPanelEvent.Data(
        FormattedText(arrayOf(text), intArrayOf(0), text.length), FormattedText.Empty,
        FormattedText.Empty, tabs)

    private fun action(id: Int, text: String, checked: Boolean = false,
                       separator: Boolean = false, checkable: Boolean = false) =
        CandidateAction(id, text, separator, "", checkable, checked)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
