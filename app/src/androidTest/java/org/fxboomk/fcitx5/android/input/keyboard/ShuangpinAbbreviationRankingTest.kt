/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.allViews
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.flexbox.FlexboxLayoutManager
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinScheme
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.InputView
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fxboomk.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.fxboomk.fcitx5.android.input.font.FontProviderApi
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.testing.RequireDefaultImeRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test

/** Real-dictionary ranking and native candidate identity, with all learning isolated. */
class ShuangpinAbbreviationRankingTest {
    @Test
    fun oneLearnedCompletionCanOutrankTheBestThreeInitialAbbreviation() = withIsolatedLearning { scene ->
        val before = scene.candidates("wum")
        assertEquals("为什么", before.bestAbbreviation().text)
        assertTrue("The initial abbreviation lost its first native page", before.indexOfFirst {
            it.isBestAbbreviation
        } in 0..4)
        assertTrue("The real dictionary lacks the wu+m completion", before.any { it.text == "雾霾" })
        scene.choose("雾霾")

        val learned = scene.candidates("wum")
        assertEquals("One complete selection must affect ranking without a two-use gate", "雾霾", learned.first().text)
        assertFalse("A wu+m completion was misidentified as three initials", learned.first().isAbbreviation)
        assertEquals("为什么", learned.bestAbbreviation().text)
        assertTrue(learned.indexOfFirst { it.text == "雾霾" } < learned.indexOfFirst { it.isBestAbbreviation })
        scene.choose("为什么")
    }

    @Test
    fun aLearnedMultiNodeCompletionCanAdvanceAheadOfChinaPeople() = withIsolatedLearning { scene ->
        val before = scene.candidates("vgr")
        assertEquals("中国人", before.bestAbbreviation().text)
        assertTrue("The real dictionary lacks the zheng+ru path", before.any { it.text == "正如" })
        scene.choose("正如")

        val learned = scene.candidates("vgr")
        val completion = learned.indexOfFirst { it.text == "正如" }
        val abbreviation = learned.indexOfFirst { it.isBestAbbreviation }
        assertTrue("A learned multi-node path was still forced behind the abbreviation: $learned",
            completion >= 0 && abbreviation >= 0 && completion < abbreviation)
        assertEquals("中国人", learned.bestAbbreviation().text)
        assertFalse(learned[completion].isAbbreviation)
        scene.choose("正如")
    }

    @Test
    fun longerEnabledInputsKeepExactlyOneBestAbbreviationIdentity() = withIsolatedLearning { scene ->
        // Pure abbreviations do not need long-word completion. Keeping its
        // normal four-syllable threshold also exercises competing completions.
        for (code in listOf("vgrm", "vgrmd")) {
            val candidates = scene.candidates(code)
            val best = candidates.bestAbbreviation()
            assertTrue("A best abbreviation lost its classification for $code", best.isAbbreviation)
            assertFalse("A normal abbreviation became a recovery candidate", best.isFallback || best.isDeferredFallback)
            val index = candidates.indexOf(best)
            assertEquals("The bridge did not preserve the native candidate identity", index, best.nativeIndex)
            val expanded = scene.api { getExpandedCandidates(0, CANDIDATE_LIMIT, 0).toList() }
            assertEquals("The expanded list duplicated or lost the best abbreviation", 1,
                expanded.count { it.nativeIndex == best.nativeIndex })
            assertTrue("No choices were generated for $code", candidates.isNotEmpty())
        }
    }

    @Test
    fun compactInputIsExcludedAndRecoveryStillUsesItsSecondSlot() = withIsolatedLearning { scene ->
        scene.api {
            setCompact(true)
            for (chr in "wum") {
                val group = Shuangpin18Keys.groups.indexOfFirst { chr in it }
                assertTrue(group >= 0)
                val key = KeySym(0x0200E000 + group)
                sendKey(key, KeyStates.Virtual)
                sendKey(key, KeyStates.Virtual, up = true)
            }
            val compact = allCandidates()
            assertTrue("Grouped input lost its existing abbreviation", compact.any { it.text == "为什么" })
            assertTrue("The ordinary 26-key homepage policy leaked into grouped input",
                compact.none { it.isAbbreviation || it.isBestAbbreviation })
            reset()
            setCompact(false)
            setImConfig(XIAOHE, getImConfig(XIAOHE)["cfg"].deepCopy().apply {
                getOrCreate("Fuzzy").getOrCreate("Correction").value = "QWERTY"
            })
        }
        val candidates = scene.candidates("fqyi")
        val recovery = candidates.withIndex().single { it.value.isFallback }
        assertEquals("Recovery no longer occupies the second native slot", 1, recovery.index)
        assertEquals("非遗", recovery.value.text)
        assertFalse("A recovered two-syllable word was marked as four initials", recovery.value.isAbbreviation)
        assertTrue("The bridge marked multiple best abbreviations", candidates.count { it.isBestAbbreviation } <= 1)
        scene.choose("非遗")
    }

