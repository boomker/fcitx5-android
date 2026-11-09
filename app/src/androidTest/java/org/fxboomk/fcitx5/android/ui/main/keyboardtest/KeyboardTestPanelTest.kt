/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.keyboardtest

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Parcelable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.SparseArray
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.*
import org.junit.Test
import splitties.resources.styledColor
import java.util.Locale

class KeyboardTestPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyInputTypePublishesItsAndroidEditorContract() = withActivity { activity ->
        val panel = KeyboardTestPanel(activity) {}
        activity.setContentView(panel)
        val expected = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertEquals(4, panel.tabs.childCount)
        KeyboardTestPanel.Kind.entries.forEachIndexed { index, kind ->
            panel.select(kind)
            panel.input.requestFocus()
            val info = EditorInfo()
            assertNotNull(panel.input.onCreateInputConnection(info))
            assertEquals(kind.name, expected[index], info.inputType)
            assertEquals(EditorInfo.IME_ACTION_DONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(kind.secret, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
            }
            assertEquals(kind.secret, panel.input.transformationMethod is PasswordTransformationMethod)
            assertEquals(1, panel.tabs.checkedButtonIds.size)
        }
    }

    @Test
    fun passwordsCannotReappearAsTextAndPinFiltersNondigits() = withActivity { activity ->
        val panel = KeyboardTestPanel(activity) {}
        activity.setContentView(panel)
        panel.select(KeyboardTestPanel.Kind.PASSWORD)
        panel.input.setText("test password")
        panel.select(KeyboardTestPanel.Kind.TEXT)
        assertEquals("", panel.input.text.toString())
        panel.input.setText("ordinary text")
        panel.select(KeyboardTestPanel.Kind.PIN)
        assertEquals("", panel.input.text.toString())
        panel.input.text?.append("12a3")
        assertEquals("123", panel.input.text.toString())
        panel.select(KeyboardTestPanel.Kind.NUMBER)
        assertEquals("", panel.input.text.toString())
        panel.input.setText("456")
        panel.select(KeyboardTestPanel.Kind.TEXT)
        assertEquals("456", panel.input.text.toString())
    }

    @Test
    fun savedViewStateExcludesTestContentsAndClearDropsTheEditorFocus() = withActivity { activity ->
        val panel = KeyboardTestPanel(activity) {}
        activity.setContentView(panel)
        panel.select(KeyboardTestPanel.Kind.PIN)
        panel.input.setText("1234")
        panel.input.requestFocus()
        val state = SparseArray<Parcelable>()
        panel.saveHierarchyState(state)
        assertNull(state.get(R.id.keyboard_test_input))
        val restored = KeyboardTestPanel(activity) {}
        restored.restoreHierarchyState(state)
        assertEquals("", restored.input.text.toString())
        assertFalse(panel.input.isSaveEnabled)
        assertFalse(panel.input.isSaveFromParentEnabled)
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, panel.input.importantForAutofill)
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, panel.importantForAutofill)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO, panel.input.importantForContentCapture)
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, panel.importantForContentCapture)
        }
        panel.clear()
        assertEquals("", panel.input.text.toString())
        assertFalse(panel.input.hasFocus())
    }

    @Test
    fun narrowLargeTextUsesEqualTwoByTwoCellsAndKeepsOneSelectedType() = withActivity { activity ->
        val configuration = Configuration(activity.resources.configuration).apply {
            fontScale = 1.6f
            setLocale(Locale.ENGLISH)
        }
        val context = ContextThemeWrapper(
            activity.createConfigurationContext(configuration),
            R.style.Theme_FcitxAppTheme
        )
        val panel = KeyboardTestPanel(context) {}
        val width = (320 * context.resources.displayMetrics.density).toInt()
        val minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            panel.layoutDirection = direction
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            panel.layout(0, 0, width, panel.measuredHeight)
            val buttons = (0 until panel.tabs.childCount).map { panel.tabs.getChildAt(it) as MaterialButton }
            assertEquals(1, buttons.map { it.width }.distinct().size)
            assertEquals(2, buttons.map { it.top }.distinct().size)
            buttons.forEach { button ->
                assertTrue(button.left >= 0 && button.right <= panel.tabs.width)
                assertTrue(button.height >= minimumHeight)
                val textLayout = requireNotNull(button.layout)
                repeat(textLayout.lineCount) { line ->
                    assertEquals(0, textLayout.getEllipsisCount(line))
                    assertTrue(textLayout.getLineWidth(line) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1f)
                }
            }
            buttons.last().performClick()
            buttons.last().performClick()
            assertEquals(KeyboardTestPanel.Kind.PIN, panel.kind)
            assertEquals(listOf(buttons.last().id), panel.tabs.checkedButtonIds)
        }
    }

    @Test
    fun selectedInputTypeTextKeepsReadableContrastInLightAndDarkThemes() = withActivity { activity ->
        for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val configuration = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            val context = ContextThemeWrapper(
                activity.createConfigurationContext(configuration),
                R.style.Theme_FcitxAppTheme
            )
            val panel = KeyboardTestPanel(context) {}
            val surface = context.styledColor(com.google.android.material.R.attr.colorSurface)
            KeyboardTestPanel.Kind.entries.forEach { kind ->
                panel.select(kind)
                val selected = (0 until panel.tabs.childCount)
                    .map { panel.tabs.getChildAt(it) as MaterialButton }
                    .single { it.isChecked }
                assertEquals(kind, selected.tag)
                assertEquals(listOf(selected.id), panel.tabs.checkedButtonIds)
                val tint = requireNotNull(selected.backgroundTintList)
                val fill = tint.getColorForState(selected.drawableState, tint.defaultColor)
                val background = ColorUtils.compositeColors(fill, surface)
                val contrast = ColorUtils.calculateContrast(selected.currentTextColor, background)
                assertTrue("${kind.name} text contrast in night mode $nightMode was $contrast", contrast >= 4.5)
            }
        }
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            instrumentation.runOnMainSync { block(activity) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
