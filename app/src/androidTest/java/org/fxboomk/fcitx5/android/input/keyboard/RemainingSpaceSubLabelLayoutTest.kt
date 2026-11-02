/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.UppercasePosition
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef.Appearance.AltTextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Main-label bounds in these tests come from Canvas pixels, not TextView boxes. */
class RemainingSpaceSubLabelLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.createConfigurationContext(
        Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = 1f }
    )
    private val positions = listOf(AltTextPosition.Top, AltTextPosition.Bottom, AltTextPosition.TopRight)
    private val glyphs = listOf("g", "É", "主", "Shift")

    @Test
    fun defaultThemePairsAndMixedEdgesKeepTheMainLabelCentered() = withPreferences {
        positions.forEachIndexed { index, position ->
            ThemeManager.prefs.punctuationPosition.setValue(position.punctuation())
            val key = createKey(appearance(main = glyphs[index]))
            layoutKey(key)
            assertCentered("theme pair $position", key, Edges(position.edge(), position.edge()))
        }
        // The default theme also has a genuine one-top/one-bottom case: the
        // punctuation owns TopRight while the uppercase hint owns Bottom.
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.TopRight)
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Bottom)
        val mixed = createKey(appearance(secondary = null, uppercase = true))
        layoutKey(mixed)
        assertCentered("theme TopRight + UpperBottom", mixed, Edges(Edge.Top, secondary = Edge.Bottom))

        // TopBottom is the legacy combined override, not an independent pair.
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.None)
        val key = createKey(appearance(primaryPosition = AltTextPosition.TopBottom))
        layoutKey(key)
        assertCentered("legacy TopBottom", key, Edges(Edge.Top, Edge.Bottom))
        val appearanceHeight = key.getChildAt(0).height
        assertEquals("Top sublabel must touch the top key inset", key.vMargin.toDouble(),
            inkBounds(key.altText).top.toDouble(), 1.0)
        assertEquals("Bottom sublabel must touch the bottom key inset", (appearanceHeight - key.vMargin).toDouble(),
            inkBounds(key.altText1).bottom.toDouble(), 1.0)
    }

    @Test
    fun independentPairsCoverBothOrdersAndTopRightCollision() = withPreferences {
        // Three by three placements only; fonts and sizes are sampled separately.
        // A hidden theme ensures this cannot pass by accidentally using its defaults.
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
        positions.forEach { primary ->
            positions.forEachIndexed { index, secondary ->
                val key = createKey(appearance(
                    main = glyphs[index], primaryPosition = primary, secondaryPosition = secondary
                ))
                layoutKey(key)
                assertCentered("independent $primary/$secondary", key, Edges(primary.edge(), secondary.edge()))
            }
        }
    }

    @Test
    fun singlePrimaryUsesThemeOrExplicitTopBottomAndTopRight() = withPreferences {
        positions.forEach { position ->
            for (override in listOf(false, true)) {
                ThemeManager.prefs.punctuationPosition.setValue(
                    if (override) PunctuationPosition.None else position.punctuation()
                )
                val key = createKey(appearance(
                    secondary = null, primaryPosition = position.takeIf { override }
                ))
                layoutKey(key)
                assertCentered("single primary $position override=$override", key, Edges(primary = position.edge()))
            }
        }
    }

    @Test
    fun onlySecondaryRemainsCenteredWithEmptyOrThemeHiddenPrimary() = withPreferences {
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
        positions.forEach { position ->
            for (primary in listOf("")) {
                val key = createKey(appearance(primary = primary, secondaryPosition = position))
                layoutKey(key)
                assertCentered(
                    "secondary only $position primary='$primary'", key, Edges(secondary = position.edge())
                )
            }
        }
    }

    @Test
    fun themeUppercaseCoversCornerMixedAndUppercaseOnlyLayouts() = withPreferences {
        for (uppercase in listOf(UppercasePosition.Top, UppercasePosition.Bottom)) {
            ThemeManager.prefs.uppercasePosition.setValue(uppercase)
            val upperEdge = if (uppercase == UppercasePosition.Top) Edge.Top else Edge.Bottom
            positions.forEach { position ->
                ThemeManager.prefs.punctuationPosition.setValue(position.punctuation())
                val key = createKey(appearance(secondary = null, uppercase = true))
                layoutKey(key)
                assertCentered(
                    "theme punctuation=$position uppercase=$uppercase", key,
                    Edges(primary = position.edge(), secondary = upperEdge)
                )
            }
            ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
            val key = createKey(appearance(secondary = null, uppercase = true))
            layoutKey(key)
            assertCentered("theme uppercase only $uppercase", key, Edges(secondary = upperEdge))
        }
    }

    @Test
    fun independentUppercaseCoversBothEdgesAndUppercaseOnlyTopRight() = withPreferences {
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
        // The preference enables the hint; its independent override selects the edge.
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Top)
        positions.forEach { primary ->
            positions.forEach { upper ->
                val key = createKey(appearance(
                    secondary = null, uppercase = true,
                    primaryPosition = primary, secondaryPosition = upper
                ))
                layoutKey(key)
                assertCentered(
                    "independent punctuation=$primary uppercase=$upper", key,
                    Edges(primary = primary.edge(), secondary = upper.edge())
                )
            }
        }
        positions.forEach { upper ->
            val key = createKey(appearance(
                primary = "", secondary = null, uppercase = true, secondaryPosition = upper
            ))
            layoutKey(key)
            assertCentered("independent uppercase only $upper", key, Edges(secondary = upper.edge()))
        }
    }

    @Test
    fun switchingEdgesAndHidingLabelsClearsPlacementBeforeRestoringIt() = withPreferences {
        val definition = appearance()
        val key = createKey(definition)
        fun check(label: String, edges: Edges) {
            layoutKey(key)
            assertCentered(label, key, edges)
        }
        check("initial theme top corners", Edges(Edge.Top, Edge.Top))
        val initialInk = inkBounds(key.mainText)
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
        check("theme bottom corners", Edges(Edge.Bottom, Edge.Bottom))

        definition.altTextPositionOverride = AltTextPosition.TopRight
        definition.altText1PositionOverride = AltTextPosition.Bottom
        check("independent right-top/bottom", Edges(Edge.Top, Edge.Bottom))
        definition.altTextPositionOverride = AltTextPosition.Bottom
        definition.altText1PositionOverride = AltTextPosition.TopRight
        check("independent bottom/right-top", Edges(Edge.Bottom, Edge.Top))

        key.altText1.text = ""
        definition.altTextPositionOverride = AltTextPosition.TopRight
        definition.altText1PositionOverride = null
        check("single right-top after mixed edges", Edges(primary = Edge.Top))
        key.altText.text = ""
        key.altText1.text = "É"
        definition.altTextPositionOverride = null
        definition.altText1PositionOverride = AltTextPosition.Bottom
        check("secondary bottom after single primary", Edges(secondary = Edge.Bottom))

        definition.altText1PositionOverride = null
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
        layoutKey(key)
        val hidden = readInk("hidden after independent", key, Edges())
        // Hidden keys retain the normal no-sublabel typography. Compare actual ink
        // to a fresh hidden key rather than requiring a different font-metric policy.
        val freshDefinition = appearance(primary = "?", secondary = null)
        val fresh = createKey(freshDefinition)
        layoutKey(fresh)
        assertEquals("Hidden layout must not retain an old offset or scale", readInk("fresh hidden", fresh, Edges()).main, hidden.main)

        key.altText.text = "?"
        definition.altTextPositionOverride = null
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Top)
        check("restored theme top corners", Edges(Edge.Top, Edge.Top))
        assertEquals("Restoring the original layout restores its ink", initialInk, inkBounds(key.mainText))

        // Exercise the shared secondary slot's hidden/reset path too.
        val upperDefinition = appearance(secondary = null, uppercase = true, secondaryPosition = AltTextPosition.TopRight)
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Top)
        val upperKey = createKey(upperDefinition)
        layoutKey(upperKey)
        assertCentered("uppercase before hiding", upperKey, Edges(primary = Edge.Top, secondary = Edge.Top))
        upperDefinition.altText1PositionOverride = null
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.None)
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
        layoutKey(upperKey)
        assertEquals("Hidden uppercase clears its old offset", hidden.main, readInk("uppercase hidden", upperKey, Edges()).main)
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Bottom)
        layoutKey(upperKey)
        assertCentered("uppercase restored at bottom", upperKey, Edges(secondary = Edge.Bottom))
    }

    @Test
    fun representativeShortRowsFontsGlyphsAndScalesRemainCenteredAfterResize() = withPreferences {
        // Deliberately sampled rather than a density x font x glyph x size product.
        val samples = listOf(
            Sample("g", Typeface.SANS_SERIF, 1.6f, 240, 1.3f, AltTextPosition.Top, AltTextPosition.TopRight),
            Sample("E", Typeface.SERIF, 1f, 560, 1f, AltTextPosition.Top, AltTextPosition.Bottom, uppercase = true),
            Sample("主", Typeface.SANS_SERIF, 1.4f, 240, 1.3f, AltTextPosition.Bottom, AltTextPosition.Bottom),
            Sample("Shift", Typeface.MONOSPACE, 2.4f, 560, 1f, AltTextPosition.Bottom, AltTextPosition.TopRight),
        )
        samples.forEach { sample ->
            val sampleContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                densityDpi = sample.density
                fontScale = sample.fontScale
            })
            ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
            ThemeManager.prefs.uppercasePosition.setValue(if (sample.uppercase) UppercasePosition.Top else UppercasePosition.None)
            val key = createKey(appearance(
                main = sample.main, secondary = if (sample.uppercase) null else "É",
                uppercase = sample.uppercase, primaryPosition = sample.primary, secondaryPosition = sample.secondary
            ), sampleContext, sample.font, sample.scale)
            val edges = if (sample.uppercase) {
                Edges(primary = sample.primary.edge(), secondary = sample.secondary.edge())
            } else Edges(sample.primary.edge(), sample.secondary.edge())
            for (height in listOf(72, 32, 64)) {
                layoutKey(key, height)
                // 塞不下就隐藏：过短行上生产退回无副标签布局，此时只要求主字居中。
                // "常规行"阈值按字号缩放折算：2.4x 字号下 64dp 行的内容密度
                // 等效于 1x 字号的 ~27dp 行，属于短行。
                val normalRow = height / sample.scale >= 52
                val effective = if (
                    key.altText.visibility == View.GONE &&
                    key.altText1.visibility == View.GONE
                ) Edges() else edges
                assertCentered("$sample height=$height", key, effective, normalRow = normalRow)
            }
        }
    }

    @Test
    fun mixedLabelPlacementsInOneRowShareTheirBaseline() = withPreferences {
        val pairs = listOf(
            AltTextPosition.Top to AltTextPosition.TopRight,
            AltTextPosition.Bottom to AltTextPosition.Bottom,
            AltTextPosition.TopRight to AltTextPosition.Bottom,
        )
        val definitions = pairs.flatMap { (primary, secondary) ->
            listOf(
                Triple("g", "?", "É"),
                Triple("É", "…", "主"),
            ).map { (main, sub, sub1) ->
                AlphabetKey(main, sub, sub1, displayText = main).apply {
                    appearance.altTextPositionOverride = primary
                    appearance.altText1PositionOverride = secondary
                }
            }
        }
        val keyboard = object : BaseKeyboard(context, ThemePreset.MaterialLight, { listOf(definitions) }) {}
        val keys = (keyboard.getChildAt(0) as ViewGroup).children.filterIsInstance<AltTextKeyView>().toList()
        assertEquals(6, keys.size)
        // Geometry reserves dp(2) - 1 from Paint bounds; allow one additional
        // pixel because this assertion measures the rasterized Canvas ink.
        val minimumInkGap = (dp(context, 2) - 2).coerceAtLeast(0)
        for (changed in listOf(false, true)) {
            keys.forEachIndexed { index, key ->
                configureText(key, if (changed) Typeface.SERIF else Typeface.SANS_SERIF, 1f)
                key.mainText.text = if (changed) listOf("主", "Shift")[index % 2] else listOf("g", "É")[index % 2]
                val subTypeface = if (index % 2 == 0) Typeface.SERIF else Typeface.MONOSPACE
                key.altText.typeface = subTypeface
                key.altText1.typeface = if (index % 2 == 0) Typeface.MONOSPACE else Typeface.SERIF
            }
            repeat(3) {
                keys.forEach { it.refreshLayout() }
                keyboard.requestLayout()
                measureAndLayout(keyboard, 288, 64)
            }
            keys.forEachIndexed { index, key ->
                val (primary, secondary) = pairs[index / 2]
                val label = "row key=$index $primary/$secondary changed=$changed"
                val ink = readInk(label, key, Edges(primary.edge(), secondary.edge()))
                // Each glyph stays centered on its key and clear of its own labels.
                val above = ink.main.top - ink.top
                val below = ink.bottom - ink.main.bottom
                assertTrue("$label: gaps=$above/$below, minimum=$minimumInkGap",
                    above >= minimumInkGap && below >= minimumInkGap)
            }
            val faceCenter = keys.first().getChildAt(0).height / 2f
            val inkCenters = keys.map { key ->
                val ink = inkBounds(key.mainText)
                (ink.top + ink.bottom) / 2f
            }
            assertTrue("Main glyphs must stay centered across different label placements: $inkCenters",
                inkCenters.all { kotlin.math.abs(it - faceCenter) <= 1.5f })
        }
    }

    @Test
    fun stableHeightRefreshPreservesRowPlacementInTheImmediateDraw() = withPreferences {
        val definitions = listOf(
            AlphabetKey("g", "?", "É", displayText = "g"),
            AlphabetKey("É", "…", "主", displayText = "É"),
        )
        val keyboard = object : BaseKeyboard(context, ThemePreset.MaterialLight, { listOf(definitions) }) {}
        val keys = (keyboard.getChildAt(0) as ViewGroup).children.filterIsInstance<AltTextKeyView>().toList()
        keys.forEach { configureText(it, Typeface.SANS_SERIF, 1f) }
        for (height in listOf(64, 32, 72)) {
            repeat(3) {
                keyboard.requestLayout()
                measureAndLayout(keyboard, 96, height)
            }
            val before = keys.map { inkBounds(it.mainText) }
            // Model InputView's global-layout callback: refresh after row layout,
            // then draw immediately without another measure/layout pass.
            repeat(2) {
                keyboard.refreshAltTextLayouts()
                keys.forEach { assertNotNull(it.mainText.glyphPlacement) }
                assertEquals("Refresh must not draw a fallback position at height=$height",
                    before, keys.map { inkBounds(it.mainText) })
                measureAndLayout(keyboard, 96, height)
                assertEquals("The following layout must preserve the same ink",
                    before, keys.map { inkBounds(it.mainText) })
            }
        }
    }

    @Test
    fun unchangedOrdinaryLabelsDoNotRequestAnotherLayout() = withPreferences {
        for (position in listOf(PunctuationPosition.Top, PunctuationPosition.None)) {
            ThemeManager.prefs.punctuationPosition.setValue(position)
            val key = createKey(appearance())
            layoutKey(key)
            repeat(3) {
                // Force the parent through its callbacks with identical geometry.
                key.requestLayout()
                measureAndLayout(key, 48, 64)
                assertFalse("The key must finish layout for $position", key.isLayoutRequested)
                for (label in listOf(key.mainText, key.altText, key.altText1)) {
                    if (label.visibility == View.VISIBLE) {
                        assertFalse("Unchanged '${label.text}' must not request another layout",
                            label.isLayoutRequested)
                    }
                }
            }
        }
    }

    private enum class Edge { Top, Bottom }
    private data class Edges(val primary: Edge? = null, val secondary: Edge? = null)
    private data class Ink(
        val main: Rect,
        val top: Int,
        val bottom: Int,
        val faceCenter: Float,
    )
    private data class Sample(
        val main: String, val font: Typeface, val scale: Float, val density: Int, val fontScale: Float,
        val primary: AltTextPosition, val secondary: AltTextPosition, val uppercase: Boolean = false,
    )

    private fun AltTextPosition.edge() = when (this) {
        AltTextPosition.Top, AltTextPosition.TopRight -> Edge.Top
        AltTextPosition.Bottom -> Edge.Bottom
        AltTextPosition.TopBottom -> error("TopBottom needs two explicitly specified edges")
    }

    private fun AltTextPosition.punctuation() = when (this) {
        AltTextPosition.Top -> PunctuationPosition.Top
        AltTextPosition.TopRight -> PunctuationPosition.TopRight
        AltTextPosition.Bottom -> PunctuationPosition.Bottom
        AltTextPosition.TopBottom -> error("TopBottom is not a theme preference")
    }

    private fun appearance(
        main: String = "g", primary: String = "?", secondary: String? = "É", uppercase: Boolean = false,
        primaryPosition: AltTextPosition? = null, secondaryPosition: AltTextPosition? = null,
    ) = KeyDef.Appearance.AltText(
        displayText = main, character = "g", altText = primary, altText1 = secondary,
        supportsUppercaseHint = uppercase, textSize = 24f,
    ).apply {
        altTextPositionOverride = primaryPosition
        altText1PositionOverride = secondaryPosition
    }

    private fun createKey(
        definition: KeyDef.Appearance.AltText, ctx: Context = context,
        font: Typeface = Typeface.SANS_SERIF, scale: Float = 1f,
    ) = AltTextKeyView(ctx, ThemePreset.MaterialLight, definition).also { configureText(it, font, scale) }

    private fun configureText(key: AltTextKeyView, font: Typeface, scale: Float) {
        key.setTextScale(scale)
        key.mainText.typeface = font
        key.mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f * scale)
        // Keep these fixtures independent of a device's persisted custom font sizes.
        listOf(key.altText, key.altText1).forEach { label ->
            label.setTypeface(Typeface.SANS_SERIF, if (label === key.altText1) Typeface.NORMAL else Typeface.BOLD)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.666667f * scale)
        }
    }

    private fun layoutKey(key: AltTextKeyView, heightDp: Int = 64) {
        repeat(3) {
            key.refreshLayout()
            measureAndLayout(key, 48, heightDp)
        }
    }

    private fun measureAndLayout(view: View, widthDp: Int, heightDp: Int) {
        val width = dp(view.context, widthDp)
        val height = dp(view.context, heightDp)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun assertCentered(label: String, key: AltTextKeyView, edges: Edges, normalRow: Boolean = true) {
        val tolerance = maxOf(2f, key.context.resources.displayMetrics.density * 1.5f)
        assertGaps(
            label,
            readInk(label, key, edges),
            if (normalRow) dp(key.context, 2) - 1 else 0,
            tolerance
        )
    }

    private fun readInk(label: String, key: AltTextKeyView, edges: Edges): Ink {
        val appearance = key.getChildAt(0)
        val tops = mutableListOf<Rect>()
        val bottoms = mutableListOf<Rect>()
        val labels = listOf(Triple("primary", key.altText, edges.primary),
            Triple("secondary", key.altText1, edges.secondary))
        labels.forEach { (name, view, edge) ->
            assertEquals("$label: $name visibility", if (edge == null) View.GONE else View.VISIBLE, view.visibility)
            if (edge != null) {
                assertEquals("$label: $name parent", appearance, view.parent)
                val params = view.layoutParams as ConstraintLayout.LayoutParams
                assertEquals("$label: $name $edge anchor", ConstraintLayout.LayoutParams.PARENT_ID,
                    if (edge == Edge.Top) params.topToTop else params.bottomToBottom)
                val ink = inkBounds(view)
                assertTrue("$label: $name ink outside appearance: $ink",
                    ink.top >= key.vMargin - 1 && ink.bottom <= appearance.height - key.vMargin + 1)
                if (edge == Edge.Top) tops.add(ink) else bottoms.add(ink)
            }
        }
        // Use fixture-declared edges, not proximity to the midpoint or main View box.
        val top = tops.maxOfOrNull { it.bottom } ?: key.vMargin
        val bottom = bottoms.minOfOrNull { it.top } ?: appearance.height - key.vMargin
        assertTrue("$label: no remaining band [$top, $bottom]", bottom > top)
        assertEquals("$label: main parent", appearance, key.mainText.parent)
        val main = inkBounds(key.mainText)
        assertTrue("$label: invalid main scale ${key.mainText.textScaleX}",
            key.mainText.textScaleX.isFinite() && key.mainText.textScaleX > 0f)
        return Ink(
            main = main,
            top = top,
            bottom = bottom,
            faceCenter = appearance.height / 2f,
        )
    }

    private fun assertGaps(
        label: String,
        ink: Ink,
        minimum: Int,
        tolerance: Float = 2f
    ) {
        val above = ink.main.top - ink.top
        val below = ink.bottom - ink.main.bottom
        val message = "$label: main=${ink.main}, band=[${ink.top}, ${ink.bottom}], gaps=$above/$below"
        assertEquals(message, ink.faceCenter.toDouble(),
            ((ink.main.top + ink.main.bottom) / 2f).toDouble(), tolerance.toDouble())
        assertTrue("$message, minimum=$minimum", above >= minimum && below >= minimum)
    }

    private fun inkBounds(view: AutoScaleTextView): Rect {
        assertEquals("Expected visible text '${view.text}'", View.VISIBLE, view.visibility)
        assertTrue("Unmeasured text '${view.text}'", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val originalColor = view.currentTextColor
        try {
            view.setTextColor(Color.BLACK)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val bounds = Rect(bitmap.width, bitmap.height, 0, 0)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(pixels[y * bitmap.width + x]) > 0) {
                    bounds.left = minOf(bounds.left, x)
                    bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x + 1)
                    bounds.bottom = maxOf(bounds.bottom, y + 1)
                }
            }
            assertFalse("No visible ink for '${view.text}'", bounds.isEmpty)
            bounds.offset(view.left, view.top)
            return bounds
        } finally {
            view.setTextColor(originalColor)
            bitmap.recycle()
        }
    }

    private fun dp(ctx: Context, value: Int) = (value * ctx.resources.displayMetrics.density).roundToInt()

    private fun withPreferences(block: () -> Unit) {
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val punctuation = prefs.punctuationPosition.getValue()
            val uppercase = prefs.uppercasePosition.getValue()
            val margin = prefs.keyVerticalMargin.getValue()
            val landscapeMargin = prefs.keyVerticalMarginLandscape.getValue()
            val horizontalMargin = prefs.keyHorizontalMargin.getValue()
            val landscapeHorizontalMargin = prefs.keyHorizontalMarginLandscape.getValue()
            try {
                prefs.punctuationPosition.setValue(PunctuationPosition.Top)
                prefs.uppercasePosition.setValue(UppercasePosition.None)
                prefs.keyVerticalMargin.setValue(2)
                prefs.keyVerticalMarginLandscape.setValue(2)
                prefs.keyHorizontalMargin.setValue(2)
                prefs.keyHorizontalMarginLandscape.setValue(2)
                block()
            } finally {
                prefs.punctuationPosition.setValue(punctuation)
                prefs.uppercasePosition.setValue(uppercase)
                prefs.keyVerticalMargin.setValue(margin)
                prefs.keyVerticalMarginLandscape.setValue(landscapeMargin)
                prefs.keyHorizontalMargin.setValue(horizontalMargin)
                prefs.keyHorizontalMarginLandscape.setValue(landscapeHorizontalMargin)
            }
        }
    }
}
