/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.font.FontProviderApi
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Compare the same warm candidate bind/measure workload before and after hot-path changes. */
class CandidateRenderingPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun reportWarmCandidateBindingCostForPlainRightAndTopAnnotations() = onMain {
        withCountingFonts { provider ->
            for (placement in Placement.entries) {
                val ui = candidateUi()
                val candidates = placement.candidates()
                repeat(WARMUP_COUNT) { index -> bindAndLayout(ui, candidates[index % 2], index % 2 == 0) }
                provider.resolutions = 0
                val samples = LongArray(SAMPLE_COUNT) { index ->
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    bindAndLayout(ui, candidates[index % 2], index % 2 == 0)
                    SystemClock.elapsedRealtimeNanos() - startedAt
                }
                val sorted = samples.sorted()
                Log.i("CandidatePerf", "CANDIDATE_PERF {\"placement\":\"" + placement.name.lowercase() +
                    "\",\"samples\":" + samples.size +
                    ",\"median_ns\":" + sorted[sorted.size / 2] +
                    ",\"p95_ns\":" + sorted[ceil(sorted.size * 0.95).toInt() - 1] +
                    ",\"total_ns\":" + samples.sum() +
                    ",\"font_resolutions\":" + provider.resolutions + "}")
                assertTrue("The probe must exercise measured candidate views", ui.root.measuredWidth > 0)
            }
        }
    }

    @Test
    fun rebindingIdenticalCandidatesKeepsMeasurementsStableAndAvoidsRepeatedFontResolution() = onMain {
        withCountingFonts { provider ->
            val failures = mutableListOf<String>()
            for (placement in Placement.entries) {
                val ui = candidateUi()
                val candidate = placement.candidates().first()
                repeat(WARMUP_COUNT) { bindAndLayout(ui, candidate, active = false) }
                provider.resolutions = 0
                val measuredSize = ui.root.measuredWidth to ui.root.measuredHeight
                var measurementChanges = 0
                repeat(IDENTICAL_BIND_COUNT) {
                    bind(ui, candidate, active = false)
                    layout(ui.root)
                    if ((ui.root.measuredWidth to ui.root.measuredHeight) != measuredSize) {
                        measurementChanges++
                    }
                }
                println("CANDIDATE_IDENTICAL_BIND {\"placement\":\"" + placement.name.lowercase() +
                    "\",\"binds\":" + IDENTICAL_BIND_COUNT +
                    ",\"measurement_changes\":" + measurementChanges +
                    ",\"font_resolutions\":" + provider.resolutions + "}")
                if (measurementChanges != 0 || provider.resolutions > 2) {
                    failures += "${placement.name}: $measurementChanges measurement changes and " +
                        "${provider.resolutions} font resolutions for $IDENTICAL_BIND_COUNT identical binds"
                }
            }
            assertTrue(failures.joinToString("; "), failures.isEmpty())
        }
    }

    private enum class Placement {
        Plain, Right, Top;

        fun candidates(): List<CandidateWord> = listOf(
            CandidateWord("", "你", if (this == Plain) "" else "(nǐ)",
                isPinyinAnnotationOnTop = this == Top),
            CandidateWord("", "好", if (this == Plain) "" else "(hǎo)",
                isPinyinAnnotationOnTop = this == Top),
        )
    }

    private fun candidateUi() = CandidateItemUi(context, ThemePreset.MaterialLight).apply {
        setFontScale(1f)
    }

    private fun bind(ui: CandidateItemUi, candidate: CandidateWord, active: Boolean) {
        ui.applyConfiguredTypeface()
        ui.updateCandidate(candidate)
        ui.setActive(active)
    }

    private fun bindAndLayout(ui: CandidateItemUi, candidate: CandidateWord, active: Boolean) {
        bind(ui, candidate, active)
        layout(ui.root)
    }

    private fun layout(view: View) {
        val width = (360 * context.resources.displayMetrics.density).roundToInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private class CountingFonts(private val delegate: FontProviderApi) : FontProviderApi by delegate {
        var resolutions = 0
        override val fontSizeMap = delegate.fontSizeMap.toMutableMap().apply {
            put("cand_font_size", 20f)
            put("cand_comment_font_size", 10f)
        }
        override fun clearCache() = Unit
        override fun resolveTypeface(key: String, current: Typeface?): Typeface {
            resolutions++
            return delegate.resolveTypeface(key, current)
        }
    }

    private fun withCountingFonts(block: (CountingFonts) -> Unit) {
        val original = FontProviders.provider
        try {
            val provider = CountingFonts(original)
            FontProviders.provider = provider
            block(provider)
        } finally {
            FontProviders.provider = original
            FontProviders.markNeedsRefresh()
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val WARMUP_COUNT = 20
        const val SAMPLE_COUNT = 80
        const val IDENTICAL_BIND_COUNT = 16
    }
}
