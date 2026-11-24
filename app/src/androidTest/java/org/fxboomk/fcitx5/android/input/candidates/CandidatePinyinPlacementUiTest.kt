/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Spanned
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.paging.PagingData
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.ColorUtils
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fxboomk.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fxboomk.fcitx5.android.input.candidates.expanded.GridPagingCandidateViewAdapter
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateViewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Verify the two-line presentation without changing the user's saved IME settings. */
class CandidatePinyinPlacementUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun defaultRightPlacementRetainsTheOriginalBracketedComment() = onMain {
        val candidate = word("你", "(nǐ)")
        val ui = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        ui.updateCandidate(candidate)
        layout(ui.root, widthDp = 140, heightDp = 40)

        assertFalse(candidate.isPinyinAnnotationOnTop)
        assertNull(candidate.topPinyinAnnotation)
        assertEquals("你 (nǐ)", ui.text.text.toString())
        assertEquals(View.GONE, ui.pinyinText.visibility)
        assertEquals("", ui.pinyinText.text.toString())
    }

    @Test
    fun topPlacementShowsOneBracketFreeReadingAboveTheCharacter() = onMain {
        val ui = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        ui.updateCandidate(word("行", "(háng)", top = true))
        layoutWithNaturalHeight(ui.root, widthDp = 100, minimumHeightDp = 40)

        assertEquals("行", ui.text.text.toString())
        assertEquals("háng", ui.pinyinText.text.toString())
        assertEquals(View.VISIBLE, ui.pinyinText.visibility)
        assertEquals(ThemePreset.MaterialLight.candidateCommentColor, ui.pinyinText.currentTextColor)
        assertLinesAreSeparate(ui)
        assertGlyphsInside(ui.root, ui.pinyinText)
        assertGlyphsInside(ui.root, ui.text)
    }

    @Test
    fun topReadingRemainsReadableWhenTheCandidateIsHighlighted() = onMain {
        for (theme in listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark)) {
            val ui = CandidateItemUi(context, theme, Typeface.MONOSPACE)
            ui.updateCandidate(word("行", "(háng)", top = true))
            ui.setActive(true)
            layoutWithNaturalHeight(ui.root, widthDp = 100, minimumHeightDp = 40)

            val contrast = ColorUtils.calculateContrast(
                ui.pinyinText.currentTextColor, theme.genericActiveBackgroundColor,
            )
            assertTrue("${theme.name} highlighted pinyin contrast was $contrast", contrast >= 4.5)
            assertEquals("háng", ui.pinyinText.text.toString())
            assertLinesAreSeparate(ui)
        }
    }

    @Test
    fun recyclingTheSameCandidateBetweenPlacementsClearsTheSecondLine() = onMain {
        val ui = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        val holder = CandidateViewHolder(ui)
        val configuredSize = ui.text.textSize
        val top = word("你", "(nǐ)", top = true).copy(nativeIndex = 41)
        holder.update(7, top)
        layoutWithNaturalHeight(ui.root, widthDp = 100, minimumHeightDp = 40)
        assertEquals(41, holder.idx)
        assertEquals("nǐ", ui.pinyinText.text.toString())

        holder.update(7, top.copy(isPinyinAnnotationOnTop = false))
        layout(ui.root, widthDp = 100, heightDp = 40)
        assertEquals("你 (nǐ)", ui.text.text.toString())
        assertEquals(View.GONE, ui.pinyinText.visibility)
        assertEquals("", ui.pinyinText.text.toString())
        assertEquals(configuredSize, ui.text.textSize, 0.01f)

        holder.clear()
        layout(ui.root, widthDp = 100, heightDp = 40)
        assertEquals(-1, holder.idx)
        assertEquals("", ui.text.text.toString())
        assertEquals("", ui.pinyinText.text.toString())
        assertEquals(View.GONE, ui.pinyinText.visibility)
    }

    @Test
    fun presentationOverridesAndOrdinaryCommentsKeepTheirRightSideFormat() = onMain {
        val ui = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        val candidates = listOf(
            word("▶️", "(ài)", top = true).copy(displayComment = "(开始)", spaceBetweenComment = false),
            word("🈳", "(空)").copy(spaceBetweenComment = false),
            word("▶️", "…").copy(spaceBetweenComment = false),
            word("你", " ", top = true),
        )
        for (candidate in candidates) {
            ui.updateCandidate(candidate)
            layout(ui.root, widthDp = 140, heightDp = 40)
            assertNull(candidate.topPinyinAnnotation)
            assertEquals(candidate.textWithComment(), ui.text.text.toString())
            assertEquals(View.GONE, ui.pinyinText.visibility)
            assertEquals("", ui.pinyinText.text.toString())
        }
    }

    @Test
    fun stackedReadingUsesLessWidthAndFitsNarrowRowsAtLargeFontScales() = onMain {
        val right = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        val top = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
        right.updateCandidate(word("床", "(chuáng)"))
        top.updateCandidate(word("床", "(chuáng)", top = true))
        naturalWidth(right.root, heightDp = 40)
        naturalLayout(top.root)
        assertTrue("Stacking must not reserve the old inline comment width",
            top.root.measuredWidth < right.root.measuredWidth)

        for (fontScale in listOf(1f, 2f)) {
            top.setFontScale(fontScale)
            for (width in listOf(48, 72)) {
                for (height in listOf(30, 40)) {
                    val configuredSize = top.text.textSize
                    layoutWithNaturalHeight(top.root, widthDp = width, minimumHeightDp = height)
                    val diagnostics = "fontScale=$fontScale widthDp=$width minimumHeightDp=$height " +
                        "rootMeasured=${top.root.measuredWidth}x${top.root.measuredHeight} " +
                        "rootActual=${top.root.width}x${top.root.height} " +
                        "mainMeasured=${top.text.measuredWidth}x${top.text.measuredHeight} " +
                        "mainActual=${top.text.width}x${top.text.height} " +
                        "pinyinMeasured=${top.pinyinText.measuredWidth}x${top.pinyinText.measuredHeight} " +
                        "pinyinActual=${top.pinyinText.width}x${top.pinyinText.height} " +
                        "configuredBodyPx=$configuredSize bodyPx=${top.text.textSize} " +
                        "pinyinPx=${top.pinyinText.textSize} " +
                        "mainMeasuredBaseline=${top.text.measuredBaseline()} " +
                        "pinyinMeasuredBaseline=${top.pinyinText.measuredBaseline()} " +
                        "rootBaseline=${top.root.baseline} mainTop=${top.text.top} " +
                        "pinyinTop=${top.pinyinText.top} pinyinVisibility=${top.pinyinText.visibility}"
                    assertEquals("Top placement must retain the configured body size: $diagnostics", configuredSize,
                        top.text.textSize, 0.01f)
                    assertEquals("chuáng", top.pinyinText.text.toString())
                    assertTrue("Both candidate lines must retain height: $diagnostics",
                        top.pinyinText.height > 0 && top.text.height > 0)
                    assertPinyinAboveMain(top.root, top.pinyinText, top.text)
                    assertGlyphsInside(top.root, top.pinyinText)
                    assertGlyphsInside(top.root, top.text)
                }
            }
        }
    }

    @Test
    fun floatingCandidatesUseTheTopLineAndRecycleBackToTheOriginalText() = onMain {
        val ui = LabeledCandidateItemUi(
            context, ThemePreset.MaterialLight,
            setupTextView = {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 48f)
                setSingleLine()
                gravity = Gravity.TOP or Gravity.START
                setPadding(6, 6, 6, 6)
            },
            highlightRadius = 4f,
        )
        val main = ui.root.allViews.filterIsInstance<TextView>().single { it !== ui.pinyinText }
        val configuredGravity = main.gravity
        val candidate = word("行", "(háng)", top = true).copy(label = "1. ")
        ui.update(candidate, active = false)
        naturalLayout(ui.root)
        val topWidth = ui.root.width
        assertEquals("1. 行", main.text.toString())
        assertEquals("háng", ui.pinyinText.text.toString())
        assertEquals(View.VISIBLE, ui.pinyinText.visibility)
        assertEquals(Gravity.CENTER_VERTICAL, main.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertPinyinAboveMain(ui.root, ui.pinyinText, main)
        assertGlyphsInside(ui.root, ui.pinyinText)
        assertViewInside(ui.root, main)

        ui.update(candidate.copy(isPinyinAnnotationOnTop = false), active = false)
        naturalLayout(ui.root)
        assertEquals("1. 行 (háng)", main.text.toString())
        assertEquals("Recycling to Right must restore the configured text gravity", configuredGravity, main.gravity)
        assertTrue("The original foreground spans must survive", main.text is Spanned)
        assertEquals(View.GONE, ui.pinyinText.visibility)
        assertEquals("", ui.pinyinText.text.toString())
        assertTrue("The right-side comment should restore its normal width", ui.root.width > topWidth)

        ui.update(CandidateWord.Empty, active = false)
        naturalLayout(ui.root)
        assertEquals("", main.text.toString())
        assertEquals(configuredGravity, main.gravity)
        assertEquals("", ui.pinyinText.text.toString())
        assertEquals(View.GONE, ui.pinyinText.visibility)
    }

    @Test
    fun topLayoutMeasuresTheDrawingBaselineWithoutCountingBodyPaddingTwice() = onMain {
        for (bodyTopPadding in listOf(8, 24)) {
            val main = AutoScaleTextView(context).apply {
                text = "你"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 48f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                scaleMode = AutoScaleTextView.Mode.None
                setPadding(8, bodyTopPadding, 8, 3)
            }
            val pinyin = AutoScaleTextView(context).apply {
                text = "nǐ"
                gravity = Gravity.CENTER
                scaleMode = AutoScaleTextView.Mode.Proportional
            }
            val column = CandidateAnnotationLayout(context, main, pinyin).apply {
                setPadding(0, 5, 0, 9)
            }
            column.measure(
                View.MeasureSpec.makeMeasureSpec(dp(160), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            assertEquals("The measured baseline must be available before child layout", 0, main.height)
            val measuredBodyBaseline = main.measuredBaseline()
            val measuredColumnBaseline = column.baseline
            assertEquals(column.paddingTop + measuredBodyBaseline, measuredColumnBaseline)

            column.layout(0, 0, column.measuredWidth, column.measuredHeight)
            val bodyInk = main.renderedGlyphBounds()
            val unscaledInk = Rect()
            main.paint.getTextBounds(main.text.toString(), 0, main.text.length, unscaledInk)
            val drawingBaseline = (bodyInk.top - unscaledInk.top).roundToInt()
            assertEquals("Measurement must use the actual drawing baseline once, including body padding",
                drawingBaseline, measuredBodyBaseline)
            assertEquals("The column must expose the actual body baseline before layout",
                main.top + drawingBaseline, measuredColumnBaseline)
            assertEquals(measuredBodyBaseline, main.measuredBaseline())
            assertPinyinAboveMain(column, pinyin, main)
            assertGlyphsInside(column, main)
            assertGlyphsInside(column, pinyin)
        }
    }

    @Test
    fun plainCandidateMinimumHeightExposesItsCenteredBodyBaselineBeforeLayout() = onMain {
        for (fontScale in listOf(1f, 2f)) {
            val ui = CandidateItemUi(context, ThemePreset.MaterialLight, Typeface.MONOSPACE)
            ui.updateCandidate(word("我", ""))
            ui.setFontScale(fontScale)
            ui.root.minimumHeight = dp(40)
            ui.root.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            ui.root.measure(unspecified, unspecified)
            assertEquals("Capture the root baseline before the body has been laid out", 0, ui.text.height)
            val measuredRootBaseline = ui.root.baseline
            val measuredHeight = ui.root.measuredHeight
            ui.root.layout(0, 0, ui.root.measuredWidth, measuredHeight)

            val bodyOrigin = Rect()
            ui.root.offsetDescendantRectToMyCoords(ui.text, bodyOrigin)
            val actualBodyBaseline = bodyOrigin.top + ui.text.measuredBaseline()
            val diagnostics = "fontScale=$fontScale rootHeight=$measuredHeight " +
                "mainHeight=${ui.text.height} mainOriginTop=${bodyOrigin.top} " +
                "mainMeasuredBaseline=${ui.text.measuredBaseline()}"
            assertTrue("The naturally measured candidate must keep its 40dp minimum: $diagnostics",
                measuredHeight >= dp(40))
            assertEquals("The pre-layout root baseline must include all body centering offsets: $diagnostics",
                actualBodyBaseline, measuredRootBaseline)
            assertEquals("Layout must preserve the measured root baseline: $diagnostics",
                measuredRootBaseline, ui.root.baseline)
            assertGlyphsInside(ui.root, ui.text)
        }
    }

    @Test
    fun horizontalMixedRowsRetainOneBodySizeAndBaselineAtEveryFontScale() = onMain {
        for (fontScale in listOf(1f, 2f)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val candidates = mixedCandidates()
            val adapter = HorizontalCandidateViewAdapter(ThemePreset.MaterialLight)
            adapter.updateCandidates(candidates, total = candidates.size)
            val holders = candidates.indices.map { position ->
                adapter.onCreateViewHolder(row, 0).also { holder ->
                    adapter.onBindViewHolder(holder, position)
                    holder.ui.setFontScale(fontScale)
                    holder.itemView.layoutParams = LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.MATCH_PARENT)
                    row.addView(holder.itemView)
                }
            }
            val configuredSize = holders.first().ui.text.textSize
            val rowHeight = holders.maxOf { naturalHeight(it.itemView, widthDp = 130) }.coerceAtLeast(dp(40))
            layoutPixels(row, width = dp(130 * holders.size), height = rowHeight)

            assertBodiesMatch(row, holders.map { it.ui.text }, configuredSize)
            holders.forEach { holder ->
                assertGlyphsInside(row, holder.ui.text)
                assertGlyphsInside(holder.ui.root, holder.ui.text)
                if (holder.candidate.topPinyinAnnotation != null) {
                    assertPinyinAboveMain(row, holder.ui.pinyinText, holder.ui.text)
                    assertGlyphsInside(row, holder.ui.pinyinText)
                    assertGlyphsInside(holder.ui.root, holder.ui.pinyinText)
                }
            }
        }
    }

    @Test
    fun gridMixedRowRemeasuresPlainCandidatesToMatchTheTopCandidate() {
        for (fontScale in listOf(1f, 2f)) {
            lateinit var recycler: RecyclerView
            lateinit var adapter: GridPagingCandidateViewAdapter
            lateinit var owner: TestLifecycleOwner
            var configuredSize = Float.NaN
            val candidates = mixedCandidates().take(3)
            onMain {
                owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
                adapter = object : GridPagingCandidateViewAdapter(ThemePreset.MaterialLight) {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
                        return super.onCreateViewHolder(parent, viewType).also {
                            it.ui.setFontScale(fontScale)
                            if (configuredSize.isNaN()) configuredSize = it.ui.text.textSize
                        }
                    }
                }
                recycler = RecyclerView(context).apply {
                    layoutManager = GridLayoutManager(context, 3)
                    itemAnimator = null
                    this.adapter = adapter
                }
                adapter.submitData(owner.lifecycle, PagingData.from(candidates))
            }
            try {
                await("loaded and bound mixed Grid row at fontScale=$fontScale") {
                    layoutPixels(recycler, width = dp(360), height = dp(300))
                    adapter.itemCount == candidates.size && candidates.indices.all {
                        recycler.findViewHolderForAdapterPosition(it) is CandidateViewHolder
                    }
                }
                onMain {
                    val holders = candidates.indices.map {
                        recycler.findViewHolderForAdapterPosition(it) as CandidateViewHolder
                    }
                    val height = holders.first().itemView.height
                    val top = holders.first().itemView.top
                    assertTrue("Top pinyin needs a larger Grid row at fontScale=$fontScale", height > dp(40))
                    holders.forEach {
                        assertEquals("Grid candidates in one row must share height", height, it.itemView.height)
                        assertEquals("Grid candidates must start in the same row", top, it.itemView.top)
                        assertGlyphsInside(it.ui.root, it.ui.text)
                    }
                    assertBodiesMatch(recycler, holders.map { it.ui.text }, configuredSize)
                    holders.filter { it.candidate.topPinyinAnnotation != null }.forEach {
                        assertPinyinAboveMain(recycler, it.ui.pinyinText, it.ui.text)
                        assertGlyphsInside(it.ui.root, it.ui.pinyinText)
                    }
                }
            } finally {
                onMain {
                    recycler.adapter = null
                    owner.currentState = Lifecycle.State.DESTROYED
                }
            }
        }
    }

    @Test
    fun horizontalFloatingPageAlignsMixedCandidatesByTheirBodyBaseline() = onMain {
        val candidates = mixedCandidates().mapIndexed { index, candidate ->
            candidate.copy(label = "${index + 1}. ")
        }.toTypedArray()
        val ui = PagedCandidatesUi(
            ctx = context,
            theme = ThemePreset.MaterialLight,
            setupTextView = {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 48f)
                setSingleLine()
                gravity = Gravity.TOP or Gravity.START
                setPadding(6, 6, 6, 6)
            },
            onCandidateClick = {},
            onCandidateAction = { _, _, _ -> },
            onBindCandidateGesture = { _, _, _ -> },
            onUnbindCandidateGesture = {},
            onPrevPage = {},
            onNextPage = {},
            highlightRadius = 4f,
        )
        ui.update(
            FcitxEvent.PagedCandidateEvent.Data(candidates, 0,
                FcitxEvent.PagedCandidateEvent.LayoutHint.NotSet, false, false),
            FloatingCandidatesOrientation.Horizontal,
            dp(360),
        )
        ui.root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.AT_MOST),
        )
        ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
        val items = candidates.indices.map { ui.root.getChildAt(it).tag as LabeledCandidateItemUi }
        val bodies = items.map { item ->
            item.root.allViews.filterIsInstance<TextView>().single { it !== item.pinyinText }
        }
        assertBodiesMatch(ui.root, bodies, 48f)
        candidates.indices.filter { candidates[it].topPinyinAnnotation != null }.forEach { index ->
            assertPinyinAboveMain(ui.root, items[index].pinyinText, bodies[index])
        }
    }

    private fun mixedCandidates() = arrayOf(
        word("你", "(nǐ)", top = true),
        word("我", ""),
        word("我们", ""),
        word("拟", "(nǐ)", top = true),
    )

    private fun word(text: String, comment: String, top: Boolean = false) =
        CandidateWord("", text, comment, isPinyinAnnotationOnTop = top)

    private fun layout(view: View, widthDp: Int, heightDp: Int) {
        layoutPixels(view, dp(widthDp), dp(heightDp))
    }

    private fun layoutPixels(view: View, width: Int, height: Int) {
        view.forceLayout()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun naturalHeight(view: View, widthDp: Int): Int {
        view.forceLayout()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight
    }

    private fun layoutWithNaturalHeight(view: View, widthDp: Int, minimumHeightDp: Int) {
        val height = naturalHeight(view, widthDp).coerceAtLeast(dp(minimumHeightDp))
        layoutPixels(view, dp(widthDp), height)
    }

    private fun naturalWidth(view: View, heightDp: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY),
        )
    }

    private fun naturalLayout(view: View) {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.forceLayout()
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun assertLinesAreSeparate(ui: CandidateItemUi) {
        assertTrue("Both candidate lines must retain height", ui.pinyinText.height > 0 && ui.text.height > 0)
        assertPinyinAboveMain(ui.root, ui.pinyinText, ui.text)
    }

    private fun assertPinyinAboveMain(root: android.view.ViewGroup, pinyin: AutoScaleTextView, main: TextView) {
        val pinyinInk = glyphsWithin(root, pinyin)
        val mainOrigin = Rect()
        root.offsetDescendantRectToMyCoords(main, mainOrigin)
        val mainTop = if (main is AutoScaleTextView) glyphsWithin(root, main).top else {
            val ink = Rect()
            main.paint.getTextBounds(main.text.toString(), 0, main.text.length, ink)
            mainOrigin.top + main.baseline + ink.top.toFloat()
        }
        assertTrue("Pinyin $pinyinInk must end above the candidate's glyphs at $mainTop",
            pinyinInk.bottom <= mainTop + 1f)
    }

    private fun assertBodiesMatch(root: android.view.ViewGroup, bodies: List<TextView>, configuredSize: Float) {
        val baseline = baselineWithin(root, bodies.first())
        bodies.forEach { body ->
            assertEquals("Top placement must not alter an individual candidate's font size",
                configuredSize, body.textSize, 0.01f)
            assertEquals("Candidate bodies must retain one global row baseline",
                baseline.toFloat(), baselineWithin(root, body).toFloat(), 1f)
            if (body is AutoScaleTextView) {
                assertEquals("A candidate body must not be individually scaled", 1f, body.textScaleX, 0.01f)
            }
        }
    }

    private fun baselineWithin(root: android.view.ViewGroup, child: TextView): Int {
        val origin = Rect()
        root.offsetDescendantRectToMyCoords(child, origin)
        val baseline = (child as? AutoScaleTextView)?.measuredBaseline() ?: child.baseline
        return origin.top + baseline
    }

    private fun assertViewInside(root: android.view.ViewGroup, child: View) {
        val bounds = Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, bounds)
        assertTrue("$bounds must fit inside ${root.width}×${root.height}",
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= root.width && bounds.bottom <= root.height)
    }

    private fun assertGlyphsInside(root: android.view.ViewGroup, child: AutoScaleTextView) {
        val ink = glyphsWithin(root, child)
        assertTrue("Glyphs $ink must fit inside ${root.width}×${root.height}",
            ink.left >= -1f && ink.top >= -1f && ink.right <= root.width + 1f && ink.bottom <= root.height + 1f)
    }

    private fun glyphsWithin(root: android.view.ViewGroup, child: AutoScaleTextView): RectF {
        val origin = Rect()
        root.offsetDescendantRectToMyCoords(child, origin)
        return RectF(child.renderedGlyphBounds()).apply { offset(origin.left.toFloat(), origin.top.toFloat()) }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        onMain { assertTrue(label, condition()) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