    @Test
    fun narrowLargeTextRowKeepsOnlyTheBestAbbreviationAndSelectsItsNativeIndex() = withIsolatedLearning { scene ->
        scene.candidates("vgr")
        scene.choose("正如")
        withEditor(scene) { ui ->
            ui.type("vgr")
            val native = scene.api { allCandidates() }
            val best = native.bestAbbreviation()
            assertEquals("中国人", best.text)
            ui.awaitRow("one protected abbreviation after the learned completion") {
                val visible = ui.visibleHolders()
                visible.firstOrNull()?.text == "正如" &&
                    visible.count { it.candidate.isAbbreviation } == 1 &&
                    visible.any { it.candidate.isBestAbbreviation && it.candidate.nativeIndex == best.nativeIndex }
            }
            onMain {
                val visible = ui.visibleHolders()
                assertTrue("The best abbreviation did not follow the higher-scoring completion",
                    visible.indexOfFirst { it.text == "正如" } < visible.indexOfFirst { it.candidate.isBestAbbreviation })
                val abbreviation = visible.single { it.candidate.isBestAbbreviation }
                assertEquals(best.nativeIndex, abbreviation.idx)
                assertTrue("The test did not apply the large candidate font",
                    abbreviation.ui.text.textSize >= 45f * abbreviation.ui.root.resources.displayMetrics.scaledDensity)
            }

            val hiddenAbbreviations = native.filter { it.isAbbreviation && !it.isBestAbbreviation }
            val expanded = ui.expand()
            ui.awaitRow("expanded native identities") { expanded.adapter.snapshot().items.isNotEmpty() }
            if (hiddenAbbreviations.isNotEmpty()) {
                ui.awaitRow("deferred abbreviations in the expanded list") {
                    expanded.adapter.snapshot().items.any { item ->
                        hiddenAbbreviations.any { it.nativeIndex == item.nativeIndex }
                    }
                }
            }
            onMain {
                val expandedItems = expanded.adapter.snapshot().items
                assertEquals("Expanded candidates duplicated a native identity", expandedItems.size,
                    expandedItems.map { it.nativeIndex }.distinct().size)
                assertTrue("The already visible best abbreviation was repeated in the expanded list",
                    expandedItems.none { it.nativeIndex == best.nativeIndex })
                ui.windowManager.attachWindow(KeyboardWindow)
            }
            ui.type("vgr")
            ui.awaitRow("the native-mapped best abbreviation after returning from expansion") {
                ui.visibleHolders().any { it.candidate.isBestAbbreviation }
            }
            ui.awaitComposingEditor()
            val flow = scene.api { eventFlow }
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        flow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                onMain {
                    val holder = ui.visibleHolders().single { it.candidate.isBestAbbreviation }
                    assertEquals(holder.candidate.nativeIndex, holder.idx)
                    assertTrue("The real abbreviation candidate view did not accept its click", holder.itemView.performClick())
                }
                assertEquals("中国人", commit.await())
            }
            awaitUi("the exact abbreviation commit in the real editor", { ui.editorDiagnostics() }) {
                ui.editor.text.toString() == "中国人" &&
                    BaseInputConnection.getComposingSpanStart(ui.editor.text) == -1 &&
                    BaseInputConnection.getComposingSpanEnd(ui.editor.text) == -1
            }
        }
    }

    @Test
    fun customShuangpinKeepsRareEnglishOffAutomaticFloatingFirstPage() {
        val scheme = InstrumentationRegistry.getInstrumentation().context.assets
            .open("shuangpin/swan.json").bufferedReader().use { reader ->
                Json.decodeFromString<CustomShuangpinScheme>(reader.readText())
            }
        withIsolatedLearning(customScheme = scheme) { scene ->
            scene.api {
                setImConfig(scene.inputMethodName, getImConfig(scene.inputMethodName)["cfg"].deepCopy().apply {
                    getOrCreate("SpellEnabled").value = "True"
                    getOrCreate("PageSize").value = "6"
                })
                activateIme("keyboard-us")
                activateIme(scene.inputMethodName)
                reset()
                for (chr in "yw") {
                    sendKey(chr, states = KeyStates.Virtual.states)
                    sendKey(chr, states = KeyStates.Virtual.states, up = true)
                }
                assertTrue("The real English dictionary must provide the regression candidate",
                    allCandidates().any { it.text == "ywca" })

                // A horizontal row supplies explicit native IDs; floating mode
                // must restore the automatic policy without promoting rare English.
                val measured = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                assertTrue("The custom scheme must use native viewport paging", measured.foldingEnabled)
                val firstRow = measured.candidates.filter { it.symbolSourceId < 0 && it.text != "ywca" }
                    .take(2).map { it.nativeIndex }.toIntArray()
                assertEquals(2, firstRow.size)
                assertTrue(setCandidateViewportIfCurrent(measured.revision, 2, firstRow, -1))
                val floating = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                assertTrue(setCandidateViewportIfCurrent(floating.revision, 0, intArrayOf(), -2))

                suspend fun currentPage(): FcitxEvent.PagedCandidateEvent.Data = coroutineScope {
                    val next = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(EVENT_TIMEOUT_MS) {
                            eventFlow.filterIsInstance<FcitxEvent.PagedCandidateEvent>().first().data
                        }
                    }
                    setCandidatePagingMode(1)
                    next.await()
                }

                val native = allCandidates()
                val rareEnglish = native.single { it.text == "ywca" }
                var page = currentPage()
                assertFalse("Automatic floating mode did not return to page one", page.hasPrev)
                assertTrue("The floating first page must contain ordinary Chinese choices", page.candidates.isNotEmpty())
                assertTrue("Rare English was promoted into the floating first page: ${page.candidates.toList()}",
                    page.candidates.none { it.text == "ywca" })
                assertEquals("The automatic first page lost its default highlight", 0, page.cursorIndex)
                val visited = mutableSetOf<Int>()
                while (true) {
                    assertTrue("A floating page exceeded the configured capacity", page.candidates.size <= 6)
                    page.candidates.forEach { candidate ->
                        assertTrue("Floating paging repeated a native candidate: $candidate", visited.add(candidate.nativeIndex))
                    }
                    if (!page.hasNext) break
                    offsetCandidatePage(1)
                    page = currentPage()
                    assertTrue("A later floating page lost its continuation state", page.hasPrev)
                }
                assertEquals("Floating paging dropped or invented a native candidate",
                    native.map { it.nativeIndex }.toSet(), visited)
                assertTrue("Rare English must remain selectable on later pages", rareEnglish.nativeIndex in visited)
            }
        }
    }

    @Test
    fun customShuangpinKeepsRareCompletionOffAutomaticFloatingFirstPage() {
        val scheme = InstrumentationRegistry.getInstrumentation().context.assets
            .open("shuangpin/swan.json").bufferedReader().use { reader ->
                Json.decodeFromString<CustomShuangpinScheme>(reader.readText())
            }
        for (symbols in listOf(false, true)) {
            withIsolatedLearning(customScheme = scheme) { scene ->
                scene.api {
                    setImConfig(scene.inputMethodName, getImConfig(scene.inputMethodName)["cfg"].deepCopy().apply {
                        getOrCreate("PageSize").value = "6"
                        getOrCreate("LongWordLengthLimit").value = "3"
                        getOrCreate("SymbolsEnabled").value = if (symbols) "True" else "False"
                    })
                }
                val initial = scene.candidates("wsm")
                assertEquals("为什么", initial.bestAbbreviation().text)
                assertTrue("The real Chinese dictionary must provide the regression completion",
                    initial.any { it.text == "威斯敏斯特" })
                scene.api {
                    val measured = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                    assertTrue("Abbreviation paging must remain enabled with symbols=$symbols", measured.foldingEnabled)
                    val firstRow = measured.candidates.filter { it.symbolSourceId < 0 && it.text != "威斯敏斯特" }
                        .take(2).map { it.nativeIndex }.toIntArray()
                    assertEquals(2, firstRow.size)
                    assertTrue(setCandidateViewportIfCurrent(measured.revision, 2, firstRow, -1))
                    val floating = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                    assertTrue(setCandidateViewportIfCurrent(floating.revision, 0, intArrayOf(), -2))

                    suspend fun currentPage(): FcitxEvent.PagedCandidateEvent.Data = coroutineScope {
                        val next = async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(EVENT_TIMEOUT_MS) {
                                eventFlow.filterIsInstance<FcitxEvent.PagedCandidateEvent>().first().data
                            }
                        }
                        setCandidatePagingMode(1)
                        next.await()
                    }

                    val native = allCandidates()
                    val rareCompletion = native.single { it.text == "威斯敏斯特" }
                    val best = native.bestAbbreviation()
                    var page = currentPage()
                    assertFalse("Automatic floating mode did not return to page one", page.hasPrev)
                    assertTrue("The best abbreviation left the floating first page with symbols=$symbols",
                        page.candidates.any { it.nativeIndex == best.nativeIndex && it.isBestAbbreviation })
                    assertTrue("Rare completion was promoted into the floating first page: ${page.candidates.toList()}",
                        page.candidates.none { it.text == rareCompletion.text })
                    assertEquals("The automatic first page lost its default highlight", 0, page.cursorIndex)
                    val visited = mutableSetOf<Int>()
                    while (true) {
                        assertTrue("A floating page exceeded the configured capacity", page.candidates.size <= 6)
                        page.candidates.forEach { candidate ->
                            assertTrue("Floating paging repeated a native candidate: $candidate", visited.add(candidate.nativeIndex))
                        }
                        if (!page.hasNext) break
                        offsetCandidatePage(1)
                        page = currentPage()
                        assertTrue("A later floating page lost its continuation state", page.hasPrev)
                    }
                    assertEquals("Floating paging dropped or invented a native candidate",
                        native.map { it.nativeIndex }.toSet(), visited)
                    assertTrue("Rare completion must remain available on later pages", rareCompletion.nativeIndex in visited)
                }
                scene.choose("威斯敏斯特")
            }
        }
    }

    @Test
    fun customShuangpinFillsWideRowAndPreservesExpandedNativeIndices() {
        val scheme = InstrumentationRegistry.getInstrumentation().context.assets
            .open("shuangpin/swan.json").bufferedReader().use { reader ->
                Json.decodeFromString<CustomShuangpinScheme>(reader.readText())
            }
        assertEquals("swan", scheme.id)
        assertEquals("*", scheme.zeroInitial)
        withIsolatedLearning(customScheme = scheme) { scene ->
            withEditor(scene, rowWidthDp = 300, fontSizeSp = 20f,
                candidateStyle = HorizontalCandidateMode.AutoFillWidth, maxSpanCount = 6) { ui ->
                ui.type("wm")
                val diagnosticNative = scene.api { allCandidates() }
                val bestText = diagnosticNative.bestAbbreviation().text
                assertTrue("The custom wm fixture has no alternatives", diagnosticNative.any {
                    it.isAbbreviation && !it.isBestAbbreviation
                })
                val stableRow = ui.awaitStableRow("custom wm alternatives fill the row; native=${diagnosticNative.take(24)}") {
                    it.candidates.size > 2 &&
                        it.candidates.any { candidate -> candidate.isAbbreviation && !candidate.isBestAbbreviation } &&
                        it.candidates.any { candidate -> candidate.text == bestText && candidate.isBestAbbreviation }
                }
                // Applying the viewport can rebuild folded symbols and their
                // bulk indices. Compare the displayed row against that live projection.
                val native = scene.api { allCandidates() }
                val best = native.bestAbbreviation()
                val viewport = scene.api { getCandidateViewportSnapshot(native.size) }
                assertEquals("The native projection changed after the stable row was captured", stableRow.revision, viewport.revision)
                var firstRow = emptyList<CandidateWord>()
                onMain {
                    val visible = ui.visibleHolders()
                    val adapter = ui.row.adapter as HorizontalCandidateViewAdapter
                    assertEquals("The bound row changed while reading its native projection", stableRow,
                        ui.boundRowSnapshot(viewport.revision))
                    assertTrue("The final visible best no longer matches the live native identity", visible.any {
                        it.idx == best.nativeIndex && it.text == best.text && it.candidate.isBestAbbreviation
                    })
                    assertEquals("The row hid a candidate after fitting it", adapter.itemCount, visible.size)
                    firstRow = visible.map { it.candidate }
                    assertTrue("A displayed candidate lost its native identity", visible.all {
                        it.idx == it.candidate.nativeIndex && it.idx >= 0
                    })
                    val firstIndices = firstRow.map { it.nativeIndex }.toSet()
                    val next = viewport.candidates.firstOrNull {
                        it.nativeIndex !in firstIndices && it.symbolSourceId < 0 && !it.isDeferredFallback
                    }
                    val dividerWidth = (0 until ui.row.itemDecorationCount).map { ui.row.getItemDecorationAt(it) }
                        .filterIsInstance<FlexboxVerticalDecoration>().sumOf { it.drawable.intrinsicWidth }
                    val usedWidth = visible.sumOf {
                        val params = it.itemView.layoutParams as FlexboxLayoutManager.LayoutParams
                        it.itemView.width + params.leftMargin + params.rightMargin + dividerWidth
                    }
                    val availableWidth = ui.row.width - ui.row.paddingLeft - ui.row.paddingRight
                    val nextWidth = next?.let { candidate ->
                        val holder = adapter.onCreateViewHolder(ui.row, adapter.getItemViewType(0))
                        holder.update(candidate.nativeIndex, candidate)
                        holder.ui.text.typeface = visible.first().ui.text.typeface
                        holder.ui.text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, visible.first().ui.text.textSize)
                        holder.itemView.minimumWidth = maxOf(holder.itemView.minimumWidth, visible.maxOf {
                            (it.itemView.layoutParams as FlexboxLayoutManager.LayoutParams).minWidth
                        })
                        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        holder.itemView.measure(unspecified, unspecified)
                        holder.itemView.measuredWidth + dividerWidth
                    }
                    val metrics = "custom wm row=${firstRow.map { it.text }}; " +
                        "rowWidth=${ui.row.width}; usedWidth=$usedWidth; nextWidth=$nextWidth; revision=${viewport.revision}"
                    Log.i("ShuangpinAbbreviationTest", metrics)
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                        putString("stream", "\n$metrics\n")
                    })
                    assertTrue("The candidate row left enough room for the next ordinary candidate: " +
                        "row=${firstRow.map { it.text }}; available=$availableWidth; used=$usedWidth; " +
                        "next=${next?.text}; nextWidth=$nextWidth",
                        visible.size >= 6 || nextWidth == null || usedWidth + nextWidth > availableWidth)
                }
                val firstIndices = firstRow.map { it.nativeIndex }.toSet()
                val firstHidden = native.firstOrNull { it.nativeIndex !in firstIndices }?.nativeIndex ?: native.size
                val exclusions = firstIndices.filter { it >= firstHidden }.sorted().toIntArray()
                val remaining = scene.api {
                    buildList {
                        do {
                            val page = getExpandedCandidates(firstHidden + size, CANDIDATE_LIMIT, firstHidden, exclusions)
                            addAll(page)
                        } while (page.size == CANDIDATE_LIMIT)
                    }
                }
                // Folded symbol actions may share their source's native index.
                // The ordinary candidate identities must still occur exactly once.
                val baseRemaining = remaining.filter { it.selectionActionId < 0 }
                val visited = firstRow.map { it.nativeIndex } + baseRemaining.map { it.nativeIndex }
                assertEquals("The row/expanded boundary lost a native candidate",
                    native.map { it.nativeIndex }.sorted(), visited.sorted())
                assertEquals("The row/expanded boundary repeated a native candidate", visited.size, visited.distinct().size)
                val expanded = ui.expand()
                ui.awaitRow("expanded custom candidates") { expanded.adapter.snapshot().items.isNotEmpty() }
                onMain {
                    val shown = expanded.adapter.snapshot().items
                    val identities = shown.map { it.nativeIndex to it.selectionActionId }
                    assertEquals("The actual expanded UI started at a different native boundary",
                        remaining.take(shown.size).map { it.nativeIndex to it.selectionActionId }, identities)
                    assertEquals("The expanded UI repeated a candidate or folded action", identities.size, identities.distinct().size)
                    ui.windowManager.attachWindow(KeyboardWindow)
                }
                ui.type("wm")
                val selectableRow = ui.awaitStableRow("a selectable custom wm alternative after expansion") {
                    it.candidates.any { candidate -> candidate.isAbbreviation && !candidate.isBestAbbreviation }
                }
                assertEquals("The native projection changed before selecting the alternative", selectableRow.revision,
                    scene.api { getCandidateViewportSnapshot(1).revision })
                val flow = scene.api { eventFlow }
                var selectedText = ""
                coroutineScope {
                    val commit = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(EVENT_TIMEOUT_MS) {
                            flow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                        }
                    }
                    onMain {
                        assertEquals("The bound alternative changed before its click", selectableRow,
                            ui.boundRowSnapshot(selectableRow.revision))
                        val holder = ui.visibleHolders().first {
                            it.candidate.isAbbreviation && !it.candidate.isBestAbbreviation
                        }
                        selectedText = holder.text
                        assertEquals(holder.candidate.nativeIndex, holder.idx)
                        assertTrue("The custom alternative did not accept its click", holder.itemView.performClick())
                    }
                    assertEquals(selectedText, commit.await())
                }
                awaitUi("the custom alternative committed to the real editor", { ui.editorDiagnostics() }) {
                    ui.editor.text.toString() == selectedText &&
                        BaseInputConnection.getComposingSpanStart(ui.editor.text) == -1 &&
                        BaseInputConnection.getComposingSpanEnd(ui.editor.text) == -1
                }
            }
        }
    }

    private suspend fun withEditor(
        scene: Scene,
        rowWidthDp: Int = 140,
        fontSizeSp: Float = 48f,
        candidateStyle: HorizontalCandidateMode = HorizontalCandidateMode.NeverFillWidth,
        maxSpanCount: Int? = null,
        body: suspend (UiSession) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = AppPrefs.getInstance()
        val originalMode = prefs.candidates.mode.getValue()
        val originalStyle = prefs.keyboard.horizontalCandidateStyle.getValue()
        val originalDynamicHeight = prefs.keyboard.toolbarDynamicHeight.getValue()
        val originalPortraitSpanCount = prefs.keyboard.expandedCandidateGridSpanCount.getValue()
        val originalLandscapeSpanCount = prefs.keyboard.expandedCandidateGridSpanCountLandscape.getValue()
        val originalFontProvider = FontProviders.provider
        var activity: GestureEditingTestActivity? = null
        var ui: UiSession? = null
        scene.usesRealEditor = true
        try {
            onMain {
                prefs.candidates.mode.setValue(FloatingCandidatesMode.InputDevice)
                prefs.keyboard.horizontalCandidateStyle.setValue(candidateStyle)
                maxSpanCount?.let {
                    prefs.keyboard.expandedCandidateGridSpanCount.setValue(it)
                    prefs.keyboard.expandedCandidateGridSpanCountLandscape.setValue(it)
                }
                prefs.keyboard.toolbarDynamicHeight.setValue(true)
                FontProviders.provider = object : FontProviderApi by originalFontProvider {
                    override fun clearCache() = Unit
                    override val fontSizeMap = originalFontProvider.fontSizeMap.toMutableMap().apply {
                        put("cand_font_size", fontSizeSp)
                    }
                }
                FontProviders.markNeedsRefresh()
            }
            activity = instrumentation.startActivitySync(Intent(context, GestureEditingTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GestureEditingTestActivity
            val host = requireNotNull(activity)
            lateinit var editor: EditText
            onMain {
                editor = EditText(host).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
                host.setContentView(editor)
                editor.requestFocus()
            }
            awaitUi("real editor focus") { editor.isShown && editor.hasWindowFocus() }
            onMain { host.getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            awaitUi("real input method view") {
                runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true
            }
            val service = requireNotNull(runningService())
            val inputView = requireNotNull(service.inputView)
            val windowManager = inputView.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
                .get(inputView) as InputWindowManager
            instrumentation.waitForIdleSync()
            scene.api {
                activateIme(scene.inputMethodName)
                setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.ClientSideInputPanel, CapabilityFlag.Sensitive))
                reset()
                setCompact(false)
            }
            scene.awaitRealEditorInputMethod()
            lateinit var row: RecyclerView
            onMain {
                windowManager.attachWindow(KeyboardWindow)
                row = inputView.allViews.filterIsInstance<RecyclerView>()
                    .first { it.adapter is HorizontalCandidateViewAdapter }
            }
            val originalParams = row.layoutParams as LinearLayout.LayoutParams
            ui = UiSession(scene, editor, inputView, row, windowManager, originalParams)
            val width = (rowWidthDp * context.resources.displayMetrics.density).toInt()
            onMain {
                row.layoutParams = LinearLayout.LayoutParams(originalParams).apply { this.width = width; weight = 0f }
                row.requestLayout()
            }
            // Empty composing input hides the candidate strip. Attachment is
            // sufficient here; measurement and visibility are checked once
            // the test types its first code.
            awaitUi("attached real candidate row", {
                "width=${row.width}; visibility=${row.visibility}; shown=${row.isShown}; " +
                    "attached=${row.isAttachedToWindow}; requestedWidth=${row.layoutParams.width}"
            }) { row.isAttachedToWindow && row.layoutParams.width == width }
            body(requireNotNull(ui))
        } finally {
            try {
                scene.api { reset() }
            } finally {
                onMain {
                    ui?.let { session ->
                        session.windowManager.attachWindow(KeyboardWindow)
                        session.row.layoutParams = session.originalRowParams
                        session.row.requestLayout()
                    }
                    activity?.finish()
                    FontProviders.provider = originalFontProvider
                    FontProviders.markNeedsRefresh()
                    prefs.keyboard.toolbarDynamicHeight.setValue(originalDynamicHeight)
                    prefs.keyboard.horizontalCandidateStyle.setValue(originalStyle)
                    prefs.keyboard.expandedCandidateGridSpanCount.setValue(originalPortraitSpanCount)
                    prefs.keyboard.expandedCandidateGridSpanCountLandscape.setValue(originalLandscapeSpanCount)
                    prefs.candidates.mode.setValue(originalMode)
                }
                instrumentation.waitForIdleSync()
                scene.usesRealEditor = false
            }
        }
    }

    private data class CandidateRowSnapshot(
        val revision: Long,
        val width: Int,
        val height: Int,
        val padding: List<Int>,
        val candidates: List<CandidateWord>,
        val indices: List<Int>,
        val bounds: List<Rect>,
        val textSizes: List<Float>,
        val typefaces: List<Typeface?>,
    )

    private class UiSession(
        private val scene: Scene,
        val editor: EditText,
        val inputView: InputView,
        val row: RecyclerView,
        val windowManager: InputWindowManager,
        val originalRowParams: LinearLayout.LayoutParams,
    ) {
        suspend fun type(code: String) {
            scene.api { reset() }
            onMain { editor.setText(""); editor.setSelection(0) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scene.api { reset() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            awaitUi("cleared editor/native composing text", { editorDiagnostics() }) {
                editor.text.isEmpty() && scene.clientPreeditNow().isEmpty()
            }
            scene.awaitRealEditorInputMethod()
            scene.typeRealEditorCode(code)
            awaitRow("visible $code candidates") {
                row.isShown && row.width in 1..(row.layoutParams.width + 2) &&
                    visibleHolders().isNotEmpty()
            }
            awaitComposingEditor()
        }

        suspend fun awaitComposingEditor() = awaitUi("editor synchronized with native composing text", {
            editorDiagnostics()
        }) {
            val client = scene.clientPreeditNow()
            client.isNotEmpty() && editor.text.toString() == client &&
                BaseInputConnection.getComposingSpanStart(editor.text) == 0 &&
                BaseInputConnection.getComposingSpanEnd(editor.text) == editor.text.length
        }

        fun editorDiagnostics(): String =
            "editor='${editor.text}'; selection=${editor.selectionStart}..${editor.selectionEnd}; " +
                "composing=${BaseInputConnection.getComposingSpanStart(editor.text)}.." +
                "${BaseInputConnection.getComposingSpanEnd(editor.text)}; " +
                "clientPreedit='${scene.clientPreeditNow()}'; focused=${editor.hasFocus()}; " +
                "fieldId=${editor.id}; serviceFieldId=${runningService()?.currentInputEditorInfo?.fieldId}"

        fun visibleHolders(): List<CandidateViewHolder> {
            val rect = Rect()
            return (0 until row.childCount).mapNotNull { index ->
                val child = row.getChildAt(index)
                val holder = row.getChildViewHolder(child) as? CandidateViewHolder ?: return@mapNotNull null
                if (child.getLocalVisibleRect(rect) && rect.left <= 0 && rect.top <= 0 &&
                    rect.right >= child.width && rect.bottom >= child.height) holder else null
            }
        }

        fun boundRowSnapshot(revision: Long): CandidateRowSnapshot? {
            if (!row.isShown || !row.isAttachedToWindow || row.isComputingLayout || row.width <= 0) return null
            val adapter = row.adapter as? HorizontalCandidateViewAdapter ?: return null
            val visible = visibleHolders()
            if (visible.isEmpty() || visible.size != adapter.itemCount) return null
            val candidates = adapter.candidates.toList()
            if (visible.indices.any { index ->
                visible[index].candidate != candidates[index] ||
                    visible[index].idx != candidates[index].nativeIndex || visible[index].idx < 0
            }) return null
            return CandidateRowSnapshot(revision, row.width, row.height,
                listOf(row.paddingLeft, row.paddingTop, row.paddingRight, row.paddingBottom),
                candidates, visible.map { it.idx },
                visible.map { Rect(it.itemView.left, it.itemView.top, it.itemView.right, it.itemView.bottom) },
                visible.map { it.ui.text.textSize }, visible.map { it.ui.text.typeface })
        }

        suspend fun awaitStableRow(
            label: String,
            predicate: (CandidateRowSnapshot) -> Boolean,
        ): CandidateRowSnapshot {
            var latest: CandidateRowSnapshot? = null
            var previous: CandidateRowSnapshot? = null
            var consecutive = 0
            var previousLayoutState = ""
            val result = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                var ready: CandidateRowSnapshot? = null
                while (ready == null) {
                    val revisionBefore = scene.api { getCandidateViewportSnapshot(1).revision }
                    onMain {
                        latest = boundRowSnapshot(revisionBefore)
                        val layoutState = layoutDiagnostics()
                        if (layoutState != previousLayoutState) {
                            Log.i("ShuangpinAbbreviationTest", "custom wm layout: $layoutState; " +
                                "bound=${visibleHolders().map { it.idx to it.candidate.text }}")
                            previousLayoutState = layoutState
                        }
                    }
                    val revisionAfter = scene.api { getCandidateViewportSnapshot(1).revision }
                    val captured = latest
                    if (revisionBefore > 0 && revisionBefore == revisionAfter && captured != null && predicate(captured)) {
                        consecutive = if (captured == previous) consecutive + 1 else 1
                        previous = captured
                        if (consecutive >= 3) ready = captured
                    } else {
                        consecutive = 0
                        previous = null
                    }
                    if (ready == null) delay(25)
                }
                ready
            }
            onMain {
                assertTrue("Expected stable $label: ${layoutDiagnostics()}; latest=$latest", result != null)
            }
            return requireNotNull(result)
        }

        fun layoutDiagnostics(): String {
            fun field(name: String): Any? = runCatching {
                RecyclerView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(row)
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
            val adapterQueue = runCatching {
                val helper = requireNotNull(field("mAdapterHelper"))
                helper.javaClass.getDeclaredMethod("hasPendingUpdates").apply { isAccessible = true }.invoke(helper)
            }.getOrElse { "unavailable:${it.javaClass.simpleName}" }
            return "pending=${row.hasPendingAdapterUpdates()}; computingLayout=${row.isComputingLayout}; " +
                "layoutRequested=${row.isLayoutRequested}; firstLayoutComplete=${field("mFirstLayoutComplete")}; " +
                "dataSetChangedAfterLayout=${field("mDataSetHasChangedAfterLayout")}; adapterQueue=$adapterQueue"
        }

        suspend fun awaitRow(label: String, predicate: () -> Boolean) = awaitUi(label, {
            "width=${row.width}; height=${row.height}; requestedWidth=${row.layoutParams.width}; " +
                "visibility=${row.visibility}; shown=${row.isShown}; attached=${row.isAttachedToWindow}; " +
                "${layoutDiagnostics()}; " +
                "adapter=${(row.adapter as? HorizontalCandidateViewAdapter)?.candidates?.toList()}; " +
                "visible=${visibleHolders().map { "${it.candidate}; itemWidth=${it.itemView.width}; textSize=${it.ui.text.textSize}" }}"
        }, predicate)

        suspend fun expand(): BaseExpandedCandidateWindow<*> {
            onMain {
                val button = requireNotNull(inputView.findViewById<View>(R.id.expand_candidate_btn))
                assertTrue("The real candidate expansion button was hidden", button.isShown)
                assertTrue(button.performClick())
            }
            awaitRow("expanded candidate window") { windowManager.currentWindowOrNull() is BaseExpandedCandidateWindow<*> }
            return windowManager.currentWindowOrNull() as BaseExpandedCandidateWindow<*>
        }
    }

    private fun withIsolatedLearning(
        customScheme: CustomShuangpinScheme? = null,
        body: suspend (Scene) -> Unit,
    ) = runBlocking {
        val client = "ShuangpinAbbreviationRankingTest"
        val connection = FcitxDaemon.connect(client)
        var originals: Map<File, ByteArray?>? = null
        var stopped = false
        var originalCustomScheme: CustomShuangpinScheme? = null
        var originalCustomEntries = emptyList<File>()
        try {
            withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
            val storage = InstrumentationRegistry.getInstrumentation().targetContext
                .createDeviceProtectedStorageContext()
            val root = storage.getExternalFilesDir(null) ?: storage.filesDir
            val scene = Scene(connection,
                customScheme?.let { CustomShuangpinSchemeStore.uniqueNameFor(it.id) } ?: XIAOHE,
                customScheme != null)
            // Stop first: the backup must include learning still pending in memory.
            FcitxDaemon.stopFcitx()
            stopped = true
            val configDirectory = File(root, "config/conf/pinyin")
            val learningFiles = listOf("user.history", "user.dict", "shuangpin.history",
                "shuangpin-placement.conf").map { File(root, "data/pinyin/$it") }
            val customFiles = if (customScheme != null) {
                originalCustomScheme = CustomShuangpinSchemeStore.load()
                originalCustomEntries = UserConfigFiles.inputMethodConfDir()?.listFiles().orEmpty().filter {
                    it.isFile && ShuangpinLayoutKeys.isCustomSchemeUniqueName(it.name.removeSuffix(".conf"))
                }
                listOf(UserConfigFiles.customShuangpinSchemeJson(), UserConfigFiles.customShuangpinSpDat(),
                    File(root, "data/inputmethod/${scene.inputMethodName}.conf"),
                    File(configDirectory, "${scene.inputMethodName}.conf")) + originalCustomEntries
            } else emptyList()
            val files = (listOf(File(root, "config/profile"), File(root, "config/conf/pinyin.conf"),
                File(configDirectory, "pinyin.conf"), File(configDirectory, "$XIAOHE.conf")) +
                configDirectory.listFiles().orEmpty().filter { it.isFile } + learningFiles + customFiles).distinct()
            originals = files.associateWith { it.takeIf(File::isFile)?.readBytes() }
            learningFiles.forEach { if (it.exists()) assertTrue("Could not isolate $it", it.delete()) }
            customScheme?.let { CustomShuangpinSchemeStore.save(it).getOrThrow() }
            FcitxDaemon.startFcitx()
            stopped = false
            scene.activate()
            body(scene)
        } finally {
            withContext(NonCancellable) {
                try {
                    originals?.let { saved ->
                        try {
                            if (!stopped) withTimeout(READY_TIMEOUT_MS) {
                                connection.runOnReady { reset(); focus(false); deactivate(TEST_CONTEXT_UID) }
                            }
                        } finally {
                            if (!stopped) FcitxDaemon.stopFcitx()
                            try {
                                try {
                                    originalCustomScheme?.let { CustomShuangpinSchemeStore.save(it).getOrThrow() }
                                } finally {
                                    if (customScheme != null) {
                                        UserConfigFiles.inputMethodConfDir()?.listFiles().orEmpty().filter {
                                            it.isFile && it !in saved &&
                                                ShuangpinLayoutKeys.isCustomSchemeUniqueName(it.name.removeSuffix(".conf"))
                                        }.forEach { assertTrue("Could not remove temporary scheme entry $it", it.delete()) }
                                    }
                                    saved.forEach { (file, bytes) ->
                                        if (bytes != null) {
                                            file.parentFile!!.mkdirs()
                                            file.writeBytes(bytes)
                                            assertArrayEquals("Could not restore $file", bytes, file.readBytes())
                                        } else if (file.exists()) {
                                            assertTrue("Could not remove test-created $file", file.delete())
                                        }
                                    }
                                }
                            } finally {
                                FcitxDaemon.startFcitx()
                                withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                            }
                        }
                    } ?: if (stopped) {
                        FcitxDaemon.startFcitx()
                        withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                    } else Unit
                } finally {
                    FcitxDaemon.disconnect(client)
                }
            }
        }
    }

    private class Scene(
        private val connection: FcitxConnection,
        val inputMethodName: String = XIAOHE,
        private val customWidthFixture: Boolean = false,
    ) {
        var usesRealEditor = false

        fun clientPreeditNow(): String = connection.runImmediately { clientPreeditCached.toString() }

        fun typeRealEditorCode(code: String) {
            check(usesRealEditor)
            // Keep one native transaction, as CandidateExpansionUiTest does:
            // per-key round trips let editor selection callbacks interleave
            // with a partially applied composing update.
            connection.runImmediately {
                assertEquals("The real editor changed input methods before typing", inputMethodName,
                    currentIme().uniqueName)
                code.forEach { sendKey(it.toString(), states = KeyStates.Virtual.states) }
            }
        }

        suspend fun <T> api(block: suspend FcitxAPI.() -> T): T =
            withTimeout(READY_TIMEOUT_MS) { connection.runOnReady(block) }

        suspend fun activate() = api {
            assertTrue(availableIme().any { it.uniqueName == inputMethodName })
            val names = (listOf("keyboard-us") + enabledIme().map { it.uniqueName } + inputMethodName).distinct()
            setEnabledIme(names.toTypedArray())
            activate(TEST_CONTEXT_UID, "shuangpin-abbreviation-ranking-test")
            setCapFlags(CapabilityFlags(CapabilityFlag.Preedit))
            focus()
            activateIme(inputMethodName)
            setImConfig(inputMethodName, getImConfig(inputMethodName)["cfg"].deepCopy().apply {
                getOrCreate("PinyinInPreedit").value = "False"
                getOrCreate("VAsQuickphrase").value = "False"
                getOrCreate("PreeditMode").value = "Composing pinyin"
                getOrCreate("PreeditCursorPositionAtBeginning").value = "False"
                getOrCreate("PageSize").value = "5"
                getOrCreate("Prediction").value = "False"
                getOrCreate("KeepCurrentContext").value = "False"
                getOrCreate("Learning").value = "True"
                getOrCreate("PartialSpLength").value = if (customWidthFixture) "2" else "3"
                getOrCreate("LongWordLengthLimit").value = if (customWidthFixture) "3" else "4"
                getOrCreate("Number of sentence").value = "2"
                getOrCreate("WordCandidateLimit").value = if (customWidthFixture) "22" else "15"
                getOrCreate("ShuangpinInitialFuzzy").value = if (customWidthFixture) "True" else "False"
                getOrCreate("PinyinAnnotation").value = if (customWidthFixture) "Only rare characters" else "Always show"
                for (option in listOf("CloudPinyinEnabled", "SpellEnabled", "SymbolsEnabled",
                    "ChaiziEnabled", "StrokeCandidateEnabled")) getOrCreate(option).value = "False"
                if (customWidthFixture) {
                    getOrCreate("SymbolsEnabled").value = "True"
                    getOrCreate("FoldSymbolCandidates").value = "True"
                }
                getOrCreate("Fuzzy").apply {
                    subItems?.forEach { if (it.value == "True" || it.value == "False") it.value = "False" }
                    if (customWidthFixture) getOrCreate("V_U").value = "True"
                    getOrCreate("Correction").value = if (customWidthFixture) "QWERTY" else "None"
                }
            })
            // Re-enter after setting the per-IM overlay so both the live
            // context and the runtime spell/abbreviation options are explicit.
            activateIme("keyboard-us")
            activateIme(inputMethodName)
            assertEquals("The test context did not activate its input method", inputMethodName, currentIme().uniqueName)
            val effective = getImConfig(inputMethodName)["cfg"]
            assertEquals("The effective configuration still enables English spell candidates", "False",
                effective["SpellEnabled"].value)
            assertEquals("The test abbreviation threshold was not applied", if (customWidthFixture) "2" else "3",
                effective["PartialSpLength"].value)
            assertEquals("The test must permit user learning", "True", effective["Learning"].value)
            reset()
            setCompact(false)
        }

        suspend fun awaitRealEditorInputMethod() {
            check(usesRealEditor)
            // IM subtype notifications and onStartInput enqueue work onto both
            // the main thread and native dispatcher. Drain both before typing;
            // changing to keyboard-us here would enqueue another subtype round.
            var stablePasses = 0
            var observed = ""
            val ready = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (stablePasses < 3) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    val matches = api {
                        if (currentIme().uniqueName != inputMethodName) activateIme(inputMethodName)
                        val current = currentIme().uniqueName
                        val cached = inputMethodEntryCached.uniqueName
                        observed = "native=$current; cached=$cached; empty=${isEmpty()}"
                        assertTrue("The real editor gained input before setup settled: $observed", isEmpty())
                        current == inputMethodName && cached == inputMethodName
                    }
                    stablePasses = if (matches) stablePasses + 1 else 0
                    delay(25)
                }
                true
            } == true
            assertTrue("The real editor input method did not settle before typing: $observed", ready)
        }

        suspend fun candidates(code: String): List<CandidateWord> = api {
            if (!usesRealEditor) {
                // A service editor may gain focus while the daemon restarts
                // between tests. Reclaim only our synthetic test context;
                // the real-row test must keep its EditText connection active.
                activate(TEST_CONTEXT_UID, "shuangpin-abbreviation-ranking-test")
                setCapFlags(CapabilityFlags(CapabilityFlag.Preedit))
                focus()
                activateIme("keyboard-us")
                activateIme(inputMethodName)
            }
            assertEquals("The active input method changed before typing $code", inputMethodName, currentIme().uniqueName)
            assertEquals("English spell candidates must be disabled for the Chinese ranking test", "False",
                getImConfig(inputMethodName)["cfg"]["SpellEnabled"].value)
            val trace = mutableListOf<String>()
            suspend fun record(stage: String) {
                val observed = "$stage: ${diagnostics()}"
                trace += observed
                Log.i("ShuangpinAbbreviationTest", observed)
                assertEquals("The active input method changed while typing $code; $trace", inputMethodName,
                    currentIme().uniqueName)
            }
            reset()
            record("after reset")
            assertTrue("Reset did not clear the preceding composition; $trace", isEmpty())
            for ((index, chr) in code.withIndex()) {
                sendKey(chr, states = KeyStates.Virtual.states)
                sendKey(chr, states = KeyStates.Virtual.states, up = true)
                record("after key ${index + 1} '$chr'")
                if (index == 0 && chr == 'v') {
                    assertFalse("Xiaohe must retain its first zh-initial key; $trace", isEmpty())
                    assertTrue("Xiaohe v did not expose a zh syllable; $trace",
                        inputPanelCached.tabs.any { it.text.startsWith("zh") })
                }
            }
            val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (clientPreeditCached.toString().replace(" ", "") != code) delay(10)
                true
            } == true
            assertTrue("Timed out waiting for raw code '$code'; trace=$trace; final=${diagnostics()}", matched)
            allCandidates()
        }

        suspend fun choose(text: String) = api {
            val candidates = allCandidates()
            val index = candidates.indexOfFirst { it.text == text }
            assertTrue("The current list lost $text: $candidates", index >= 0)
            val nativeIndex = candidates[index].nativeIndex.takeIf { it >= 0 } ?: index
            val before = diagnostics()
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                assertTrue("Selecting '$text' at nativeIndex=$nativeIndex failed; before=$before",
                    select(nativeIndex))
                val committed = commit.await()
                assertTrue("Timed out selecting '$text' at nativeIndex=$nativeIndex; " +
                    "before=$before; final=${diagnostics()}", committed != null)
                assertEquals("Selection did not consume the complete input; nativeIndex=$nativeIndex; " +
                    "before=$before; final=${diagnostics()}", text, committed)
            }
            assertTrue("A complete selection left unconsumed input; ${diagnostics()}", isEmpty())
        }

        private suspend fun FcitxAPI.diagnostics(): String =
            "ime=${currentIme().uniqueName}, empty=${isEmpty()}, " +
                "clientPreedit='$clientPreeditCached', panelPreedit='${inputPanelCached.preedit}', " +
                "auxUp='${inputPanelCached.auxUp}', auxDown='${inputPanelCached.auxDown}', " +
                "tabs=${inputPanelCached.tabs.toList()}, candidates=${getCandidates(0, 12).toList()}"
    }

    companion object {
        @ClassRule @JvmField val defaultIme = RequireDefaultImeRule()
        private const val XIAOHE = "shuangpin-xiaohe"
        private const val TEST_CONTEXT_UID = -260023
        private const val READY_TIMEOUT_MS = 60_000L
        private const val EVENT_TIMEOUT_MS = 10_000L
        private const val CANDIDATE_LIMIT = 512

        private fun onMain(block: () -> Unit) =
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

        private suspend fun awaitUi(
            label: String,
            diagnostics: () -> String = { "" },
            predicate: () -> Boolean,
        ) {
            val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                var done = false
                while (!done) {
                    onMain { done = predicate() }
                    if (!done) delay(25)
                }
                true
            } == true
            onMain { assertTrue("Expected $label: ${diagnostics()}", matched && predicate()) }
        }

        private fun runningService(): FcitxInputMethodService? {
            val type = Class.forName("android.app.ActivityThread")
            val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
            val services = type.getDeclaredField("mServices").apply { isAccessible = true }
                .get(thread) as Map<*, *>
            return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
        }

        private fun RawConfig.deepCopy(): RawConfig =
            copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

        private fun List<CandidateWord>.bestAbbreviation(): CandidateWord {
            val best = filter { it.isBestAbbreviation }
            assertEquals("Expected one best abbreviation: $this", 1, best.size)
            assertTrue("Best abbreviation lost its classification", best.single().isAbbreviation)
            return best.single()
        }

        private suspend fun FcitxAPI.setCompact(compact: Boolean) {
            val key = KeySym(if (compact) 0x0200E101 else 0x0200E100)
            sendKey(key, KeyStates.Virtual)
            sendKey(key, KeyStates.Virtual, up = true)
        }

        private suspend fun FcitxAPI.allCandidates(): List<CandidateWord> {
            val result = mutableListOf<CandidateWord>()
            do {
                val page = getCandidates(result.size, CANDIDATE_LIMIT)
                result.addAll(page)
            } while (page.size == CANDIDATE_LIMIT)
            return result
        }
    }
}
