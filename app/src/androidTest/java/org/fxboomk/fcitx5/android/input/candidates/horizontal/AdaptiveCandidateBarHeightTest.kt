/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates.horizontal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.candidates.CandidateItemUi
import org.fxboomk.fcitx5.android.input.font.FontProviderApi
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp

class AdaptiveCandidateBarHeightTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun topAnnotationsReserveTheirLineBeforeAnyCandidateIsPresent() = onMain {
        withFonts(bodySize = 36f, annotationSize = 12f) {
            val height = AdaptiveCandidateBarHeight(context, ThemePreset.MaterialLight)
            val right = height.heightDp(annotationOnTop = false, inlineMode = false)
            val top = height.heightDp(annotationOnTop = true, inlineMode = false)
            assertTrue("Top annotation space must be reserved from configuration, before typing", top > right)
            assertEquals("Returning to Right must recover its original natural height", right,
                height.heightDp(annotationOnTop = false, inlineMode = false))
        }
    }

    @Test
    fun changingFontsRecalculatesTheSameCalculatorWithoutRetainingItsPreviousHeight() = onMain {
        withFonts(bodySize = 20f, annotationSize = 8f) { provider ->
            val height = AdaptiveCandidateBarHeight(context, ThemePreset.MaterialLight)
            val original = height.heightDp(annotationOnTop = true, inlineMode = false)
            provider.fontSizeMap["cand_font_size"] = 60f
            provider.fontSizeMap["cand_comment_font_size"] = 24f
            FontProviders.markNeedsRefresh()
            val larger = height.heightDp(annotationOnTop = true, inlineMode = false)
            assertTrue("A new font generation must invalidate the cached height", larger > original)

            provider.fontSizeMap["cand_font_size"] = 20f
            provider.fontSizeMap["cand_comment_font_size"] = 8f
            FontProviders.markNeedsRefresh()
            assertEquals("Restoring font sizes must recover the exact earlier height", original,
                height.heightDp(annotationOnTop = true, inlineMode = false))
        }
    }

    @Test
    fun inlineCandidateScaleAndSystemFontScaleProduceTheirOwnFixedHeights() = onMain {
        withFonts(bodySize = 60f, annotationSize = 24f) {
            val height = AdaptiveCandidateBarHeight(context, ThemePreset.MaterialLight)
            val ordinary = height.heightDp(annotationOnTop = true, inlineMode = false)
            val inline = height.heightDp(annotationOnTop = true, inlineMode = true)
            assertTrue("The smaller inline font must fit in a smaller candidate row", inline < ordinary)
            assertEquals(ordinary, height.heightDp(annotationOnTop = true, inlineMode = false))

            val normalContext = scaledContext(1f)
            val largeContext = scaledContext(2f)
            val normal = AdaptiveCandidateBarHeight(normalContext, ThemePreset.MaterialLight)
                .heightDp(annotationOnTop = true, inlineMode = false)
            val large = AdaptiveCandidateBarHeight(largeContext, ThemePreset.MaterialLight)
                .heightDp(annotationOnTop = true, inlineMode = false)
            assertTrue("A larger system font scale must receive a larger fixed row", large > normal)
        }
    }

    @Test
    fun calculatedHeightFitsActualTopAndRightCandidatesWithoutChangingTheirFonts() = onMain {
        withFonts(bodySize = 36f, annotationSize = 12f) {
            val height = AdaptiveCandidateBarHeight(context, ThemePreset.MaterialLight)
            for (inline in listOf(false, true)) {
                for (top in listOf(false, true)) {
                    val heightDp = height.heightDp(annotationOnTop = top, inlineMode = inline)
                    val words = listOf(
                        CandidateWord("", "中国", "(zhōng guó)", isPinyinAnnotationOnTop = top),
                        CandidateWord("", "𰻞", "(biáng)", isPinyinAnnotationOnTop = top),
                        CandidateWord("", "我", ""),
                    )
                    for (word in words) {
                        val ui = CandidateItemUi(context, ThemePreset.MaterialLight).apply {
                            configureHorizontalHighlightSpacing(
                                outerPadding = context.dp(HORIZONTAL_CANDIDATE_OUTER_PADDING_DP),
                                highlightPadding = context.dp(HORIZONTAL_CANDIDATE_HIGHLIGHT_PADDING_DP),
                                verticalPadding = context.dp(HORIZONTAL_CANDIDATE_VERTICAL_PADDING_DP),
                            )
                            updateCandidate(word)
                            setFontScale(if (inline) 0.9f else 1f)
                        }
                        val bodySize = ui.text.textSize
                        val width = context.dp(300)
                        val allocatedHeight = context.dp(heightDp)
                        ui.root.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(allocatedHeight, View.MeasureSpec.EXACTLY),
                        )
                        ui.root.layout(0, 0, width, allocatedHeight)
                        assertEquals("Height fitting must preserve the requested candidate size",
                            bodySize, ui.text.textSize, 0.01f)
                        assertVerticalInkFits(ui, ui.text, "body ${word.text}, top=$top inline=$inline")
                        if (word.topPinyinAnnotation != null) {
                            assertVerticalInkFits(ui, ui.pinyinText, "annotation ${word.text}, inline=$inline")
                        }
                    }
                }
            }
        }
    }

    private fun assertVerticalInkFits(
        ui: CandidateItemUi,
        text: org.fxboomk.fcitx5.android.input.AutoScaleTextView,
        label: String,
    ) {
        val origin = Rect()
        ui.root.offsetDescendantRectToMyCoords(text, origin)
        val ink = text.renderedGlyphBounds()
        assertTrue("$label must fit vertically: origin=$origin ink=$ink height=${ui.root.height}",
            origin.top + ink.top >= -1f && origin.top + ink.bottom <= ui.root.height + 1f)
    }

    private fun scaledContext(scale: Float): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { fontScale = scale }
    )

    private fun withFonts(bodySize: Float, annotationSize: Float, block: (FontProviderApi) -> Unit) {
        val original = FontProviders.provider
        val provider = object : FontProviderApi {
            override fun clearCache() = Unit
            override val fontTypefaceMap = mutableMapOf<String, Typeface?>(
                "cand_font" to Typeface.SANS_SERIF,
                "cand_comment_font" to Typeface.SERIF,
            )
            override val fontSizeMap = mutableMapOf(
                "cand_font_size" to bodySize,
                "cand_comment_font_size" to annotationSize,
            )
        }
        try {
            FontProviders.provider = provider
            block(provider)
        } finally {
            FontProviders.provider = original
            FontProviders.markNeedsRefresh()
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
