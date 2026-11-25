/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.config.ConfigProvider
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.font.DefaultFontProvider
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual editor controls while preserving the user's fontset. */
@SdkSuppress(minSdkVersion = 29)
class FontsetEditorActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun oversizedAnnotationLoadsCappedAndPickerUsesTheBodyLimit() = withFontset(20f, 72f) { activity, saved ->
        onMain {
            val chinese = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                setLocale(Locale.SIMPLIFIED_CHINESE)
            })
            assertEquals("候选正文字体", chinese.getString(R.string.fontset_entry_candidate))
            assertEquals(activity.getString(R.string.fontset_entry_candidate),
                title(activity, R.string.fontset_entry_candidate).text.toString())
            assertSize(activity, R.string.fontset_entry_candidate, 20)
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 10)
        }
        openSizeEditor(activity, R.string.fontset_entry_candidate_annotation)
        onMain {
            assertPickerLimits(activity, minimum = 1, maximum = 10)
            assertEquals(9, seekBar().progress)
        }
        confirmSize(5, minimum = 1)
        onMain {
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 5)
        }
        save(activity)
        assertSavedSizes(saved, body = 20f, annotation = 5f)
    }

    @Test
    fun bodyDecreaseAndBothResetActionsKeepAnnotationWithinHalfTheBody() = withFontset(72f, 36f) { activity, saved ->
        openSizeEditor(activity, R.string.fontset_entry_candidate)
        resetSize()
        onMain {
            assertSize(activity, R.string.fontset_entry_candidate, 20)
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 10)
        }
        openSizeEditor(activity, R.string.fontset_entry_candidate)
        confirmSize(72, minimum = 8)
        openSizeEditor(activity, R.string.fontset_entry_candidate_annotation)
        onMain {
            assertPickerLimits(activity, minimum = 1, maximum = 36)
        }
        confirmSize(36, minimum = 1)
        onMain {
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 36)
        }
        openSizeEditor(activity, R.string.fontset_entry_candidate)
        confirmSize(8, minimum = 8)
        onMain {
            assertSize(activity, R.string.fontset_entry_candidate, 8)
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 4)
        }
        openSizeEditor(activity, R.string.fontset_entry_candidate_annotation)
        onMain {
            assertPickerLimits(activity, minimum = 1, maximum = 4)
        }
        resetSize()
        onMain {
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 4)
        }
        save(activity)
        assertSavedSizes(saved, body = 8f, annotation = 4f)
    }

    @Test
    fun smallAnnotationSizesSurviveLoadingEditingAndSaving() = withFontset(8f, 3f) { activity, saved ->
        onMain {
            assertEquals(3f, FontProviders.getFontSize("cand_comment_font", 11f), 0.01f)
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 3)
        }
        openSizeEditor(activity, R.string.fontset_entry_candidate_annotation)
        onMain {
            assertPickerLimits(activity, minimum = 1, maximum = 4)
        }
        confirmSize(2, minimum = 1)
        onMain {
            assertSize(activity, R.string.fontset_entry_candidate_annotation, 2)
        }
        save(activity)
        assertSavedSizes(saved, body = 8f, annotation = 2f)
    }

    private fun withFontset(
        body: Float,
        annotation: Float,
        block: (FontsetEditorActivity, AtomicReference<Map<String, List<String>>>) -> Unit,
    ) {
        // The production snapshot reader resolves UserConfigFiles directly. Preserve its
        // exact bytes, while routing writes through a provider that only captures values.
        val fontset = requireNotNull(UserConfigFiles.fontsetJson())
        val originalBytes = fontset.takeIf(File::exists)?.readBytes()
        val originalConfigProvider = ConfigProviders.provider
        val originalFontProvider = FontProviders.provider
        val scratch = File(context.cacheDir, "fontset-editor-test-" + UUID.randomUUID())
        val saved = AtomicReference<Map<String, List<String>>>()
        val fixtureProvider = object : ConfigProvider by originalConfigProvider {
            override fun fontsetFile(): File = File(scratch, "fontset.json")
            override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> {
                saved.set(pathMap.mapValues { (_, values) -> values.toList() })
                return Result.success(fontsetFile())
            }
        }
        var activity: FontsetEditorActivity? = null
        try {
            fontset.parentFile?.mkdirs()
            fontset.writeText(buildJsonObject {
                put("cand_font_size", JsonPrimitive(body.toString()))
                put("cand_comment_font_size", JsonPrimitive(annotation.toString()))
            }.toString())
            onMain {
                ConfigProviders.provider = fixtureProvider
                FontProviders.provider = DefaultFontProvider()
                FontProviders.markNeedsRefresh()
            }
            val editor = instrumentation.startActivitySync(
                Intent(context, FontsetEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as FontsetEditorActivity
            activity = editor
            instrumentation.waitForIdleSync()
            block(editor, saved)
        } finally {
            onMain {
                sizeDialogOrNull()?.findViewById<Button>(android.R.id.button2)?.performClick()
                activity?.finish()
            }
            instrumentation.waitForIdleSync()
            if (originalBytes == null) {
                fontset.delete()
            } else {
                fontset.writeBytes(originalBytes)
            }
            onMain {
                ConfigProviders.provider = originalConfigProvider
                FontProviders.provider = originalFontProvider
                FontProviders.markNeedsRefresh()
            }
            instrumentation.waitForIdleSync()
            scratch.deleteRecursively()
        }
    }

    private fun title(activity: FontsetEditorActivity, titleRes: Int): TextView =
        descendants(activity.window.decorView).filterIsInstance<TextView>().single {
            it.text.toString() == activity.getString(titleRes)
        }

    private fun assertSize(activity: FontsetEditorActivity, titleRes: Int, size: Int) {
        val label = title(activity, titleRes)
        val value = descendants(label.parent as View).filterIsInstance<TextView>().single { it !== label }
        assertEquals(activity.getString(R.string.font_size_value, size), value.text.toString())
    }

    private fun openSizeEditor(activity: FontsetEditorActivity, titleRes: Int) {
        onMain {
            val row = title(activity, titleRes).parent as View
            val icon = descendants(row).single {
                it.contentDescription == activity.getString(R.string.font_size)
            }
            assertTrue((icon.parent as View).performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertPickerLimits(activity: FontsetEditorActivity, minimum: Int, maximum: Int) {
        assertEquals(maximum - minimum, seekBar().max)
        assertTrue(descendants(sizeDialog()).filterIsInstance<TextView>().any {
            it.text.toString() == activity.getString(R.string.font_size_editor_hint, minimum, maximum)
        })
    }

    private fun confirmSize(size: Int, minimum: Int) {
        onMain {
            seekBar().progress = size - minimum
            assertTrue(sizeDialog().findViewById<Button>(android.R.id.button1).performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun resetSize() {
        onMain {
            assertTrue(sizeDialog().findViewById<Button>(android.R.id.button3).performClick())
        }
        instrumentation.waitForIdleSync()
    }

    private fun seekBar(): SeekBar = descendants(sizeDialog()).filterIsInstance<SeekBar>().single()

    private fun sizeDialog(): View = requireNotNull(sizeDialogOrNull()) { "Font size dialog is not open" }

    private fun sizeDialogOrNull(): View? = WindowInspector.getGlobalWindowViews().singleOrNull { root ->
        descendants(root).any { it is SeekBar }
    }

    private fun save(activity: FontsetEditorActivity) {
        onMain {
            val toolbar = descendants(activity.window.decorView).filterIsInstance<Toolbar>().single()
            val save = (0 until toolbar.menu.size()).map(toolbar.menu::getItem).single {
                it.title.toString() == activity.getString(R.string.save)
            }
            assertTrue("Editing a size must enable save", save.isEnabled)
            assertTrue(toolbar.menu.performIdentifierAction(save.itemId, 0))
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertSavedSizes(
        saved: AtomicReference<Map<String, List<String>>>,
        body: Float,
        annotation: Float,
    ) {
        assertNotNull("Save must write through the injected provider", saved.get())
        val values = requireNotNull(saved.get())
        val savedBody = values.getValue("cand_font_size").single().toFloat()
        val savedAnnotation = values.getValue("cand_comment_font_size").single().toFloat()
        assertEquals(body, savedBody, 0.01f)
        assertEquals(annotation, savedAnnotation, 0.01f)
        assertTrue(savedAnnotation <= savedBody / 2f)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}
