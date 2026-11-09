/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.keyboardtest

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.*
import org.junit.Test
import splitties.resources.styledColor
import kotlin.math.abs

class KeyboardTestDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun actualSheetBackgroundKeepsTitleAndSelectedTypeReadableInLightAndDarkModes() {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                lateinit var dialog: KeyboardTestDialog
                lateinit var context: ContextThemeWrapper
                var shown = false
                try {
                    onMain {
                        val configuration = Configuration(activity.resources.configuration).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                        }
                        // Keep the Activity as the base so Dialog resolves its real window token.
                        context = ContextThemeWrapper(activity, R.style.Theme_FcitxAppTheme).apply {
                            applyOverrideConfiguration(configuration)
                        }
                        dialog = KeyboardTestDialog(context)
                        dialog.show()
                        shown = true
                    }
                    waitForSheetLayout(dialog)
                    onMain {
                        val sheet = requireNotNull(dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet))
                        assertTrue(sheet.isAttachedToWindow)
                        val bitmap = Bitmap.createBitmap(sheet.width, sheet.height, Bitmap.Config.ARGB_8888)
                        try {
                            sheet.draw(Canvas(bitmap))
                            // This point is inside the sheet shape and outside the panel's 20dp content padding.
                            val x = (8 * context.resources.displayMetrics.density).toInt()
                            val background = bitmap.getPixel(x.coerceAtMost(bitmap.width - 1), bitmap.height / 2)
                            val surface = context.styledColor(com.google.android.material.R.attr.colorSurface)
                            assertEquals("The rendered sheet must be opaque", 255, Color.alpha(background))
                            assertTrue("Rendered sheet background in night mode $nightMode differs from its host surface",
                                abs(Color.red(background) - Color.red(surface)) <= 2 &&
                                    abs(Color.green(background) - Color.green(surface)) <= 2 &&
                                    abs(Color.blue(background) - Color.blue(surface)) <= 2)
                            val title = descendants(dialog.panel).filterIsInstance<TextView>().single {
                                it.text.toString() == context.getString(R.string.keyboard_test_title)
                            }
                            val selected = descendants(dialog.panel.tabs).filterIsInstance<MaterialButton>().single { it.isChecked }
                            for (text in listOf(title, selected)) {
                                val contrast = ColorUtils.calculateContrast(text.currentTextColor, background)
                                assertTrue("${text.text} contrast against the rendered sheet in night mode $nightMode was $contrast",
                                    contrast >= 4.5)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } finally {
                    if (shown) onMain { dialog.dismiss() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun waitForSheetLayout(dialog: KeyboardTestDialog) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (onMain {
                    dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                        it.isAttachedToWindow && it.width > 0 && it.height > 0
                    } == true
                }) return
            Thread.sleep(20)
        }
        fail("The shown keyboard test sheet did not receive a layout")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { result[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }
}
