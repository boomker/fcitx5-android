/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayoutManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.CandidateBarHeightSettings
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.BaseInputView
import org.fxboomk.fcitx5.android.input.CandidatesView
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.InputView
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import org.fxboomk.fcitx5.android.input.bar.ui.IdleUi
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.candidates.expanded.window.BaseExpandedCandidateWindow
import org.fxboomk.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.fxboomk.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fxboomk.fcitx5.android.input.candidates.floating.PaginationUi
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.fxboomk.fcitx5.android.input.font.FontProviderApi
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.testing.RequireDefaultImeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import splitties.dimensions.dp

/** Exercises the actual candidate row, expanded adapter and click binding. */
class CandidateExpansionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun candidateHeightUpdatesReuseUnchangedFontMetricsDuringTyping() {
        val prefs = AppPrefs.getInstance()
        val dynamicHeight = prefs.keyboard.toolbarDynamicHeight
        val height = ThemeManager.prefs.toolbarHeight
        val profiles = prefs.internal.candidateBarHeightProfiles
        val originals = listOf(dynamicHeight, height, profiles)
            .associateWith { it.sharedPreferences.all[it.key] }
        try {
            withEditor { session ->
                session.type("ni")
                val component = InputView::class.java.getDeclaredField("horizontalCandidate").apply {
                    isAccessible = true
                }.get(session.inputView) as HorizontalCandidateComponent
                val updateHeight = HorizontalCandidateComponent::class.java.getDeclaredMethod(
                    "syncDynamicBarHeight",
                ).apply { isAccessible = true }
                for (dynamic in listOf(false, true)) {
                    onMain { dynamicHeight.setValue(dynamic) }
                    instrumentation.waitForIdleSync()
                    repeat(3) { sample ->
                        val samples = LongArray(16)
                        onMain {
                            repeat(4) { updateHeight.invoke(component) }
                            samples.indices.forEach { index ->
                                val started = System.nanoTime()
                                updateHeight.invoke(component)
                                samples[index] = System.nanoTime() - started
                            }
                        }
                        samples.sort()
                        Log.i("CandidatePerf", "CANDIDATE_HEIGHT_PERF dynamic=$dynamic sample=$sample " +
                            "medianUs=${samples[samples.size / 2] / 1000} " +
                            "p95Us=${samples[samples.size * 95 / 100] / 1000}")
                    }
                }
            }
        } finally {
            onMain {
                originals.forEach { (preference, original) ->
                    val editor = preference.sharedPreferences.edit()
                    when (original) {
                        null -> editor.remove(preference.key)
                        is Int -> editor.putInt(preference.key, original)
                        is Boolean -> editor.putBoolean(preference.key, original)
                        is String -> editor.putString(preference.key, original)
                        else -> error("Unexpected stored preference $original")
                    }
                    assertTrue(editor.commit())
                }
            }
        }
    }

    @Test
    fun largeBodyWithCappedRightPinyinExpandsTheActualDynamicCandidateBarWithoutClipping() {
        val prefs = AppPrefs.getInstance()
        val height = ThemeManager.prefs.toolbarHeight
        val dynamicHeight = prefs.keyboard.toolbarDynamicHeight
        val mode = prefs.candidates.mode
        val profiles = prefs.internal.candidateBarHeightProfiles
        val originals = listOf(height, dynamicHeight, mode, profiles)
            .associateWith { it.sharedPreferences.all[it.key] }
        val originalFontProvider = FontProviders.provider
        try {
            onMain {
                mode.setValue(FloatingCandidatesMode.Disabled)
                FontProviders.provider = object : FontProviderApi by originalFontProvider {
                    override fun clearCache() = Unit
                    override val fontSizeMap = originalFontProvider.fontSizeMap.toMutableMap().apply {
                        put("cand_font_size", 72f)
                        put("cand_comment_font_size", 72f)
                    }
                }
                // Assigning the provider already invalidates caches and bumps the generation.
                // Consume any refresh queued by an earlier run so onStartInputView() does not
                // replace the InputView while this test holds a reference to its row.
                FontProviders.checkAndClearRefreshFlag()
            }
            instrumentation.waitForIdleSync()
            // Changing toolbarHeight is a theme preference change, which makes the service
            // post an InputView replacement. Use a fresh editor session per height so the
            // row inspected below belongs to the InputView that is actually attached.
            for (toolbarHeight in listOf(24, 40)) {
                onMain { height.setValue(toolbarHeight) }
                instrumentation.waitForIdleSync()
                withEditor(prepare = { connection ->
                    connection.runImmediately {
                        setImConfig("pinyin", RawConfig().apply {
                            getOrCreate("PinyinAnnotation").value = "Always show"
                            getOrCreate("PinyinAnnotationPlacement").value = "Right"
                        })
                        CandidateBarHeightSettings.refresh(this)
                    }
                    onMain {
                        height.setValue(toolbarHeight)
                        dynamicHeight.setValue(true)
                    }
                    instrumentation.waitForIdleSync()
                }) { session ->
                    session.type("ni")
                    await("dynamic height for 72sp body with capped right pinyin at ${toolbarHeight}dp", diagnostics = {
                        "height=${session.row.height}, ${session.rowDiagnostics()}"
                    }) {
                        session.visibleHolders().any { it.hasRightAnnotation() } &&
                            session.row.height > toolbarHeight * context.resources.displayMetrics.density + 1f &&
                            !session.row.isLayoutRequested
                    }
                    onMain {
                        val holder = session.visibleHolders().first { it.hasRightAnnotation() }
                        assertEquals("The candidate must retain right-side pinyin", holder.candidate.textWithComment(),
                            holder.ui.text.text.toString())
                        assertEquals(View.GONE, holder.ui.pinyinText.visibility)
                        val annotationPaint = TextPaint().apply {
                            textSize = holder.ui.text.textSize
                            typeface = holder.ui.text.typeface
                        }
                        CandidateAnnotationStyle.span(context, holder.ui.text.textSize)
                            .updateMeasureState(annotationPaint)
                        assertTrue("Annotation size must not exceed half the body size",
                            annotationPaint.textSize <= holder.ui.text.textSize / 2f + 0.01f)
                        assertEquals("Dynamic height must preserve full-size candidate drawing",
                            AutoScaleTextView.Mode.None, holder.ui.text.scaleMode)
                        assertEquals("The large annotation must fit without shrinking",
                            1f, holder.ui.text.textScaleX, 0.01f)
                        val origin = Rect()
                        holder.ui.root.offsetDescendantRectToMyCoords(holder.ui.text, origin)
                        val ink = holder.ui.text.renderedGlyphBounds()
                        assertTrue("Right-side pinyin must fit vertically in its actual candidate: $ink, origin=$origin",
                            origin.top + ink.top >= -1f &&
                                origin.top + ink.bottom <= holder.itemView.height + 1f)
                        assertTrue("Right-side pinyin must fit horizontally in its actual candidate: $ink, origin=$origin",
                            origin.left + ink.left >= -1f &&
                                origin.left + ink.right <= holder.itemView.width + 1f)
                    }
                }
            }
        } finally {
            onMain {
                FontProviders.provider = originalFontProvider
                FontProviders.markNeedsRefresh()
                originals.forEach { (preference, original) ->
                    val editor = preference.sharedPreferences.edit()
                    when (original) {
                        null -> editor.remove(preference.key)
                        is Int -> editor.putInt(preference.key, original)
                        is Boolean -> editor.putBoolean(preference.key, original)
                        is String -> editor.putString(preference.key, original)
                        else -> error("Unexpected preference type for ${preference.key}")
                    }
                    assertTrue("Restore ${preference.key}", editor.commit())
                }
            }
        }
    }

    @Test
    fun topAdaptiveHeightStaysStableAcrossIdleCandidatesPagingCommitAndReset() {
        val prefs = AppPrefs.getInstance()
        val dynamicHeight = prefs.keyboard.toolbarDynamicHeight
        val height = ThemeManager.prefs.toolbarHeight
        val mode = prefs.candidates.mode
        val profiles = prefs.internal.candidateBarHeightProfiles
        // Restore JSON last: restoring the proxy preferences can capture a profile.
        val originals = listOf(dynamicHeight, height, mode, profiles)
            .associateWith { it.sharedPreferences.all[it.key] }
        try {
            onMain { mode.setValue(FloatingCandidatesMode.Disabled) }
            withEditor { session ->
                fun configureAnnotation(value: String) {
                    session.connection.runImmediately {
                        setImConfig("pinyin", RawConfig().apply {
                            getOrCreate("PinyinAnnotation").value = value
                            getOrCreate("PinyinAnnotationPlacement").value = "Top"
                        })
                        CandidateBarHeightSettings.refresh(this)
                    }
                    onMain { dynamicHeight.setValue(true) }
                    instrumentation.waitForIdleSync()
                }
                configureAnnotation("Only rare characters")
                val bar = InputView::class.java.getDeclaredField("kawaiiBar").apply {
                    isAccessible = true
                }.get(session.inputView) as KawaiiBarComponent
                val idleUi = KawaiiBarComponent::class.java.getDeclaredField("_idleUi").apply {
                    isAccessible = true
                }.get(bar) as IdleUi
                var toolbarGeometryChecks = 0
                val adapter = session.row.adapter as HorizontalCandidateViewAdapter
                fun awaitIdle() {
                    await("empty actual candidate bar", diagnostics = { session.rowDiagnostics() }) {
                        adapter.candidates.isEmpty() && session.connection.runImmediately {
                            inputPanelCached.preedit.isEmpty() && clientPreeditCached.isEmpty()
                        } && bar.view.isShown && bar.view.height > 0 && !bar.view.isLayoutRequested
                    }
                }
                session.connection.runImmediately { reset() }
                awaitIdle()
                var expectedHeight = 0
                onMain {
                    assertTrue("Top placement must reserve its annotation line while idle",
                        CandidateBarHeightSettings.annotationOnTop)
                    expectedHeight = bar.view.height
                }
                fun assertStableHeight(state: String) {
                    instrumentation.waitForIdleSync()
                    await("settled top bar after $state", diagnostics = {
                        "bar=" + bar.view.height + ", expected=$expectedHeight, " + session.rowDiagnostics()
                    }) {
                        !bar.view.isLayoutRequested &&
                            (!session.row.isShown || !session.row.isLayoutRequested)
                    }
                    onMain {
                        assertTrue("The same actual InputView must remain attached after $state",
                            session.inputView === session.inputView.service.inputView)
                        assertEquals("Top adaptive bar height must stay stable after $state",
                            expectedHeight, bar.view.height)
                        if (idleUi.root.isShown && idleUi.currentState == IdleUi.State.Toolbar) {
                            val rowHeight = idleUi.root.height
                            val edgeWidth = context.dp(40)
                            val iconSize = context.dp(20)
                            assertEquals(edgeWidth, idleUi.menuButton.width)
                            assertEquals(edgeWidth, idleUi.hideKeyboardButton.width)
                            assertEquals(0, idleUi.menuButton.left)
                            assertEquals((idleUi.hideKeyboardButton.parent as View).width,
                                idleUi.hideKeyboardButton.right)
                            val buttons = listOf(idleUi.menuButton, idleUi.hideKeyboardButton) +
                                idleUi.buttonsUi.root.children.filterIsInstance<ToolButton>().toList()
                            buttons.forEach { button ->
                                assertEquals(rowHeight, button.height)
                                assertEquals(iconSize, button.image.width)
                                assertEquals(iconSize, button.image.height)
                                assertEquals(button.width / 2f,
                                    button.image.left + button.image.width / 2f, 1f)
                                assertEquals(rowHeight / 2f,
                                    button.image.top + button.image.height / 2f, 1f)
                            }
                            toolbarGeometryChecks++
                        }
                    }
                }
                assertStableHeight("idle")
                session.type("ni")
                await("ordinary candidate without annotation", diagnostics = { session.rowDiagnostics() }) {
                    session.visibleHolders().any { it.text == "你" && it.candidate.topPinyinAnnotation == null }
                }
                assertStableHeight("ordinary candidates")

                configureAnnotation("Always show")
                session.type("ni")
                await("actual annotated candidate", diagnostics = { session.rowDiagnostics() }) {
                    session.visibleHolders().any { it.candidate.topPinyinAnnotation != null }
                }
                assertStableHeight("annotated candidates with the same Top structure")
                var beforePage = emptyList<Int>()
                onMain { beforePage = session.visibleHolders().map { it.candidate.nativeIndex } }
                session.connection.runImmediately { offsetCandidatePage(1) }
                await("actual second candidate page", diagnostics = { session.rowDiagnostics() }) {
                    val indices = session.visibleHolders().map { it.candidate.nativeIndex }
                    indices.isNotEmpty() && indices != beforePage
                }
                assertStableHeight("paging")
                val component = InputView::class.java.getDeclaredField("horizontalCandidate").apply {
                    isAccessible = true
                }.get(session.inputView) as HorizontalCandidateComponent
                val applyingViewport = HorizontalCandidateComponent::class.java.getDeclaredField("viewportApplying").apply {
                    isAccessible = true
                }
                await("candidate ready for actual commit", diagnostics = { session.rowDiagnostics() }) {
                    !applyingViewport.getBoolean(component) && !session.row.hasPendingAdapterUpdates() &&
                        !session.row.isLayoutRequested && !session.row.isComputingLayout &&
                        session.visibleHolders().any {
                            it.bindingAdapterPosition in adapter.candidates.indices &&
                                it.candidate == adapter.candidates[it.bindingAdapterPosition] && it.idx >= 0
                        }
                }
                lateinit var candidate: CandidateViewHolder
                onMain {
                    candidate = session.visibleHolders().first {
                        it.bindingAdapterPosition in adapter.candidates.indices &&
                            it.candidate == adapter.candidates[it.bindingAdapterPosition] && it.idx >= 0
                    }
                }
                session.connection.runImmediately {
                    assertFalse("The paged candidate must still belong to an active composition", isEmpty())
                }
                session.expectCommit(candidate.text, candidate.itemView)
                awaitIdle()
                assertStableHeight("commit and cleared candidates")
                session.type("xing")
                assertStableHeight("another composition")
                session.connection.runImmediately { reset() }
                awaitIdle()
                assertStableHeight("reset and cleared candidates")
                assertTrue("Verify visible toolbar geometry at idle, after commit and after reset",
                    toolbarGeometryChecks >= 3)
            }
        } finally {
            onMain {
                originals.forEach { (preference, original) ->
                    val editor = preference.sharedPreferences.edit()
                    when (original) {
                        null -> editor.remove(preference.key)
                        is Int -> editor.putInt(preference.key, original)
                        is Boolean -> editor.putBoolean(preference.key, original)
                        is String -> editor.putString(preference.key, original)
                        else -> error("Unexpected stored preference $original")
                    }
                    assertTrue(editor.commit())
                }
            }
        }
    }

    @Test
    fun topPinyinAlignsActualHorizontalAndFlexboxRowsWithDynamicHeightDisabled() {
        val prefs = AppPrefs.getInstance()
        val height = ThemeManager.prefs.toolbarHeight
        val dynamicHeight = prefs.keyboard.toolbarDynamicHeight
        val mode = prefs.candidates.mode
        val expandedStyle = prefs.keyboard.expandedCandidateStyle
        val profiles = prefs.internal.candidateBarHeightProfiles
        val originals = listOf(height, dynamicHeight, mode, expandedStyle, profiles)
            .associateWith { it.sharedPreferences.all[it.key] }
        val manualHeight = KawaiiBarComponent.MAX_TOOLBAR_HEIGHT
        try {
            onMain {
                // Reserve enough manual space for both lines while adaptation remains off.
                height.setValue(manualHeight)
                dynamicHeight.setValue(false)
                mode.setValue(FloatingCandidatesMode.Disabled)
                expandedStyle.setValue(ExpandedCandidateStyle.Flexbox)
                profiles.setValue("")
            }
            instrumentation.waitForIdleSync()
            withEditor { session ->
                session.connection.runImmediately {
                    setImConfig("pinyin", RawConfig().apply {
                        getOrCreate("PinyinAnnotation").value = "Automatic"
                        getOrCreate("PinyinAnnotationPlacement").value = "Top"
                    })
                    CandidateBarHeightSettings.refresh(this)
                }
                onMain { dynamicHeight.setValue(false) }
                session.type("xing")
                await("actual mixed candidate row with top pinyin", diagnostics = { session.rowDiagnostics() }) {
                    val holders = session.visibleHolders()
                    holders.any { it.candidate.topPinyinAnnotation != null } &&
                        holders.any { it.candidate.topPinyinAnnotation == null } &&
                        kotlin.math.abs(session.row.height - manualHeight * context.resources.displayMetrics.density) <= 1f
                }
                onMain {
                    val holders = session.visibleHolders()
                    assertFalse("Top placement must respect disabled adaptive height", dynamicHeight.getValue())
                    assertCandidateBodiesAligned(session.row, holders)
                    holders.filter { it.candidate.topPinyinAnnotation != null }.forEach { holder ->
                        assertEquals(holder.candidate.topPinyinAnnotation, holder.ui.pinyinText.text.toString())
                        assertTrue(holder.ui.pinyinText.renderedGlyphBounds().top >= -1f)
                    }
                    captureCandidateView(session.row, "pinyin-aligned-horizontal-row.png")
                }

                val expanded = session.expand()
                lateinit var recycler: RecyclerView
                onMain {
                    recycler = session.inputView.allViews.filterIsInstance<RecyclerView>()
                        .first { it.adapter === expanded.adapter }
                }
                fun mixedRow(): List<CandidateViewHolder> {
                    val holders = (0 until recycler.childCount).mapNotNull {
                        recycler.getChildViewHolder(recycler.getChildAt(it)) as? CandidateViewHolder
                    }
                    return holders.filter { it.candidate.topPinyinAnnotation != null }.firstNotNullOfOrNull { top ->
                        holders.filter {
                            it.itemView.top < top.itemView.bottom && it.itemView.bottom > top.itemView.top
                        }.takeIf { row -> row.any { it.candidate.topPinyinAnnotation == null } }
                    }.orEmpty()
                }
                await("actual mixed expanded Flexbox row") {
                    (recycler.layoutManager as? FlexboxLayoutManager)?.alignItems == AlignItems.STRETCH &&
                        !recycler.isLayoutRequested &&
                        mixedRow().size >= 2
                }
                onMain {
                    captureCandidateView(recycler, "pinyin-aligned-expanded-flexbox.png")
                    assertCandidateBodiesAligned(recycler, mixedRow())
                    for (index in 0 until recycler.childCount) {
                        val holder = recycler.getChildViewHolder(recycler.getChildAt(index)) as? CandidateViewHolder
                        holder?.ui?.setFontScale(2f)
                    }
                }
                instrumentation.waitForIdleSync()
                await("large-font mixed expanded Flexbox row") {
                    !recycler.isLayoutRequested && mixedRow().size >= 2
                }
                onMain {
                    assertCandidateBodiesAligned(recycler, mixedRow())
                    session.wm.attachWindow(KeyboardWindow)
                }
                session.connection.runImmediately {
                    setImConfig("pinyin", RawConfig().apply { getOrCreate("PinyinAnnotationPlacement").value = "Right" })
                    CandidateBarHeightSettings.refresh(this)
                }
                onMain { dynamicHeight.setValue(false) }
                session.type("xing")
                await("restored fixed row height after leaving top placement", diagnostics = { session.rowDiagnostics() }) {
                    session.visibleHolders().none { it.candidate.topPinyinAnnotation != null } &&
                        kotlin.math.abs(session.row.height - manualHeight * context.resources.displayMetrics.density) <= 1f
                }
            }
        } finally {
            onMain {
                originals.forEach { (preference, original) ->
                    val editor = preference.sharedPreferences.edit()
                    when (original) {
                        null -> editor.remove(preference.key)
                        is Int -> editor.putInt(preference.key, original)
                        is Boolean -> editor.putBoolean(preference.key, original)
                        is String -> editor.putString(preference.key, original)
                        else -> error("Unexpected preference type for ${preference.key}")
                    }
                    assertTrue("Restore ${preference.key}", editor.commit())
                }
            }
        }
    }

    private fun assertCandidateBodiesAligned(root: ViewGroup, holders: List<CandidateViewHolder>) {
        assertTrue("The test needs a mixed row", holders.size >= 2)
        fun baseline(text: AutoScaleTextView): Int {
            val origin = Rect()
            root.offsetDescendantRectToMyCoords(text, origin)
            return origin.top + text.measuredBaseline()
        }
        val expectedSize = holders.first().ui.text.textSize
        val expectedBaseline = baseline(holders.first().ui.text)
        val expectedHeight = holders.first().itemView.height
        val expectedTop = holders.first().itemView.top
        holders.forEach {
            assertEquals("${it.text} must share the row height", expectedHeight.toFloat(),
                it.itemView.height.toFloat(), 1f)
            assertEquals("${it.text} must start in the same row", expectedTop.toFloat(),
                it.itemView.top.toFloat(), 1f)
            assertEquals("${it.text} must keep the row font size", expectedSize, it.ui.text.textSize, 0.01f)
            assertEquals("${it.text} must keep the row body baseline", expectedBaseline.toFloat(),
                baseline(it.ui.text).toFloat(), 1f)
            assertEquals("${it.text} must not shrink its body", 1f, it.ui.text.textScaleX, 0.01f)
            val origin = Rect()
            it.ui.root.offsetDescendantRectToMyCoords(it.ui.text, origin)
            val ink = it.ui.text.renderedGlyphBounds()
            assertTrue("${it.text} body must fit vertically inside its candidate: $ink, origin=$origin",
                origin.top + ink.top >= -1f && origin.top + ink.bottom <= it.itemView.height + 1f)
        }
    }

    private fun captureCandidateView(view: View, filename: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.cacheDir, filename).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test
    fun hiddenSourceEmojisRenderTogetherAndTapTheMappedChoice() = withEditor { session ->
        session.type("cha")
        session.constrainRow((96 * context.resources.displayMetrics.density).toInt())
        session.type("cha")
        onMain {
            assertFalse("叉 must be outside the actual horizontal row", session.visibleTexts().contains("叉"))
        }
        val expanded = session.expand()
        assertChaGroups(session, expanded, sourceMustBeExpanded = true)
        val alternative = session.expandedHolder(expanded, "❎")
        onMain {
            assertEquals("❎", alternative.ui.text.text.toString())
            assertEquals(alternative.candidate.nativeIndex, alternative.idx)
        }
        session.capture("emoji-expansion-ui.png")
        // Screenshot capture can yield while Paging replaces a bound holder.
        session.expectCommit("❎", session.expandedHolder(expanded, "❎").itemView)
    }

    @Test
    fun naturalWidthKeepsOneEmojiAndDeferredChaGroupsContiguous() = withEditor { session ->
        session.type("cha")
        onMain {
            assertTrue("The actual first page must have at most one emoji",
                session.visibleTexts().count { it in CHA_SYMBOLS } <= 1)
        }
        val expanded = session.expand()
        assertChaGroups(session, expanded, sourceMustBeExpanded = false)
        val alternative = session.expandedHolder(expanded, "❎")
        onMain {
            assertEquals(alternative.candidate.textWithComment(), alternative.ui.text.text.toString())
        }
        session.capture("emoji-expansion-natural-ui.png")
        session.expectCommit("❎", alternative.itemView)
    }

    @Test
    fun visibleSourceBeyondPageUnfoldsButFirstPageHeadKeepsItsPopup() = withEditor { session ->
        session.type("kaishi")
        await("normal first-page folded head") {
            session.visibleHolders().any { it.text == "▶️" }
        }
        var sourceWidth = 0
        onMain {
            val source = session.visibleHolders().first { it.text == "开始" }
            sourceWidth = source.itemView.width + session.row.paddingLeft + session.row.paddingRight + 1
        }
        session.constrainRow(sourceWidth)
        session.type("kaishi")
        await("source visible and head outside actual first page", diagnostics = { session.rowDiagnostics() }) {
            val values = session.visibleTexts()
            "开始" in values && "▶️" !in values
        }
        val expanded = session.expand()
        await("expanded unfolded start group") {
            val values = expanded.adapter.snapshot().items.map { it.text }
            val head = values.indexOf("▶️")
            head >= 0 && values.getOrNull(head + 1) == "🔛"
        }
        val head = session.expandedHolder(expanded, "▶️")
        onMain {
            assertEquals("▶️(开始)", head.candidate.textWithComment())
            assertEquals("▶️(开始)", head.ui.text.text.toString())
            head.ui.root.performLongClick()
        }
        SystemClock.sleep(250)
        instrumentation.waitForIdleSync()
        onMain {
            assertFalse("An unfolded expanded head must not open a folded-choice popup",
                session.popup()?.isShowing == true)
            session.dismissOrdinaryMenu()
        }
        session.capture("emoji-expansion-source-visible-ui.png")

        onMain {
            session.wm.attachWindow(KeyboardWindow)
            session.restoreRow()
        }
        session.type("kaishi")
        await("restored normal first-page head and gestures") {
            session.visibleHolders().any { it.text == "▶️" && it.ui.root.isLongClickable }
        }
        onMain {
            val foldedHead = session.visibleHolders().first { it.text == "▶️" }
            assertEquals("…", foldedHead.candidate.comment)
            assertEquals("▶️…", foldedHead.ui.text.text.toString())
        }
        await("restored first-page long press", diagnostics = { session.rowDiagnostics() }) {
            session.visibleHolders().firstOrNull { it.text == "▶️" }?.ui?.root?.performLongClick() == true
        }
        await("real first-page folded popup") {
            session.popup()?.isShowing == true && session.popupLabels().any {
                it.text.toString() == "🔛" && it.isShown && it.width > 0 && it.height > 0
            }
        }
        lateinit var option: TextView
        onMain {
            option = session.popupLabels().first { it.text.toString() == "🔛" }
            assertNotNull(option)
        }
        SystemClock.sleep(200)
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue("The folded popup must remain visible after rendering", session.popup()?.isShowing == true)
            assertTrue("The folded alternative must be visible", option.isShown && option.width > 0 && option.height > 0)
        }
        session.capture("emoji-first-page-folded-popup-ui.png")
        session.expectCommit("🔛", option)
    }

    @Test
    fun floatingUsesFixedPagesAndKeepsDeferredChaChoicesContiguous() = withEditor { session ->
        val mode = AppPrefs.getInstance().candidates.mode
        val preferences = mode.sharedPreferences
        val originalPresent = preferences.contains(mode.key)
        val originalRawValue = preferences.getString(mode.key, null)
        try {
            onMain { mode.setValue(FloatingCandidatesMode.InputDevice) }
            instrumentation.waitForIdleSync()
            session.connection.runImmediately {
                setImConfig("pinyin", RawConfig().apply { getOrCreate("PageSize").value = "3" })
                assertEquals("3", getImConfig("pinyin")["cfg"]["PageSize"].value)
            }
            session.type("cha")
            val initialFloating = session.floatingView()
            await("horizontal viewport owner") {
                session.inputView.handleEvents && !initialFloating.handleEvents && session.row.isShown
            }
            onMain { mode.setValue(FloatingCandidatesMode.Always) }
            session.type("cha")
            val floating = session.floatingView()
            await("floating viewport owner", diagnostics = {
                "mode=${mode.getValue()}, floatingEvents=${floating.handleEvents}, " +
                    "floatingShown=${floating.isShown}, ${session.rowDiagnostics()}"
            }) {
                mode.getValue() == FloatingCandidatesMode.Always && floating.handleEvents &&
                    floating.isShown && !session.row.isShown
            }
            var automaticAccepted = false
            await("configured native floating first page", diagnostics = { session.floatingPage(floating).toString() }) {
                if (!automaticAccepted) {
                    automaticAccepted = session.connection.runImmediately {
                        val snapshot = getCandidateViewportSnapshot(128)
                        snapshot.foldingEnabled &&
                            setCandidateViewportIfCurrent(snapshot.revision, 0, intArrayOf(), -2)
                    }
                }
                val snapshot = session.connection.runImmediately { getCandidateViewportSnapshot(128) }
                val page = session.floatingPage(floating)
                automaticAccepted && snapshot.foldingEnabled && !page.hasPrev &&
                    page.candidates.isNotEmpty() && page.candidates.size <= 3 &&
                    page.candidates.map { it.nativeIndex } == snapshot.candidates.take(3).map { it.nativeIndex }
            }
            val snapshot = session.connection.runImmediately { getCandidateViewportSnapshot(128) }
            val firstPage = session.floatingPage(floating)
            onMain {
                val symbols = snapshot.candidates.filter { it.symbolSourceId >= 0 }.map { it.nativeIndex }.toSet()
                assertTrue("The fixed first page must contain at most one native symbol",
                    firstPage.candidates.count { it.nativeIndex in symbols } <= 1)
            }
            assertFloatingPage(session, floating, firstPage)
            session.capture("emoji-floating-first-page-ui.png")

            val deferredNative = snapshot.candidates.drop(firstPage.candidates.size)
            val red = deferredNative.indexOfFirst { it.text == "❌" }
            val green = deferredNative.indexOfFirst { it.text == "❎" }
            assertTrue("Tea must remain available in the fixed-page native sequence",
                snapshot.candidates.any { it.text == "🍵" })
            assertTrue("The fixture must defer the 叉 group after the fixed first page", red >= 0)
            assertEquals("Native deferred 叉 choices must be contiguous", red + 1, green)
            val nativeChoices = listOf(deferredNative[red], deferredNative[green])
            assertEquals("The deferred choices must retain distinct native IDs", 2,
                nativeChoices.map { it.nativeIndex }.toSet().size)
            session.connection.runImmediately {
                nativeChoices.forEach { candidate ->
                    assertEquals(-1, candidate.selectionActionId)
                    assertNull(candidate.actionSourceText)
                    assertFalse(candidate.comment.contains("…"))
                    assertTrue(getCandidateActions(candidate.nativeIndex).none { it.icon == "folded-symbol" })
                }
            }

            val deferredPages = mutableListOf<CandidateWord>()
            var page = firstPage
            var pageNumber = 1
            while (deferredPages.none { it.text == "❎" }) {
                assertTrue("The fixed pages must retain the deferred 叉 group", page.hasNext)
                session.nextFloatingPage(floating)
                page = session.floatingPage(floating)
                pageNumber++
                assertFloatingPage(session, floating, page)
                assertTrue("Every later page must be marked as a continuation", page.hasPrev)
                page.candidates.forEach { candidate ->
                    assertFalse("Only first-page symbols may retain fold markers", candidate.comment.contains("…"))
                }
                deferredPages.addAll(page.candidates)
                assertEquals("Floating pagination must preserve the native deferred sequence",
                    deferredNative.take(deferredPages.size).map { it.nativeIndex },
                    deferredPages.map { it.nativeIndex })
                if (pageNumber == 2) session.capture("emoji-floating-second-page-ui.png")
            }
            val displayedRed = deferredPages.indexOfFirst { it.text == "❌" }
            val displayedGreen = deferredPages.indexOfFirst { it.text == "❎" }
            assertTrue("The displayed suffix must include ❌", displayedRed >= 0)
            assertEquals("A page boundary must not split the source order", displayedRed + 1, displayedGreen)
            val alternativePosition = page.candidates.indexOfFirst { it.text == "❎" }
            lateinit var alternative: View
            onMain { alternative = session.floatingUi(floating).root.getChildAt(alternativePosition) }
            session.capture("emoji-floating-deferred-choices-ui.png")
            session.expectCommit("❎", alternative)

            onMain { mode.setValue(FloatingCandidatesMode.InputDevice) }
            instrumentation.waitForIdleSync()
            session.type("cha")
            val restoredFloating = session.floatingView()
            await("returned horizontal viewport owner") {
                session.inputView.handleEvents && !restoredFloating.handleEvents && session.row.isShown
            }
        } finally {
            onMain {
                preferences.edit().apply {
                    if (originalPresent) putString(mode.key, originalRawValue) else remove(mode.key)
                }.apply()
            }
            instrumentation.waitForIdleSync()
            assertEquals("Restore exact floating preference presence", originalPresent, preferences.contains(mode.key))
            assertEquals("Restore the exact floating preference value", originalRawValue,
                preferences.getString(mode.key, null))
        }
    }

    private fun assertFloatingPage(
        session: UiSession,
        floating: CandidatesView,
        page: FcitxEvent.PagedCandidateEvent.Data,
    ) = onMain {
        assertTrue("Every actual floating page must have one to three candidates", page.candidates.size in 1..3)
        val root = session.floatingUi(floating).root
        assertEquals(page.candidates.size,
            (0 until root.childCount).count { root.getChildAt(it).tag is LabeledCandidateItemUi })
        page.candidates.forEachIndexed { position, candidate ->
            val item = root.getChildAt(position) as ViewGroup
            assertTrue("The floating candidate must be visible", item.isShown)
            assertTrue("The floating candidate must retain a real native ID", candidate.nativeIndex >= 0)
            val candidateUi = item.tag as LabeledCandidateItemUi
            val label = item.allViews.filterIsInstance<TextView>()
                .single { it !== candidateUi.pinyinText }
            val topPinyin = candidate.topPinyinAnnotation
            val body = if (topPinyin == null) candidate.textWithComment() else candidate.text
            assertEquals(candidate.label + body, label.text.toString())
            assertEquals(if (topPinyin == null) View.GONE else View.VISIBLE, candidateUi.pinyinText.visibility)
            assertEquals(topPinyin.orEmpty(), candidateUi.pinyinText.text.toString())
        }
    }

    private fun assertChaGroups(
        session: UiSession,
        expanded: BaseExpandedCandidateWindow<*>,
        sourceMustBeExpanded: Boolean,
    ) {
        await("contiguous expanded 叉 choices") {
            val values = expanded.adapter.snapshot().items.map { it.text }
            val red = values.indexOf("❌")
            red >= 0 && values.getOrNull(red + 1) == "❎"
        }
        onMain {
            val items = expanded.adapter.snapshot().items
            val fork = items.indexOfFirst { it.text == "叉" }
            val red = items.indexOfFirst { it.text == "❌" }
            val green = items.indexOfFirst { it.text == "❎" }
            assertEquals("The two deferred 叉 choices must remain contiguous", red + 1, green)
            if (sourceMustBeExpanded || fork >= 0) {
                assertTrue("叉 must remain present outside the first page", fork >= 0)
                assertEquals("Hidden 叉 must immediately precede its group", fork + 1, red)
            } else {
                assertTrue("A source absent from expansion must be on the actual first page",
                    session.visibleTexts().contains("叉"))
            }
            val tea = items.indexOfFirst { it.text == "🍵" }
            assertTrue("Tea must remain available on one of the actual pages",
                tea >= 0 || session.visibleTexts().contains("🍵"))
            assertFalse("Tea cannot split the two 叉 choices", tea in (red + 1) until green)
            val expectedComment = if (fork >= 0) "" else "(叉)"
            assertEquals(expectedComment, items[red].displayComment ?: items[red].comment)
            assertEquals(expectedComment, items[green].displayComment ?: items[green].comment)
            assertEquals("❌$expectedComment", items[red].textWithComment())
            assertEquals("❎$expectedComment", items[green].textWithComment())
        }
    }

    private fun withEditor(prepare: (FcitxConnection) -> Unit = {}, body: (UiSession) -> Unit) {
        val connectionName = "CandidateExpansionUiTest"
        val connection = FcitxDaemon.connect(connectionName)
        val storage = context.createDeviceProtectedStorageContext()
        val root = storage.getExternalFilesDir(null) ?: storage.filesDir
        val originalFiles = listOf(
            File(root, "config/profile"),
            File(root, "config/conf/pinyin.conf"),
            File(root, "config/conf/pinyin/pinyin.conf"),
        ).associateWith { it.takeIf(File::exists)?.readBytes() }
        var activity: GestureEditingTestActivity? = null
        var service: FcitxInputMethodService? = null
        var session: UiSession? = null
        var originalIme: String? = null
        var originalEnabled: Array<String>? = null
        var configured = false
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, GestureEditingTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as GestureEditingTestActivity
            val host = requireNotNull(activity)
            lateinit var editor: EditText
            onMain {
                editor = EditText(host).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    hint = "Candidate expansion validation"
                }
                host.setContentView(editor)
                editor.requestFocus()
            }
            await("editor focus") { editor.isShown && editor.hasWindowFocus() }
            onMain {
                host.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await("input method view") {
                runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true
            }
            instrumentation.waitForIdleSync()
            val activeService = requireNotNull(runningService()).also { service = it }
            originalIme = connection.runImmediately { currentIme().uniqueName }
            originalEnabled = connection.runImmediately { enabledIme().map { it.uniqueName }.toTypedArray() }
            connection.runImmediately {
                configured = true
                val enabled = requireNotNull(originalEnabled)
                if ("pinyin" !in enabled) setEnabledIme(enabled + "pinyin")
                activateIme("pinyin")
                setCapFlags(CapabilityFlags(
                    CapabilityFlag.Preedit, CapabilityFlag.ClientSideInputPanel, CapabilityFlag.Sensitive,
                ))
                setImConfig("pinyin", RawConfig().apply {
                    getOrCreate("SymbolsEnabled").value = "True"
                    getOrCreate("FoldSymbolCandidates").value = "True"
                    getOrCreate("Prediction").value = "False"
                })
                reset()
            }
            await("full pinyin") { connection.runImmediately { inputMethodEntryCached.uniqueName } == "pinyin" }
            prepare(connection)
            // Scheme activation and preparation can change toolbarHeight, which posts
            // a theme-driven InputView replacement. Capture the final attached view.
            instrumentation.waitForIdleSync()
            val inputView = requireNotNull(activeService.inputView)
            val wm = windowManager(activeService)
            lateinit var row: RecyclerView
            onMain {
                wm.attachWindow(KeyboardWindow)
                row = inputView.allViews.filterIsInstance<RecyclerView>()
                    .first { it.adapter is HorizontalCandidateViewAdapter }
            }
            session = UiSession(connection, editor, inputView, row, wm)
            body(requireNotNull(session))
        } finally {
            try {
                if (configured) connection.runImmediately {
                    reset()
                    originalEnabled?.let { setEnabledIme(it) }
                    originalIme?.takeIf { it in originalEnabled.orEmpty() }?.let { activateIme(it) }
                }
            } finally {
                try {
                    onMain {
                        session?.restoreRow()
                        session?.dismissOrdinaryMenu()
                        session?.popup()?.dismiss()
                        service?.let { windowManager(it).attachWindow(KeyboardWindow) }
                        activity?.finish()
                    }
                    instrumentation.waitForIdleSync()
                } finally {
                    try {
                        originalFiles.forEach { (file, bytes) ->
                            if (bytes != null) {
                                file.parentFile?.mkdirs()
                                file.writeBytes(bytes)
                            } else if (file.exists()) {
                                assertTrue("Could not restore absent configuration ${file.name}", file.delete())
                            }
                        }
                        connection.runImmediately { reloadConfig() }
                    } finally {
                        FcitxDaemon.disconnect(connectionName)
                    }
                }
            }
        }
    }

    private inner class UiSession(
        val connection: FcitxConnection,
        val editor: EditText,
        val inputView: InputView,
        val row: RecyclerView,
        val wm: InputWindowManager,
    ) {
        private var originalRowParams: LinearLayout.LayoutParams? = null

        fun type(keys: String) {
            connection.runImmediately { reset() }
            instrumentation.waitForIdleSync()
            onMain { editor.setText(""); editor.setSelection(0) }
            instrumentation.waitForIdleSync()
            connection.runImmediately { reset() }
            instrumentation.waitForIdleSync()
            await("cleared editor/native preedit", diagnostics = { editorDiagnostics() }) {
                editor.text.isEmpty() && connection.runImmediately {
                    inputPanelCached.preedit.isEmpty() && clientPreeditCached.isEmpty()
                }
            }
            connection.runImmediately {
                keys.forEach { sendKey(it.toString(), states = KeyStates.Virtual.states) }
            }
            await("visible candidate presentation", diagnostics = { rowDiagnostics() }) {
                if (AppPrefs.getInstance().candidates.mode.getValue() == FloatingCandidatesMode.Always) {
                    val floating = floatingView()
                    floating.handleEvents && floating.isShown && floatingPage(floating).candidates.isNotEmpty()
                } else {
                    row.isShown && row.width > 0 && row.childCount > 0
                }
            }
        }

        fun floatingView(): CandidatesView =
            FcitxInputMethodService::class.java.getDeclaredField("candidatesView").apply { isAccessible = true }
                .get(inputView.service) as CandidatesView

        fun floatingPage(view: CandidatesView): FcitxEvent.PagedCandidateEvent.Data =
            CandidatesView::class.java.getDeclaredField("paged").apply { isAccessible = true }
                .get(view) as FcitxEvent.PagedCandidateEvent.Data

        fun floatingUi(view: CandidatesView): PagedCandidatesUi =
            CandidatesView::class.java.getDeclaredField("candidatesUi").apply { isAccessible = true }
                .get(view) as PagedCandidatesUi

        fun nextFloatingPage(view: CandidatesView) {
            val before = floatingPage(view)
            onMain {
                val pagination = floatingUi(view).root.getChildAt(before.candidates.size).tag as PaginationUi
                assertTrue(pagination.nextIcon.isShown)
                assertTrue(pagination.nextIcon.performClick())
            }
            await("next actual floating page") {
                val next = floatingPage(view)
                next.hasPrev && next.candidates.map { it.nativeIndex } != before.candidates.map { it.nativeIndex }
            }
        }

        fun constrainRow(width: Int) {
            onMain {
                val params = row.layoutParams as LinearLayout.LayoutParams
                if (originalRowParams == null) originalRowParams = params
                row.layoutParams = LinearLayout.LayoutParams(params).apply { this.width = width; weight = 0f }
                row.requestLayout()
            }
            await("constrained candidate row", diagnostics = { rowDiagnostics() }) {
                row.isShown && row.width in 1..(width + 2)
            }
        }

        fun restoreRow() {
            originalRowParams?.let { row.layoutParams = it; row.requestLayout() }
        }

        fun visibleHolders(): List<CandidateViewHolder> {
            val bounds = Rect()
            return (0 until row.childCount).mapNotNull { index ->
                val child = row.getChildAt(index)
                val holder = row.getChildViewHolder(child) as? CandidateViewHolder ?: return@mapNotNull null
                if (child.getLocalVisibleRect(bounds) && bounds.left <= 0 && bounds.top <= 0 &&
                    bounds.right >= child.width && bounds.bottom >= child.height) holder else null
            }
        }

        fun visibleTexts() = visibleHolders().map { it.text }

        fun expand(): BaseExpandedCandidateWindow<*> {
            onMain {
                val button = requireNotNull(inputView.findViewById<View>(R.id.expand_candidate_btn))
                assertTrue("The expand button must be shown", button.isShown)
                assertTrue(button.performClick())
            }
            await("expanded window") { wm.currentWindowOrNull() is BaseExpandedCandidateWindow<*> }
            return wm.currentWindowOrNull() as BaseExpandedCandidateWindow<*>
        }

        fun expandedHolder(window: BaseExpandedCandidateWindow<*>, text: String): CandidateViewHolder {
            await("expanded $text item") { window.adapter.snapshot().items.any { it.text == text } }
            lateinit var recycler: RecyclerView
            var position = -1
            onMain {
                recycler = inputView.allViews.filterIsInstance<RecyclerView>().first { it.adapter === window.adapter }
                position = window.adapter.snapshot().items.indexOfFirst { it.text == text }
                recycler.scrollToPosition(0)
            }
            instrumentation.waitForIdleSync()
            onMain {
                if (recycler.findViewHolderForAdapterPosition(position) == null) {
                    recycler.scrollToPosition((position - 2).coerceAtLeast(0))
                }
            }
            await("bound $text holder") { recycler.findViewHolderForAdapterPosition(position) is CandidateViewHolder }
            return recycler.findViewHolderForAdapterPosition(position) as CandidateViewHolder
        }

        fun popup(): CandidateCharacterPopup? =
            BaseInputView::class.java.getDeclaredField("candidateCharacterPopup").apply { isAccessible = true }
                .get(inputView) as CandidateCharacterPopup?

        fun popupLabels(): List<TextView> {
            val popup = popup() ?: return emptyList()
            val content = CandidateCharacterPopup::class.java.getDeclaredField("content").apply { isAccessible = true }
                .get(popup) as ViewGroup
            return (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<TextView>()
                .filter { it.text.isNotEmpty() }
        }

        fun dismissOrdinaryMenu() {
            (BaseInputView::class.java.getDeclaredField("candidateActionMenu").apply { isAccessible = true }
                .get(inputView) as PopupMenu?)?.dismiss()
        }

        fun capture(filename: String) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val file = File(context.cacheDir, filename)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            println("EMOJI_UI_SCREENSHOT=${file.absolutePath}")
        }

        fun expectCommit(expected: String, click: View) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val commits = CopyOnWriteArrayList<String>()
            try {
                val flow = connection.runImmediately { eventFlow }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    flow.filterIsInstance<FcitxEvent.CommitStringEvent>().collect { commits.add(it.data.text) }
                }
                val client = connection.runImmediately { clientPreeditCached.toString() }
                onMain {
                    println("EMOJI_UI_PRECLICK_EDITOR=${editorDiagnostics()}, clientPreedit=$client")
                    assertEquals("The editor must contain exactly the native client preedit", client, editor.text.toString())
                    if (client.isEmpty()) {
                        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
                        assertEquals(-1, BaseInputConnection.getComposingSpanEnd(editor.text))
                    } else {
                        assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
                        assertEquals(editor.text.length, BaseInputConnection.getComposingSpanEnd(editor.text))
                    }
                    assertTrue(click.performClick())
                }
                await("exact finished $expected commit", diagnostics = { "${editorDiagnostics()}, nativeCommits=$commits" }) {
                    editor.text.toString() == expected && commits.toList() == listOf(expected) &&
                        BaseInputConnection.getComposingSpanStart(editor.text) == -1 &&
                        BaseInputConnection.getComposingSpanEnd(editor.text) == -1
                }
                onMain {
                    assertEquals(expected, editor.text.toString())
                    assertEquals(listOf(expected), commits.toList())
                    assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
                    assertEquals(-1, BaseInputConnection.getComposingSpanEnd(editor.text))
                    println("EMOJI_UI_POSTCLICK_EDITOR=${editorDiagnostics()}, nativeCommits=$commits")
                }
            } finally {
                scope.cancel()
            }
        }

        fun rowDiagnostics(): String =
            "width=${row.width}, measured=${row.measuredWidth}, shown=${row.isShown}, children=${row.childCount}, " +
                "layoutWidth=${row.layoutParams?.width}, layoutRequested=${row.isLayoutRequested}, " +
                "visible=${visibleTexts()}"

        private fun editorDiagnostics(): String =
            "text=${editor.text}, selection=${editor.selectionStart}..${editor.selectionEnd}, " +
                "composing=${BaseInputConnection.getComposingSpanStart(editor.text)}.." +
                BaseInputConnection.getComposingSpanEnd(editor.text)
    }

    private fun windowManager(service: FcitxInputMethodService): InputWindowManager =
        service.inputView!!.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
            .get(service.inputView) as InputWindowManager

    private fun runningService(): FcitxInputMethodService? {
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = type.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun await(label: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            onMain { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        onMain { assertTrue("Timed out waiting for $label: ${diagnostics()}", condition()) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** A candidate whose pinyin annotation is drawn beside the text, not above it. */
    private fun CandidateViewHolder.hasRightAnnotation(): Boolean =
        (candidate.displayComment ?: candidate.comment).isNotBlank() &&
            candidate.topPinyinAnnotation == null

    companion object {
        @ClassRule
        @JvmField
        val defaultIme = RequireDefaultImeRule()

        private val CHA_SYMBOLS = setOf("👎", "🍵", "❌", "❎")
    }
}
