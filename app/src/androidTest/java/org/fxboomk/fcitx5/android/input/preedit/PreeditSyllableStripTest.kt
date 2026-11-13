/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
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
            assertEquals(AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE,
                AccessibilityNodeInfoCompat.wrap(info).checked)
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
            assertEquals(AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE,
                AccessibilityNodeInfoCompat.wrap(info).checked)
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
    fun fourNarrowCodesAndAFewChoicesStayInlineWhenTheyFit() = onMain {
        var keyboardWidth = context.dp(1000)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        val code = "j'j'k'k"
        row.update(panel(code, arrayOf(action(7, "ji"), action(11, "ju"))), true)
        measureAndLayout(row)
        keyboardWidth = naturalInlineWidth(row) + context.dp(8)
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.syllables.visibility)
        assertSame(row.codeRow, row.syllables.parent)
        assertEquals(View.GONE, row.expandButton.visibility)
        assertEquals(View.GONE, row.expandedSyllables.visibility)
        assertEquals(row.syllables.getChildAt(0).measuredWidth, row.syllables.width)
        assertTrue(labels(row.codeRow).any { it.text.toString() == code })
    }

    @Test
    fun tooManyChoicesUseAnExpandButtonEvenWithOneInputSyllable() = onMain {
        val keyboardWidth = context.dp(240)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        row.update(panel("ni", Array(20) { action(it, "ni") }), true)
        measureAndLayout(row)
        assertTrue(naturalInlineWidth(row) > keyboardWidth)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        assertEquals(context.getString(R.string.expand_syllables), row.expandButton.contentDescription)
        assertEquals(View.GONE, row.expandedSyllables.visibility)
        assertTrue("The collapsed expand button must receive IME touches", row.hasInteractiveContent)
    }

    @Test
    fun exactWidthStaysInlineAndOnePixelLessRequiresExpansion() = onMain {
        var keyboardWidth = context.dp(1000)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        row.update(panel("j'j'k'k", arrayOf(action(7, "ji"), action(11, "ju"))), true)
        measureAndLayout(row)
        val requiredWidth = naturalInlineWidth(row)
        keyboardWidth = requiredWidth
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.syllables.visibility)
        assertEquals(View.GONE, row.expandButton.visibility)
        assertEquals(requiredWidth, row.root.width)

        keyboardWidth = requiredWidth - 1
        row.root.requestLayout()
        measureAndLayout(row)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        assertTrue(row.root.width <= keyboardWidth)
    }

    @Test
    fun aSingleLongChoiceUsesItsFullTextWidthToDecideWhetherToExpand() = onMain {
        val keyboardWidth = context.dp(240)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        val choice = "zhuang".repeat(40)
        row.update(panel("ni", arrayOf(action(7, choice))), true)
        measureAndLayout(row)
        assertTrue(row.syllables.getChildAt(0).measuredWidth > keyboardWidth)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        row.expandButton.performClick()
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.expandedSyllables.visibility)
        assertTrue(labels(row.syllables).any { it.text.toString() == choice })
        assertTrue(row.syllables.width <= keyboardWidth)
    }

    @Test
    fun largerCodeOrSyllableFontsCanRequireExpansionWithoutANewPanel() = onMain {
        for (resizeSyllable in listOf(false, true)) {
            var keyboardWidth = context.dp(1000)
            val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
                availableWidth = { keyboardWidth })
            val code = "j'j'k'k"
            row.update(panel(code, arrayOf(action(7, "zhuang"))), true)
            measureAndLayout(row)
            keyboardWidth = naturalInlineWidth(row)
            measureAndLayout(row)
            assertEquals(View.GONE, row.expandButton.visibility)

            val label = if (resizeSyllable) labels(row.syllables).single()
                else labels(codeViewport(row)).single { it.text.toString() == code }
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, label.textSize * 3)
            row.root.requestLayout()
            measureAndLayout(row)
            assertTrue(naturalInlineWidth(row) > keyboardWidth)
            assertEquals(View.VISIBLE, row.expandButton.visibility)
            assertEquals(View.GONE, row.syllables.visibility)
        }
    }

    @Test
    fun keyboardWidthChangesSwitchPresentationAndResetExpansionWithoutANewPanel() = onMain {
        var keyboardWidth = context.dp(1000)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        row.update(panel("j'j'k'k", arrayOf(action(7, "ji"), action(11, "ju"))), true)
        measureAndLayout(row)
        val requiredWidth = naturalInlineWidth(row)
        assertEquals(View.VISIBLE, row.syllables.visibility)

        keyboardWidth = requiredWidth - 1
        row.root.requestLayout()
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        row.expandButton.performClick()
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.expandedSyllables.visibility)

        keyboardWidth = requiredWidth + 1
        row.root.requestLayout()
        measureAndLayout(row)
        assertEquals(View.GONE, row.expandedSyllables.visibility)
        assertEquals(View.GONE, row.expandButton.visibility)
        assertEquals(View.VISIBLE, row.syllables.visibility)
        assertSame(row.codeRow, row.syllables.parent)

        keyboardWidth = requiredWidth - 1
        row.root.requestLayout()
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        assertEquals(View.GONE, row.expandedSyllables.visibility)
        assertEquals(View.GONE, row.syllables.visibility)
    }

    @Test
    fun expandedSyllablesOccupyTheirOwnRowAboveTheCodeAndCanBeCollapsed() = onMain {
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { context.dp(240) })
        val code = "ni hc ".repeat(40).trimEnd()
        row.update(panel(code, arrayOf(action(7, "ni"), action(11, "bi"))), true)
        measureAndLayout(row)
        val collapsedHeight = row.root.height

        assertTrue(row.expandButton.performClick())
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.syllables.visibility)
        assertEquals(View.VISIBLE, row.expandedSyllables.visibility)
        assertSame(row.expandedSyllables, row.syllables.parent)
        assertNotSame(row.codeRow, row.syllables.parent)
        assertTrue("The syllable panel overlaps the code row", row.expandedSyllables.bottom < row.codeRow.top)
        assertTrue(row.root.height > collapsedHeight)
        assertNull("Each row provides its own surface", row.root.background)
        assertEquals(context.getString(R.string.hide_syllables), row.expandButton.contentDescription)
        assertTrue(labels(row.codeRow).any { it.text.toString() == code })

        assertTrue(row.expandButton.performClick())
        measureAndLayout(row)
        assertEquals(View.GONE, row.expandedSyllables.visibility)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(collapsedHeight, row.root.height)
        assertEquals(context.getString(R.string.expand_syllables), row.expandButton.contentDescription)
    }

    @Test
    fun clearingChoicesOrInputOrDisablingTheStripResetsItsExpansion() = onMain {
        val choices = arrayOf(action(7, "ni"), action(11, "bi"))
        val code = "ni hc ".repeat(40).trimEnd()
        val longPanel = panel(code, choices)
        val resets = listOf(
            panel(code, emptyArray()) to true,
            panel("", choices) to true,
            longPanel to false,
        )
        for ((resetPanel, enabled) in resets) {
            val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
                availableWidth = { context.dp(240) })
            val picks = mutableListOf<Int>()
            row.syllables.onSelect = { action, _ -> picks += action.id }
            row.update(longPanel, true)
            measureAndLayout(row)
            row.expandButton.performClick()
            measureAndLayout(row)
            val oldBi = labels(row.syllables).single { it.text.toString() == "bi" }
            row.update(resetPanel, enabled)
            measureAndLayout(row)
            assertEquals(View.GONE, row.expandedSyllables.visibility)
            assertEquals(View.GONE, row.expandButton.visibility)
            assertEquals(View.GONE, row.syllables.visibility)
            assertEquals("Long code remains draggable after its choices are hidden",
                resetPanel.preedit.isNotEmpty(), row.hasInteractiveContent)
            oldBi.performClick()
            row.expandButton.performClick()
            measureAndLayout(row)
            assertTrue("A hidden choice dispatched its old ID", picks.isEmpty())
            assertEquals(View.GONE, row.expandedSyllables.visibility)

            row.update(longPanel, true)
            measureAndLayout(row)
            assertEquals(View.VISIBLE, row.expandButton.visibility)
            assertEquals(View.GONE, row.expandedSyllables.visibility)
            assertEquals(View.GONE, row.syllables.visibility)
        }
    }

    @Test
    fun expandedChoicesAdvanceWithoutClosingAndRejectOldPanelBindings() = onMain {
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { context.dp(240) })
        val picks = mutableListOf<Pair<CandidateAction, FcitxEvent.InputPanelEvent.Data>>()
        row.syllables.onSelect = { action, panel -> picks += action to panel }
        val first = panel("ni hc ".repeat(40).trimEnd(), arrayOf(action(7, "ni"), action(11, "bi")))
        row.update(first, true)
        measureAndLayout(row)
        row.expandButton.performClick()
        measureAndLayout(row)
        val oldBi = labels(row.syllables).single { it.text.toString() == "bi" }
        oldBi.performClick()
        assertEquals(11, picks.single().first.id)
        assertSame(first, picks.single().second)

        val next = panel("bi hc ".repeat(40).trimEnd(), arrayOf(action(7, "hao"), action(11, "hua")))
        row.update(next, true)
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.expandedSyllables.visibility)
        assertSame(row.expandedSyllables, row.syllables.parent)
        oldBi.performClick()
        assertEquals("The previous pair dispatched its reused ID", 1, picks.size)
        val hua = labels(row.syllables).single { it.text.toString() == "hua" }
        hua.performClick()
        assertEquals(11, picks.last().first.id)
        assertSame(next, picks.last().second)

        row.expandButton.performClick()
        measureAndLayout(row)
        hua.performClick()
        assertEquals("Collapsing left an old choice clickable", 2, picks.size)
    }

    @Test
    fun longCodeGetsTheKeyboardWidthApartFromItsButtonWhileChoicesUseTheRowAbove() = onMain {
        val keyboardWidth = context.dp(240)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        val code = "ni hc ".repeat(40).trimEnd()
        val data = panel(code, arrayOf(action(7, "ni"), action(11, "bi"), action(12, "nuo"),
            action(13, "bo"), action(14, "bu"), action(15, "bing")))
        row.update(data, true)
        measureAndLayout(row)
        assertTrue(row.root.width <= keyboardWidth)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        val viewport = codeViewport(row)
        assertEquals(keyboardWidth - row.expandButton.width, viewport.width)
        assertTrue("The code still reserves space for hidden syllables", viewport.width > keyboardWidth / 2)

        row.expandButton.performClick()
        measureAndLayout(row)
        assertTrue(row.root.width <= keyboardWidth)
        assertEquals(keyboardWidth - row.expandButton.width, viewport.width)
        assertTrue(row.syllables.width <= keyboardWidth)
        assertEquals(minOf(keyboardWidth, row.syllables.getChildAt(0).measuredWidth), row.syllables.width)
        assertTrue(row.expandedSyllables.bottom < row.codeRow.top)
        assertTrue(labels(row.root).any { it.text.toString() == code })

        row.update(data, false)
        measureAndLayout(row)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(View.GONE, row.expandButton.visibility)
        assertEquals(View.GONE, row.expandedSyllables.visibility)
        assertEquals(keyboardWidth, viewport.width)
        assertTrue(row.visible)
        assertTrue(labels(row.root).any { it.text.toString() == code })
    }

    @Test
    fun inlineAndExpandedControlsStayWithinTheKeyboardInRtl() = onMain {
        var keyboardWidth = context.dp(1000)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        row.root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val choices = arrayOf(action(7, "ni"), action(11, "bi"))
        row.update(panel("j'j'k'k", choices), true)
        measureAndLayout(row)
        keyboardWidth = naturalInlineWidth(row) + 1
        measureAndLayout(row)
        assertEquals(0, row.syllables.left)
        assertTrue(row.syllables.right <= row.codeRow.width)
        assertTrue(row.root.width <= keyboardWidth)

        row.update(panel("ni hc ".repeat(40).trimEnd(), choices), true)
        measureAndLayout(row)
        assertEquals(0, row.expandButton.left)
        assertTrue(row.expandButton.right <= row.codeRow.width)
        row.expandButton.performClick()
        measureAndLayout(row)
        assertTrue(row.root.width <= keyboardWidth)
        assertTrue(row.expandedSyllables.left >= 0)
        assertTrue(row.expandedSyllables.right <= row.root.width)
        assertTrue(row.syllables.right <= row.expandedSyllables.width)
        assertTrue(row.expandedSyllables.bottom < row.codeRow.top)
    }

    @Test
    fun repeatedMeasurementsKeepInlineCollapsedAndExpandedViewportsConstrained() = onMain {
        for (overflow in listOf(false, true)) {
            var keyboardWidth = context.dp(1000)
            val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
                availableWidth = { keyboardWidth })
            val code = if (overflow) "ni hc ".repeat(40).trimEnd() else "j'j'k'k"
            val choices = if (overflow) Array(20) { action(it, "ni") }
                else arrayOf(action(7, "ji"), action(11, "ju"))
            row.update(panel(code, choices), true)
            measureAndLayout(row)
            keyboardWidth = if (overflow) context.dp(240) else naturalInlineWidth(row) + context.dp(8)
            measureAndLayout(row)

            for (expanded in if (overflow) listOf(false, true) else listOf(false)) {
                if (expanded) {
                    row.expandButton.performClick()
                    measureAndLayout(row)
                }
                val viewport = codeViewport(row)
                val expectedCodeWidth = viewport.width
                val expectedStripWidth = row.syllables.width
                val expectedRootHeight = row.root.height
                repeat(10) {
                    // Reuse the same panel, keyboard width and parent specs. The natural
                    // size probe must not leave either visible scroll viewport unconstrained.
                    measureAndLayout(row)
                    assertEquals(expectedRootHeight, row.root.height)
                    assertTrue(row.root.width <= keyboardWidth)
                    assertEquals(if (overflow) View.VISIBLE else View.GONE, row.expandButton.visibility)
                    assertEquals(if (expanded) View.VISIBLE else View.GONE, row.expandedSyllables.visibility)
                    assertEquals(if (!overflow || expanded) View.VISIBLE else View.GONE, row.syllables.visibility)
                    assertEquals(expectedCodeWidth, viewport.width)
                    assertEquals(viewport.width, viewport.measuredWidth)
                    assertTrue(viewport.left >= row.codeRow.paddingLeft)
                    assertTrue(viewport.right <= row.codeRow.width - row.codeRow.paddingRight)
                    if (!overflow || expanded) {
                        val parent = if (expanded) row.expandedSyllables else row.codeRow
                        assertSame(parent, row.syllables.parent)
                        assertEquals(expectedStripWidth, row.syllables.width)
                        assertEquals(row.syllables.width, row.syllables.measuredWidth)
                        assertTrue(row.syllables.left >= parent.paddingLeft)
                        assertTrue(row.syllables.right <= parent.width - parent.paddingRight)
                    }
                }
            }
        }
    }

    @Test
    fun exactInlineWidthAccountsForBothRootAndCodeRowPadding() = onMain {
        var keyboardWidth = context.dp(1000)
        val row = PreeditRowUi(context, ThemePreset.MaterialLight, setupTextView = {},
            availableWidth = { keyboardWidth })
        row.root.setPadding(7, 0, 11, 0)
        row.codeRow.setPadding(13, 0, 17, 0)
        row.update(panel("j'j'k'k", arrayOf(action(7, "ji"), action(11, "ju"))), true)
        measureAndLayout(row)
        val requiredWidth = naturalInlineWidth(row) + row.root.paddingLeft + row.root.paddingRight +
            row.codeRow.paddingLeft + row.codeRow.paddingRight

        keyboardWidth = requiredWidth
        measureAndLayout(row)
        assertEquals(View.VISIBLE, row.syllables.visibility)
        assertEquals(View.GONE, row.expandButton.visibility)
        assertEquals(requiredWidth, row.root.width)
        assertTrue(codeViewport(row).left >= row.codeRow.paddingLeft)
        assertTrue(row.syllables.right <= row.codeRow.width - row.codeRow.paddingRight)

        keyboardWidth = requiredWidth - 1
        measureAndLayout(row)
        assertEquals(View.GONE, row.syllables.visibility)
        assertEquals(View.VISIBLE, row.expandButton.visibility)
        assertTrue(row.root.width <= keyboardWidth)
    }

    private fun measureAndLayout(row: PreeditRowUi) {
        row.root.requestLayout()
        row.root.measure(View.MeasureSpec.makeMeasureSpec(context.dp(2000), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.root.layout(0, 0, row.root.measuredWidth, row.root.measuredHeight)
    }

    private fun codeViewport(row: PreeditRowUi) = row.codeRow.allViews
        .filterIsInstance<HorizontalScrollView>().single { it !== row.syllables }

    private fun naturalInlineWidth(row: PreeditRowUi): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val code = codeViewport(row).getChildAt(0)
        val choices = row.syllables.getChildAt(0)
        code.measure(unspecified, unspecified)
        choices.measure(unspecified, unspecified)
        return code.measuredWidth + choices.measuredWidth
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
