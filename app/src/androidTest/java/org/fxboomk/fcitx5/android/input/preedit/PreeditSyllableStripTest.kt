/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateAction
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.*
import org.junit.Test
import splitties.dimensions.dp

class PreeditSyllableStripTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun stripOnlyOffersAmbiguousLowercaseSyllableFilters() = onMain {
        val ni = action(7, "ni")
        val bi = action(11, "bi")
        val unrelated = arrayOf(action(-1, "单字"), action(-2, "笔画"),
            action(-9, "", separator = true), action(12, "W"), action(13, "return", checkable = false))
        assertEquals(listOf(ni, bi), preeditSyllableActions(arrayOf(ni, bi) + unrelated, true))
        assertTrue(preeditSyllableActions(arrayOf(ni, bi), false).isEmpty())
        assertTrue(preeditSyllableActions(arrayOf(ni), true).isEmpty())
    }

    @Test
    fun clicksKeepNativeIdsAndSelectionStateAcrossUpdates() = onMain {
        val strip = SyllableStrip(context, ThemePreset.MaterialLight)
        val picks = mutableListOf<Pair<CandidateAction, FcitxEvent.InputPanelEvent.Data>>()
        strip.onSelect = { action, panel -> picks += action to panel }
        val original = panel("ni hc", arrayOf(action(7, "ni"), action(11, "bi")))
        strip.update(original, true)
        assertTrue(labels(strip).single { it.text.toString() == "bi" }.performClick())
        assertEquals(11, picks.single().first.id)
        assertEquals(original, picks.single().second)

        val selected = panel("bi hc", arrayOf(action(7, "ni"), action(11, "bi", checked = true)))
        strip.update(selected, true)
        val bi = labels(strip).single { it.text.toString() == "bi" }
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
        assertTrue(bi.performClick())
        assertEquals(11, picks.last().first.id)
        assertEquals(selected, picks.last().second)
        // The native side receives the same ID again to cancel its filter.
        strip.update(original, true)
        assertFalse(labels(strip).single { it.text.toString() == "bi" }.isActivated)
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
                       separator: Boolean = false, checkable: Boolean = true) =
        CandidateAction(id, text, separator, "", checkable, checked)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
