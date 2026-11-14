/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.DefaultConfigProvider
import org.fxboomk.fcitx5.android.input.config.MemoryConfigProvider
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.fxboomk.fcitx5.android.input.keyboard.AltTextKeyView
import org.fxboomk.fcitx5.android.input.keyboard.BaseKeyboard
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.fxboomk.fcitx5.android.input.keyboard.KeyView
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinLayoutVariants
import org.fxboomk.fcitx5.android.input.keyboard.SpaceKeyLabelMode
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutEditorActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerDialog
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class KeyboardArrangementPickerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun previewsScrollAndSelectingACardMovesTheOnlyCheckWithoutChangingProfile() = withPreviewActivity { activity ->
        val prefs = AppPrefs.getInstance()
        val profile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val provider = ConfigProviders.provider
        val ui = onMain {
            prefs.internal.compactShuangpinInputMethods.setValue(setOf("shuangpin-ms"))
            KeyboardArrangementPreviewCache.clear()
            lateinit var picker: KeyboardArrangementPickerUi
            picker = KeyboardArrangementPickerUi(ContextThemeWrapper(activity, R.style.Theme_InputViewTheme),
                ThemePreset.MaterialLight) { entry, variant ->
                ShuangpinKeyboardLayouts.write(entry.uniqueName, variant)
                picker.setSelected(variant)
            }
            picker
        }
        showPreview(activity, ui)
        onMain {
            assertSelected(ui, ShuangpinKeyboardLayout.Full)
            assertNull("The active arrangement must wait for browsing", bitmapOrNull(ui, ShuangpinKeyboardLayout.Full))
            assertNotNull(bitmapOrNull(ui, ShuangpinKeyboardLayout.Compact18))
            assertTrue("The alternative arrangement must be shown first", cardCenterIsVisible(ui, ShuangpinKeyboardLayout.Compact18))
            assertTrue("The current arrangement must remain reachable",
                ui.canScrollHorizontally(-1) || ui.canScrollHorizontally(1))
        }
        loadBothPreviews(ui)
        onMain {
            val full = bitmap(ui, ShuangpinKeyboardLayout.Full)
            val compact = bitmap(ui, ShuangpinKeyboardLayout.Compact18)
            assertTrue(full.width > 0 && full.height > 0)
            assertFalse("26-key and 18-key previews must differ", full.sameAs(compact))
            assertTrue("Preview images must contain a rendered keyboard", pixelColors(full).size > 2)
            assertTrue("Preview content must be wider than its viewport",
                ui.getChildAt(0).width > ui.width - ui.paddingLeft - ui.paddingRight)
            assertTrue("Previews must expose images rather than interactive keyboard keys",
                ui.allViews.none { it is BaseKeyboard })
            assertSame(provider, ConfigProviders.provider)
            assertEquals(setOf("shuangpin-ms"), prefs.internal.compactShuangpinInputMethods.getValue())
            ui.cards.getValue(ShuangpinKeyboardLayout.Compact18).performClick()
            assertSelected(ui, ShuangpinKeyboardLayout.Compact18)
            assertEquals(setOf("shuangpin-ms", "shuangpin-xiaohe"),
                prefs.internal.compactShuangpinInputMethods.getValue())
            assertEquals(profile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
        }
        showPreview(activity, ui)
        onMain {
            assertSelected(ui, ShuangpinKeyboardLayout.Compact18)
            assertTrue("The alternative full arrangement must be shown after selection",
                cardCenterIsVisible(ui, ShuangpinKeyboardLayout.Full))
            ui.cards.getValue(ShuangpinKeyboardLayout.Full).performClick()
            assertSelected(ui, ShuangpinKeyboardLayout.Full)
            assertEquals(setOf("shuangpin-ms"), prefs.internal.compactShuangpinInputMethods.getValue())
            assertEquals(profile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
            ui.clearPreviews()
        }
    }

    @Test
    fun previewsReadCurrentProfileContentWithoutReplacingTheProviderOrActiveArrangement() = withPreviewActivity { activity ->
        val originalProvider = ConfigProviders.provider
        val originalArrangement = AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue()
        val data = onMain { LayoutDataManager(activity) }
        val fullRows = data.defaultPresetSnapshot().map { row -> row.map { it.toMutableMap() }.toMutableList() }
        val entries = mapOf("shuangpin-xiaohe" to fullRows,
            ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to data.compactDefaultRows())
        val firstProvider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(entries), originalProvider)
        val ui = onMain {
            ConfigProviders.provider = firstProvider
            TextKeyboard.invalidateLayoutCache()
            KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
                fail("Rendering or scrolling a preview must not select a layout")
            }
        }
        showPreview(activity, ui)
        val before = loadBothPreviews(ui)
        onMain { assertSame(firstProvider, ConfigProviders.provider) }

        fullRows.first().reverse()
        val secondProvider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(entries), originalProvider)
        onMain {
            ConfigProviders.provider = secondProvider
            TextKeyboard.invalidateLayoutCache()
        }
        showPreview(activity, ui)
        val after = loadBothPreviews(ui)
        onMain {
            assertFalse("The full preview must follow current profile edits", before.first().sameAs(after.first()))
            assertTrue("Changing only the full arrangement must preserve compact pixels", before.last().sameAs(after.last()))
            assertSame(secondProvider, ConfigProviders.provider)
            assertEquals(originalArrangement, AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue())
            ui.clearPreviews()
        }
    }

    @Test
    fun recordColdRepeatedAndNewUiPreviewRenderingTimings() = withPreviewActivity { activity ->
        val originalProvider = ConfigProviders.provider
        val originalArrangement = AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue()
        val data = onMain { LayoutDataManager(activity) }
        val memoryProvider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(mapOf(
            "shuangpin-xiaohe" to data.defaultPresetSnapshot(),
            ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to data.compactDefaultRows(),
        )), originalProvider)
        onMain {
            ConfigProviders.provider = memoryProvider
            TextKeyboard.invalidateLayoutCache()
            ShuangpinKeyboardLayouts.write("shuangpin-xiaohe", ShuangpinKeyboardLayout.Full)
            KeyboardArrangementPreviewCache.clear()
        }
        val createdUis = mutableListOf<KeyboardArrangementPickerUi>()
        var previousFull: Bitmap? = null
        var previousCompact: Bitmap? = null
        fun newUi() = KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
            fail("Measuring previews must not select a layout")
        }.also { createdUis += it }
        fun record(phase: String, elapsed: Long, ui: KeyboardArrangementPickerUi) {
            onMain {
                val full = bitmapOrNull(ui, ShuangpinKeyboardLayout.Full)
                val compact = bitmapOrNull(ui, ShuangpinKeyboardLayout.Compact18)
                val fullReused = full != null && full === previousFull
                val compactReused = compact != null && compact === previousCompact
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("preview_phase", phase)
                    putLong("preview_elapsed_ns", elapsed)
                    putInt("preview_rendered_count", listOfNotNull(full, compact).size)
                    putInt("full_bitmap_id", full?.let(System::identityHashCode) ?: 0)
                    putInt("compact_bitmap_id", compact?.let(System::identityHashCode) ?: 0)
                    putBoolean("full_reused", fullReused)
                    putBoolean("compact_reused", compactReused)
                    putString("stream", "PREVIEW_TIMING phase=$phase elapsed_ns=$elapsed " +
                        "rendered_count=${listOfNotNull(full, compact).size} " +
                        "full_id=${full?.let(System::identityHashCode) ?: 0} " +
                        "compact_id=${compact?.let(System::identityHashCode) ?: 0} " +
                        "full_reused=$fullReused compact_reused=$compactReused\n")
                })
                if (previousCompact != null) assertSame(previousCompact, compact)
                previousFull = full
                previousCompact = compact
            }
        }
        fun measureFirst(phase: String, createOrReuse: () -> KeyboardArrangementPickerUi): KeyboardArrangementPickerUi {
            val (ui, elapsed) = onMain {
                val start = SystemClock.elapsedRealtimeNanos()
                val picker = createOrReuse()
                picker.show(ime())
                mountPreview(activity, picker)
                picker to (SystemClock.elapsedRealtimeNanos() - start)
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertNull("Only the alternative arrangement may render before scrolling",
                    bitmapOrNull(ui, ShuangpinKeyboardLayout.Full))
                assertNotNull(bitmapOrNull(ui, ShuangpinKeyboardLayout.Compact18))
            }
            record(phase, elapsed, ui)
            return ui
        }
        try {
            val first = measureFirst("cold_first_only", ::newUi)
            measureFirst("same_ui_first_only") { first }
            val reopened = measureFirst("new_ui_first_only", ::newUi)
            val start = SystemClock.elapsedRealtimeNanos()
            revealPreview(reopened, ShuangpinKeyboardLayout.Full)
            record("after_scroll_second", SystemClock.elapsedRealtimeNanos() - start, reopened)
            onMain {
                assertSame(memoryProvider, ConfigProviders.provider)
                assertEquals(originalArrangement - "shuangpin-xiaohe",
                    AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue())
            }
        } finally {
            onMain {
                createdUis.forEach { it.clearPreviews() }
                ConfigProviders.provider = originalProvider
                TextKeyboard.invalidateLayoutCache()
                KeyboardArrangementPreviewCache.clear()
            }
        }
    }

    @Test
    fun lazyPreviewsBrowseTheActiveArrangementAndReuseBothImagesAfterReopening() = withPreviewActivity { activity ->
        val originalProvider = ConfigProviders.provider
        val data = onMain { LayoutDataManager(activity) }
        val ui = onMain {
            ConfigProviders.provider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(mapOf(
                "shuangpin-xiaohe" to data.defaultPresetSnapshot(),
                ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to data.compactDefaultRows(),
            )), originalProvider)
            TextKeyboard.invalidateLayoutCache()
            ShuangpinKeyboardLayouts.write("shuangpin-xiaohe", ShuangpinKeyboardLayout.Full)
            KeyboardArrangementPreviewCache.clear()
            KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
                fail("Browsing previews must not change the active arrangement")
            }
        }
        showPreview(activity, ui)
        val first = onMain {
            assertSelected(ui, ShuangpinKeyboardLayout.Full)
            assertNull(bitmapOrNull(ui, ShuangpinKeyboardLayout.Full))
            assertTrue(cardCenterIsVisible(ui, ShuangpinKeyboardLayout.Compact18))
            assertTrue("Peeking at the edge must not request the active card",
                !cardCenterIsVisible(ui, ShuangpinKeyboardLayout.Full))
            bitmap(ui, ShuangpinKeyboardLayout.Compact18)
        }
        onMain {
            ui.scrollTo(ui.cards.getValue(ShuangpinKeyboardLayout.Full).left, 0)
            assertNull("Scrolling must enqueue rendering rather than block the scroll callback",
                bitmapOrNull(ui, ShuangpinKeyboardLayout.Full))
        }
        await { bitmapOrNull(ui, ShuangpinKeyboardLayout.Full) != null }
        val active = onMain {
            assertSame(first, bitmap(ui, ShuangpinKeyboardLayout.Compact18))
            assertSelected(ui, ShuangpinKeyboardLayout.Full)
            bitmap(ui, ShuangpinKeyboardLayout.Full)
        }
        val reopened = onMain { KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
            fail("Reopening previews must not change the active arrangement")
        } }
        showPreview(activity, reopened)
        onMain { assertSame(first, bitmap(reopened, ShuangpinKeyboardLayout.Compact18)) }
        revealPreview(reopened, ShuangpinKeyboardLayout.Full)
        onMain { assertSame(active, bitmap(reopened, ShuangpinKeyboardLayout.Full)) }
        onMain {
            ShuangpinKeyboardLayouts.write("shuangpin-xiaohe", ShuangpinKeyboardLayout.Compact18)
            KeyboardArrangementPreviewCache.clear()
        }
        showPreview(activity, reopened)
        onMain {
            assertSelected(reopened, ShuangpinKeyboardLayout.Compact18)
            assertNull("An active 18-key arrangement must wait for browsing",
                bitmapOrNull(reopened, ShuangpinKeyboardLayout.Compact18))
            assertNotNull(bitmapOrNull(reopened, ShuangpinKeyboardLayout.Full))
            assertTrue(cardCenterIsVisible(reopened, ShuangpinKeyboardLayout.Full))
        }
        revealPreview(reopened, ShuangpinKeyboardLayout.Compact18)
        onMain { assertSelected(reopened, ShuangpinKeyboardLayout.Compact18) }
    }

    @Test
    fun pendingPreviewWorkDoesNotRenderIntoClearedOrDetachedCards() = withPreviewActivity { activity ->
        val data = onMain { LayoutDataManager(activity) }
        val originalProvider = ConfigProviders.provider
        onMain {
            ConfigProviders.provider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(mapOf(
                "shuangpin-xiaohe" to data.defaultPresetSnapshot(),
                ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to data.compactDefaultRows(),
            )), originalProvider)
            TextKeyboard.invalidateLayoutCache()
            ShuangpinKeyboardLayouts.write("shuangpin-xiaohe", ShuangpinKeyboardLayout.Full)
        }
        for (detach in listOf(false, true)) {
            val ui = onMain {
                KeyboardArrangementPreviewCache.clear()
                KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
                    fail("Cancelled preview work must not select an arrangement")
                }
            }
            showPreview(activity, ui)
            val oldImage = onMain {
                val image = previewImage(ui, ShuangpinKeyboardLayout.Full)
                assertNull(image.drawable)
                ui.scrollTo(ui.cards.getValue(ShuangpinKeyboardLayout.Full).left, 0)
                assertNull(image.drawable)
                if (detach) activity.setContentView(FrameLayout(activity)) else ui.clearPreviews()
                image
            }
            instrumentation.waitForIdleSync()
            onMain { assertNull("Stale work updated a discarded preview image", oldImage.drawable) }
        }
    }

    @Test
    fun cachedPreviewsFollowImeLayoutThemeAndSizeWhilePreservingSelectionReuse() = withPreviewActivity { activity ->
        val prefs = AppPrefs.getInstance().keyboard
        val themePrefs = ThemeManager.prefs
        val originalProvider = ConfigProviders.provider
        val originalRadius = themePrefs.keyRadius.getValue()
        val originalBorder = themePrefs.keyBorder.getValue()
        val originalSplit = prefs.splitKeyboardEnabled.getValue()
        val originalHeight = prefs.keyboardHeightPercent.getValue()
        val originalLandscapeHeight = prefs.keyboardHeightPercentLandscape.getValue()
        val data = onMain { LayoutDataManager(activity) }
        val fullRows = data.defaultPresetSnapshot().map { row -> row.toMutableList() }.toMutableList()
        val compactRows = data.compactDefaultRows()
        fun installLayout() = onMain {
            ConfigProviders.provider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(mapOf(
                "shuangpin-xiaohe" to fullRows,
                ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to compactRows,
            )), originalProvider)
            TextKeyboard.invalidateLayoutCache()
        }
        val ui = onMain { KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { entry, variant ->
            ShuangpinKeyboardLayouts.write(entry.uniqueName, variant)
        } }
        fun show(entry: InputMethodEntry = ime(), width: Int = 1080, height: Int = 720): List<Bitmap> {
            showPreview(activity, ui, entry, width, height)
            return loadBothPreviews(ui)
        }
        fun assertReused(expected: List<Bitmap>, actual: List<Bitmap>) {
            expected.zip(actual).forEach { (before, after) -> assertSame(before, after) }
        }
        try {
            onMain {
                themePrefs.keyBorder.setValue(true)
                themePrefs.keyRadius.setValue(0)
                prefs.splitKeyboardEnabled.setValue(false)
                prefs.keyboardHeightPercent.setValue(20)
                prefs.keyboardHeightPercentLandscape.setValue(20)
                ShuangpinKeyboardLayouts.write("shuangpin-xiaohe", ShuangpinKeyboardLayout.Full)
            }
            installLayout()
            onMain { KeyboardArrangementPreviewCache.clear() }
            val initial = show()
            assertReused(initial, show())

            onMain { ui.cards.getValue(ShuangpinKeyboardLayout.Compact18).performClick() }
            assertReused(initial, show())
            onMain { assertSelected(ui, ShuangpinKeyboardLayout.Compact18) }
            onMain { ui.cards.getValue(ShuangpinKeyboardLayout.Full).performClick() }
            assertReused(initial, show())
            onMain { assertSelected(ui, ShuangpinKeyboardLayout.Full) }

            val microsoft = show(ime("shuangpin-ms"))
            initial.zip(microsoft).forEach { (before, after) -> assertNotSame(before, after) }
            assertFalse("Microsoft's compact preview must include its semicolon", initial.last().sameAs(microsoft.last()))
            assertReused(initial, show())

            fullRows.first().reverse()
            installLayout()
            val edited = show()
            assertNotSame(initial.first(), edited.first())
            assertFalse("The cached full preview retained the previous key order", initial.first().sameAs(edited.first()))
            assertTrue("Editing only the full arrangement must preserve compact pixels", initial.last().sameAs(edited.last()))
            assertReused(edited, show())

            onMain { themePrefs.keyRadius.setValue(16) }
            val rounded = show()
            edited.zip(rounded).forEach { (before, after) -> assertNotSame(before, after) }
            assertFalse("Theme changes must affect preview pixels", edited.first().sameAs(rounded.first()))
            assertReused(rounded, show())

            val narrower = show(width = 960)
            rounded.zip(narrower).forEach { (before, after) ->
                assertNotSame(before, after)
                assertEquals(960, after.width)
            }
            val cardHeight = onMain { ui.cards.getValue(ShuangpinKeyboardLayout.Full).height }
            assertReused(narrower, show(width = 960, height = 840))
            onMain { assertTrue("Changing the panel height must resize its cards",
                ui.cards.getValue(ShuangpinKeyboardLayout.Full).height > cardHeight) }

            onMain {
                prefs.keyboardHeightPercent.setValue(30)
                prefs.keyboardHeightPercentLandscape.setValue(30)
            }
            val taller = show(width = 960, height = 840)
            narrower.zip(taller).forEach { (before, after) ->
                assertNotSame(before, after)
                assertTrue("Changing the keyboard height must resize its image", after.height > before.height)
            }
            assertReused(taller, show(width = 960, height = 840))
        } finally {
            onMain {
                ui.clearPreviews()
                themePrefs.keyRadius.setValue(originalRadius)
                themePrefs.keyBorder.setValue(originalBorder)
                prefs.splitKeyboardEnabled.setValue(originalSplit)
                prefs.keyboardHeightPercent.setValue(originalHeight)
                prefs.keyboardHeightPercentLandscape.setValue(originalLandscapeHeight)
                ConfigProviders.provider = originalProvider
                TextKeyboard.invalidateLayoutCache()
                KeyboardArrangementPreviewCache.clear()
            }
        }
    }

    @Test
    fun previewsMatchTheNormalKeyboardPathAndReuseWideSplitImages() = withPreviewActivity { activity ->
        val prefs = AppPrefs.getInstance().keyboard
        val originalProvider = ConfigProviders.provider
        val originalSplit = prefs.splitKeyboardEnabled.getValue()
        val originalThreshold = prefs.splitKeyboardThreshold.getValue()
        val originalGap = prefs.splitKeyboardGapPercent.getValue()
        val originalHeight = prefs.keyboardHeightPercent.getValue()
        val originalLandscapeHeight = prefs.keyboardHeightPercentLandscape.getValue()
        val originalSpaceLabel = prefs.spaceKeyLabelMode.getValue()
        try {
            val data = onMain { LayoutDataManager(activity) }
            val ui = onMain {
                ConfigProviders.provider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(mapOf(
                    "shuangpin-xiaohe" to data.defaultPresetSnapshot(),
                    ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to data.compactDefaultRows(),
                )), originalProvider)
                prefs.splitKeyboardEnabled.setValue(true)
                prefs.splitKeyboardThreshold.setValue(500)
                prefs.splitKeyboardGapPercent.setValue(20)
                prefs.keyboardHeightPercent.setValue(30)
                prefs.keyboardHeightPercentLandscape.setValue(30)
                prefs.spaceKeyLabelMode.setValue(SpaceKeyLabelMode.Default)
                TextKeyboard.invalidateLayoutCache()
                KeyboardArrangementPreviewCache.clear()
                KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
                    fail("Rendering previews must not select a layout")
                }
            }
            val density = activity.resources.displayMetrics.density
            val entry = ime()
            for (width in listOf((360 * density).toInt(), (800 * density).toInt())) {
                showPreview(activity, ui, entry, width)
                val images = loadBothPreviews(ui)
                ShuangpinKeyboardLayout.entries.zip(images).forEach { (variant, actual) ->
                    val reference = onMain { normalKeyboardPreview(activity, entry, variant, actual.width, actual.height) }
                    val actualFile = File.createTempFile("preview-$variant-$width-actual-", ".png", context.cacheDir)
                    val referenceFile = File.createTempFile("preview-$variant-$width-reference-", ".png", context.cacheDir)
                    var passed = false
                    try {
                        actualFile.outputStream().use { output -> assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                        referenceFile.outputStream().use { output -> assertTrue(reference.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                        val actualColors = pixelColors(actual).size
                        val referenceColors = pixelColors(reference).size
                        val same = actual.sameAs(reference)
                        instrumentation.sendStatus(2, Bundle().apply {
                            putString("preview_variant", variant.name)
                            putInt("preview_width", actual.width)
                            putInt("preview_height", actual.height)
                            putInt("preview_actual_colors", actualColors)
                            putInt("preview_reference_colors", referenceColors)
                            putBoolean("preview_same_pixels", same)
                            putString("preview_actual_png", actualFile.absolutePath)
                            putString("preview_reference_png", referenceFile.absolutePath)
                            putString("stream", "PREVIEW_DIAGNOSTIC variant=$variant width=${actual.width} height=${actual.height} " +
                                "actual_colors=$actualColors reference_colors=$referenceColors same=$same " +
                                "actual_png=${actualFile.absolutePath} reference_png=${referenceFile.absolutePath}\n")
                        })
                        assertTrue("The preview must render key pixels at width $width", actualColors > 2)
                        assertTrue("The preview factory changed $variant pixels at width $width", same)
                        passed = true
                    } finally {
                        reference.recycle()
                        if (passed) {
                            assertTrue("Could not remove the successful preview diagnostic", actualFile.delete())
                            assertTrue("Could not remove the successful reference diagnostic", referenceFile.delete())
                        }
                    }
                }
            }
            val wideWidth = (800 * density).toInt()
            assertTrue(SplitKeyboardStateManager.getInstance().shouldUseSplitKeyboard(wideWidth))
            val wide = onMain { ShuangpinKeyboardLayout.entries.map { bitmap(ui, it) } }
            showPreview(activity, ui, entry, wideWidth)
            val repeated = loadBothPreviews(ui)
            wide.zip(repeated).forEach { (before, after) -> assertSame(before, after) }
            val reopened = onMain { KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
                fail("Reopening previews must not select a layout")
            } }
            showPreview(activity, reopened, entry, wideWidth)
            val reopenedImages = loadBothPreviews(reopened)
            wide.zip(reopenedImages).forEach { (before, after) -> assertSame(before, after) }
        } finally {
            onMain {
                prefs.splitKeyboardEnabled.setValue(originalSplit)
                prefs.splitKeyboardThreshold.setValue(originalThreshold)
                prefs.splitKeyboardGapPercent.setValue(originalGap)
                prefs.keyboardHeightPercent.setValue(originalHeight)
                prefs.keyboardHeightPercentLandscape.setValue(originalLandscapeHeight)
                prefs.spaceKeyLabelMode.setValue(originalSpaceLabel)
                ConfigProviders.provider = originalProvider
                TextKeyboard.invalidateLayoutCache()
                KeyboardArrangementPreviewCache.clear()
            }
        }
    }

    @Test
    fun cachedPreviewInvalidatesWhenTheBackgroundFileIsReplacedAtTheSamePath() = withActivity { activity ->
        val file = File.createTempFile("preview-background-", ".png", activity.cacheDir)
        val theme = ThemePreset.MaterialLight.deriveCustomBackground(
            "preview-background-${UUID.randomUUID()}", file.absolutePath, file.absolutePath,
            brightness = 100, blurRadius = 0f,
        )
        var renders = 0
        fun writeBackground(color: Int, size: Int = 16) {
            val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try {
                file.outputStream().use { output -> assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            } finally {
                image.recycle()
            }
        }
        fun preview(): Bitmap = KeyboardArrangementPreviewCache.getOrRender(
            activity, theme, ime(), ShuangpinKeyboardLayout.Full, null, 16, 16, false,
        ) {
            renders++
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { image ->
                theme.backgroundDrawable().apply {
                    setBounds(0, 0, 16, 16)
                    draw(Canvas(image))
                }
            }
        }
        try {
            KeyboardArrangementPreviewCache.clear()
            writeBackground(Color.RED)
            val first = preview()
            assertEquals(Color.RED, first.getPixel(8, 8))
            assertSame(first, preview())
            assertEquals(1, renders)

            val originalModified = file.lastModified()
            writeBackground(Color.BLUE)
            assertTrue(file.setLastModified(originalModified + 2_000))
            val replaced = preview()
            assertNotSame("Replacing the background at the same path must invalidate its image", first, replaced)
            assertEquals(Color.BLUE, replaced.getPixel(8, 8))
            assertSame(replaced, preview())
            assertEquals(2, renders)

            val unchangedModified = file.lastModified()
            val previousLength = file.length()
            writeBackground(Color.GREEN, size = 64)
            assertTrue("The replacement must exercise a different file length", previousLength != file.length())
            assertTrue(file.setLastModified(unchangedModified))
            assertEquals(unchangedModified, file.lastModified())
            val resized = preview()
            assertNotSame("A changed file length must invalidate the background even with an unchanged timestamp", replaced, resized)
            assertEquals(Color.GREEN, resized.getPixel(8, 8))
            assertSame(resized, preview())
            assertEquals(3, renders)
        } finally {
            KeyboardArrangementPreviewCache.clear()
            if (file.exists()) assertTrue("Could not remove the temporary preview background", file.delete())
        }
    }

    @Test
    fun profilePickerWorksFromTheImeThemeAndKeepsTheArrangement() = withActivity { activity ->
        val prefs = AppPrefs.getInstance()
        val originalArrangement = prefs.internal.compactShuangpinInputMethods.getValue()
        prefs.keyboard.textKeyboardLayoutProfile.setValue("status-area-test-${UUID.randomUUID()}")
        var selectedProfile: String? = null
        val themed = ContextThemeWrapper(activity, R.style.Theme_InputViewTheme)
        val dialog = TextKeyboardLayoutProfilePickerDialog.build(themed, onProfileSelected = { selectedProfile = it })
        dialog.show()
        try {
            dialog.listView.performItemClick(dialog.listView.getChildAt(0), 0, 0)
            assertEquals(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, selectedProfile)
            assertEquals(selectedProfile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
            assertEquals(originalArrangement, prefs.internal.compactShuangpinInputMethods.getValue())
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
        }
        assertTrue(KeyboardArrangementWindow.supports(ime()))
        assertFalse(KeyboardArrangementWindow.supports(ime("pinyin")))
        assertFalse(KeyboardArrangementWindow.supports(ime("rime", "rime")))
        assertFalse(KeyboardArrangementWindow.supports(ime(addon = "test")))
    }

    @Test
    fun statusAreaSelectingAPreviewImmediatelyReturnsToTheChosenArrangement() {
        val prefs = AppPrefs.getInstance()
        val originalArrangement = prefs.internal.compactShuangpinInputMethods.getValue()
        val connectionName = "KeyboardArrangementPickerUiTest"
        val connection = FcitxDaemon.connect(connectionName)
        val activity = instrumentation.startActivitySync(
            Intent(context, GestureEditingTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as GestureEditingTestActivity
        var originalIme: InputMethodEntry? = null
        var originalEnabledIme: Array<String>? = null
        var service: FcitxInputMethodService? = null
        var originalShiftLock: Boolean? = null
        try {
            lateinit var editor: EditText
            onMain {
                editor = EditText(activity).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText("preview selection preserves editor")
                }
                activity.setContentView(editor)
                editor.requestFocus()
            }
            await { editor.isShown && editor.hasWindowFocus() }
            onMain { activity.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            await { runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true }
            val activeService = requireNotNull(runningService()).also { service = it }
            originalShiftLock = activeService.isVirtualShiftLockOn()
            originalIme = connection.runImmediately { inputMethodEntryCached }
            val enabled = connection.runImmediately { enabledIme() }
            originalEnabledIme = enabled.map { it.uniqueName }.toTypedArray()
            val target = enabled.firstOrNull { KeyboardArrangementWindow.supports(it) }
                ?: connection.runImmediately { availableIme() }.first { KeyboardArrangementWindow.supports(it) }
            if (enabled.none { it.uniqueName == target.uniqueName }) {
                connection.runImmediately { setEnabledIme(enabled.map { it.uniqueName }.toTypedArray() + target.uniqueName) }
            }
            connection.runImmediately { activateIme(target.uniqueName) }
            await { connection.runImmediately { inputMethodEntryCached.uniqueName } == target.uniqueName }
            val wm = windowManager(activeService)
            onMain {
                activeService.setVirtualShiftLockState(true)
                activeService.inputView!!.executeButtonAction("more")
            }
            await { (wm.currentWindowOrNull() as? StatusAreaWindow)?.view?.adapter?.let { adapter ->
                (adapter as StatusAreaAdapter).entries.any {
                    it is StatusAreaEntry.ActionEntry && it.buttonAction.id == "keyboard_arrangement"
                }
            } == true }
            onMain {
                val status = wm.currentWindowOrNull() as StatusAreaWindow
                val adapter = status.view.adapter as StatusAreaAdapter
                val entry = adapter.entries.filterIsInstance<StatusAreaEntry.ActionEntry>()
                    .single { it.buttonAction.id == "keyboard_arrangement" }
                adapter.onItemClick(status.view, entry)
            }
            await { wm.currentWindowOrNull() is KeyboardArrangementWindow }
            val ui = (wm.currentWindowOrNull() as KeyboardArrangementWindow).onCreateView() as KeyboardArrangementPickerUi
            await { ui.cards.size == 2 }
            loadBothPreviews(ui)
            onMain {
                assertFalse("Virtual Shift-lock must not turn the 18-key preview into a 26-key image",
                    bitmap(ui, ShuangpinKeyboardLayout.Full).sameAs(bitmap(ui, ShuangpinKeyboardLayout.Compact18)))
                activeService.setVirtualShiftLockState(false)
                ui.cards.getValue(ShuangpinKeyboardLayout.Compact18).performClick()
                assertTrue("Selecting a preview must immediately return to the keyboard",
                    wm.currentWindowOrNull() is KeyboardWindow)
                assertEquals(ShuangpinKeyboardLayout.Compact18, ShuangpinKeyboardLayouts.read(target.uniqueName))
                assertEquals("preview selection preserves editor", editor.text.toString())
            }
            await { hasGroupKeys(activeService) }
            onMain { activeService.inputView!!.executeButtonAction("keyboard_arrangement") }
            await { wm.currentWindowOrNull() is KeyboardArrangementWindow }
            await { ((wm.currentWindowOrNull() as KeyboardArrangementWindow).onCreateView()
                as KeyboardArrangementPickerUi).cards.size == 2 }
            onMain {
                val reopened = (wm.currentWindowOrNull() as KeyboardArrangementWindow).onCreateView() as KeyboardArrangementPickerUi
                assertSelected(reopened, ShuangpinKeyboardLayout.Compact18)
                reopened.cards.getValue(ShuangpinKeyboardLayout.Full).performClick()
                assertTrue("Selecting the full preview must immediately return to the keyboard",
                    wm.currentWindowOrNull() is KeyboardWindow)
                assertEquals(ShuangpinKeyboardLayout.Full, ShuangpinKeyboardLayouts.read(target.uniqueName))
            }
            await { !hasGroupKeys(activeService) }
            onMain { assertEquals("preview selection preserves editor", editor.text.toString()) }
        } finally {
            originalEnabledIme?.let { entries -> connection.runImmediately { setEnabledIme(entries) } }
            originalIme?.let { entry -> connection.runImmediately { activateIme(entry.uniqueName) } }
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(originalArrangement)
                originalShiftLock?.let { locked -> service?.setVirtualShiftLockState(locked) }
                service?.let { (windowManager(it).getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards() }
                activity.finish()
            }
            FcitxDaemon.disconnect(connectionName)
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun titleEditButtonOpensTheActiveProfilesCurrentShuangpinArrangement() {
        val prefs = AppPrefs.getInstance()
        val originalArrangement = prefs.internal.compactShuangpinInputMethods.getValue()
        val originalProfile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val originalProvider = ConfigProviders.provider
        val profile = "arrangement-edit-test-${UUID.randomUUID()}"
        val file = requireNotNull(UserConfigFiles.textKeyboardLayoutJson(profile))
        val connectionName = "KeyboardArrangementTitleEditTest"
        val connection = FcitxDaemon.connect(connectionName)
        val activity = instrumentation.startActivitySync(
            Intent(context, GestureEditingTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as GestureEditingTestActivity
        var originalIme: InputMethodEntry? = null
        var originalEnabledIme: Array<String>? = null
        var service: FcitxInputMethodService? = null
        var originalShiftLock: Boolean? = null
        try {
            val data = LayoutDataManager(context).apply { loadFromFile(null) }
            file.parentFile?.mkdirs()
            file.writeText(data.exportCurrentJsonString())
            val originalJson = file.readText()
            lateinit var editor: EditText
            onMain {
                prefs.keyboard.textKeyboardLayoutProfile.setValue(profile)
                ConfigProviders.provider = DefaultConfigProvider
                TextKeyboard.invalidateLayoutCache()
                editor = EditText(activity).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText("layout editing preserves editor")
                }
                activity.setContentView(editor)
                editor.requestFocus()
            }
            await { editor.isShown && editor.hasWindowFocus() }
            onMain { activity.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            await { runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true }
            val activeService = requireNotNull(runningService()).also { service = it }
            originalShiftLock = activeService.isVirtualShiftLockOn()
            originalIme = connection.runImmediately { inputMethodEntryCached }
            val enabled = connection.runImmediately { enabledIme() }
            originalEnabledIme = enabled.map { it.uniqueName }.toTypedArray()
            val target = connection.runImmediately { availableIme() }.first { it.uniqueName == "shuangpin-xiaohe" }
            if (enabled.none { it.uniqueName == target.uniqueName }) {
                connection.runImmediately { setEnabledIme(enabled.map { it.uniqueName }.toTypedArray() + target.uniqueName) }
            }
            connection.runImmediately { activateIme(target.uniqueName) }
            await { connection.runImmediately { inputMethodEntryCached.uniqueName } == target.uniqueName }
            val wm = windowManager(activeService)
            onMain { activeService.setVirtualShiftLockState(false) }
            for (variant in listOf(ShuangpinKeyboardLayout.Compact18, ShuangpinKeyboardLayout.Full)) {
                onMain {
                    ShuangpinKeyboardLayouts.write(target.uniqueName, variant)
                    (wm.getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards()
                    activeService.inputView!!.executeButtonAction("keyboard_arrangement")
                }
                await { wm.currentWindowOrNull() is KeyboardArrangementWindow }
                val window = wm.currentWindowOrNull() as KeyboardArrangementWindow
                await { (window.onCreateView() as KeyboardArrangementPickerUi).cards.size == 2 }
                val monitor = instrumentation.addMonitor(TextKeyboardLayoutEditorActivity::class.java.name, null, false)
                var layoutEditor: TextKeyboardLayoutEditorActivity? = null
                try {
                    onMain {
                        val button = window.onCreateBarExtension().allViews.single {
                            it.contentDescription == context.getString(R.string.edit_text_keyboard_layout)
                        }
                        assertTrue("The edit button must be mounted in the visible title bar", button.isShown)
                        assertTrue(button.performClick())
                    }
                    layoutEditor = monitor.waitForActivityWithTimeout(8000) as? TextKeyboardLayoutEditorActivity
                    assertNotNull("The title button did not open the layout editor", layoutEditor)
                    instrumentation.waitForIdleSync()
                    val opened = requireNotNull(layoutEditor)
                    onMain {
                        assertEquals(profile, opened.intent.getStringExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_PROFILE))
                        assertEquals(target.uniqueName, opened.intent.getStringExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_LAYOUT))
                        assertEquals(target.displayName, opened.intent.getStringExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_DISPLAY_NAME))
                        assertEquals(variant.name, opened.intent.getStringExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_LAYOUT_VARIANT))
                        assertFalse(opened.intent.hasExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_SUBMODE))
                        assertTrue(opened.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                        assertEquals(profile, editorField(opened, "currentLayoutProfile"))
                        assertEquals(target.uniqueName, editorField(opened, "currentLayout"))
                        assertEquals(variant, editorField(opened, "selectedLayoutVariant"))
                        @Suppress("UNCHECKED_CAST")
                        val rows = editorField(opened, "currentRowsRef") as List<List<Map<String, Any?>>>
                        assertEquals(if (variant == ShuangpinKeyboardLayout.Compact18) 18 else 0,
                            rows.flatten().count { it["type"] == "ShuangpinGroupKey" })
                        if (variant == ShuangpinKeyboardLayout.Full) {
                            assertEquals(26, rows.flatten().count { it["type"] == "AlphabetKey" })
                        }
                        assertEquals(variant, ShuangpinKeyboardLayouts.read(target.uniqueName))
                        assertEquals(profile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
                    }
                } finally {
                    instrumentation.removeMonitor(monitor)
                    onMain { layoutEditor?.finish() }
                    instrumentation.waitForIdleSync()
                }
                await { editor.isShown && editor.hasWindowFocus() }
                onMain {
                    editor.requestFocus()
                    activity.getSystemService(InputMethodManager::class.java)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                }
                await { activeService.isInputViewShown && activeService.currentInputEditorInfo?.fieldId == editor.id }
                onMain { assertEquals("layout editing preserves editor", editor.text.toString()) }
            }
            assertEquals("Opening the editor must not modify the profile", originalJson, file.readText())
        } finally {
            originalEnabledIme?.let { entries -> connection.runImmediately { setEnabledIme(entries) } }
            originalIme?.let { entry -> connection.runImmediately { activateIme(entry.uniqueName) } }
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(originalArrangement)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(originalProfile)
                ConfigProviders.provider = originalProvider
                TextKeyboard.invalidateLayoutCache()
                originalShiftLock?.let { locked -> service?.setVirtualShiftLockState(locked) }
                service?.let { (windowManager(it).getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards() }
                activity.finish()
            }
            FcitxDaemon.disconnect(connectionName)
            instrumentation.waitForIdleSync()
            if (file.exists()) assertTrue("Could not remove the temporary edit profile", file.delete())
            file.parentFile?.listFiles { candidate -> candidate.name.startsWith("${file.nameWithoutExtension}_backup_") }
                ?.forEach { assertTrue("Could not remove the temporary edit profile backup", it.delete()) }
        }
    }

    private fun editorField(activity: TextKeyboardLayoutEditorActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun assertSelected(ui: KeyboardArrangementPickerUi, expected: ShuangpinKeyboardLayout) {
        assertEquals(listOf(expected), ui.cards.filterValues { it.isSelected }.keys.toList())
        ui.cards.forEach { (layout, card) ->
            val check = card.children.filterIsInstance<ImageView>().last()
            assertEquals(if (layout == expected) View.VISIBLE else View.INVISIBLE, check.visibility)
            assertEquals(Gravity.TOP or Gravity.END, (check.layoutParams as FrameLayout.LayoutParams).gravity)
        }
    }

    private fun previewImage(ui: KeyboardArrangementPickerUi, variant: ShuangpinKeyboardLayout): ImageView =
        ui.cards.getValue(variant).children.filterIsInstance<ImageView>().first()

    private fun bitmapOrNull(ui: KeyboardArrangementPickerUi, variant: ShuangpinKeyboardLayout): Bitmap? =
        ui.cards[variant]?.children?.filterIsInstance<ImageView>()?.firstOrNull()
            ?.drawable.let { (it as? BitmapDrawable)?.bitmap }

    private fun bitmap(ui: KeyboardArrangementPickerUi, variant: ShuangpinKeyboardLayout): Bitmap =
        requireNotNull(bitmapOrNull(ui, variant)) { "$variant preview has not been rendered" }

    private fun cardCenterIsVisible(ui: KeyboardArrangementPickerUi, variant: ShuangpinKeyboardLayout): Boolean {
        val card = ui.cards.getValue(variant)
        val center = card.left + card.width / 2
        return center in ui.scrollX..(ui.scrollX + ui.width - ui.paddingLeft - ui.paddingRight)
    }

    private fun mountPreview(activity: MainActivity, ui: KeyboardArrangementPickerUi, width: Int = 1080, height: Int = 720) {
        if (ui.isAttachedToWindow) {
            ui.layoutParams = FrameLayout.LayoutParams(width, height)
        } else {
            (ui.parent as? ViewGroup)?.removeView(ui)
            val host = FrameLayout(activity).apply {
                // IME panels do not take editor focus. Keep Activity focus out of the preview cards too.
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isFocusableInTouchMode = true
                addView(ui, FrameLayout.LayoutParams(width, height))
            }
            activity.setContentView(host)
            host.requestFocus()
        }
        layout(ui, width, height)
    }

    private fun showPreview(
        activity: MainActivity,
        ui: KeyboardArrangementPickerUi,
        entry: InputMethodEntry = ime(),
        width: Int = 1080,
        height: Int = 720,
    ) {
        onMain {
            ui.show(entry)
            mountPreview(activity, ui, width, height)
        }
        instrumentation.waitForIdleSync()
        await { ui.isAttachedToWindow && ui.cards.size == 2 && ui.width == width && ui.height == height }
    }

    private fun revealPreview(ui: KeyboardArrangementPickerUi, variant: ShuangpinKeyboardLayout) {
        onMain { ui.scrollTo(ui.cards.getValue(variant).left, 0) }
        await { bitmapOrNull(ui, variant) != null }
    }

    private fun loadBothPreviews(ui: KeyboardArrangementPickerUi): List<Bitmap> {
        instrumentation.waitForIdleSync()
        ShuangpinKeyboardLayout.entries.forEach { revealPreview(ui, it) }
        return onMain { ShuangpinKeyboardLayout.entries.map { bitmap(ui, it) } }
    }

    private fun pixelColors(bitmap: Bitmap): Set<Int> = buildSet {
        for (y in 0 until bitmap.height step 13) for (x in 0 until bitmap.width step 13) add(bitmap.getPixel(x, y))
    }

    private fun normalKeyboardPreview(
        activity: MainActivity,
        entry: InputMethodEntry,
        variant: ShuangpinKeyboardLayout,
        width: Int,
        height: Int,
    ): Bitmap {
        // Keep the previous construction path as an independent rendering reference.
        val keyboard = TextKeyboard(activity, ThemePreset.MaterialLight, entry).apply {
            layoutVariantOverride = variant
            background = ThemePreset.MaterialLight.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
            onInputMethodUpdate(entry)
        }
        layout(keyboard, width, height)
        val keys = keyboard.allViews.filterIsInstance<KeyView>().toList()
        val zeroMeasured = keys.count { it.measuredWidth <= 0 || it.measuredHeight <= 0 }
        val zeroLayout = keys.count { it.width <= 0 || it.height <= 0 }
        val visible = keys.count { it.visibility == View.VISIBLE }
        val children = keyboard.children.map {
            "${it.javaClass.simpleName}:${it.measuredWidth}x${it.measuredHeight}->${it.width}x${it.height}v${it.visibility}"
        }.take(6).joinToString(",")
        val keySizes = keys.map {
            "${it.measuredWidth}x${it.measuredHeight}->${it.width}x${it.height}v${it.visibility}"
        }.distinct().take(8).joinToString(",")
        instrumentation.sendStatus(2, Bundle().apply {
            putInt("preview_reference_child_count", keyboard.childCount)
            putInt("preview_reference_key_count", keys.size)
            putInt("preview_reference_zero_measured_keys", zeroMeasured)
            putInt("preview_reference_zero_layout_keys", zeroLayout)
            putInt("preview_reference_visible_keys", visible)
            putString("stream", "PREVIEW_REFERENCE_GEOMETRY variant=$variant width=$width height=$height " +
                "children=${keyboard.childCount} keys=${keys.size} visible_keys=$visible " +
                "zero_measured=$zeroMeasured zero_layout=$zeroLayout child_sizes=[$children] key_sizes=[$keySizes]\n")
        })
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { keyboard.draw(Canvas(it)) }
    }

    private fun layout(view: View, width: Int = 1080, height: Int = 720) {
        repeat(2) { pass ->
            if (pass == 1 && view is BaseKeyboard && view.children.any { it.width == 0 || it.height == 0 }) {
                // Split-row replacement can lose the children's layout request during the first traversal.
                view.allViews.forEach { it.forceLayout() }
            }
            // onSizeChanged adds the cards after the initial measure; emulate the next traversal.
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
    }

    private fun ime(name: String = "shuangpin-xiaohe", addon: String = "pinyin") =
        InputMethodEntry(name, name, "", "", "", "zh", addon, false)

    private fun withActivity(block: (MainActivity) -> Unit) {
        val prefs = AppPrefs.getInstance()
        val original = prefs.internal.compactShuangpinInputMethods.getValue()
        val originalProfile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val originalProvider = ConfigProviders.provider
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            onMain { block(activity) }
        } finally {
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(original)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(originalProfile)
                ConfigProviders.provider = originalProvider
                TextKeyboard.clearCachedKeyDefLayouts()
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun withPreviewActivity(block: (MainActivity) -> Unit) {
        val prefs = AppPrefs.getInstance()
        val original = prefs.internal.compactShuangpinInputMethods.getValue()
        val originalProfile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val originalProvider = ConfigProviders.provider
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            block(activity)
        } finally {
            onMain {
                activity.setContentView(FrameLayout(activity))
                prefs.internal.compactShuangpinInputMethods.setValue(original)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(originalProfile)
                ConfigProviders.provider = originalProvider
                TextKeyboard.invalidateLayoutCache()
                KeyboardArrangementPreviewCache.clear()
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun windowManager(service: FcitxInputMethodService): InputWindowManager =
        service.inputView!!.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
            .get(service.inputView) as InputWindowManager

    private fun runningService(): FcitxInputMethodService? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun hasGroupKeys(service: FcitxInputMethodService): Boolean =
        service.inputView!!.allViews.filterIsInstance<AltTextKeyView>().any {
            (it.def as? KeyDef.Appearance.AltText)?.isLetterGroup == true
        }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            onMain { result = condition() }
            if (result) return
            SystemClock.sleep(50)
        }
        onMain { assertTrue("Timed out waiting for keyboard arrangement UI", condition()) }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
