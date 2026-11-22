/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.ImageView
import androidx.core.view.allViews
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.keyboardtest.KeyboardTestDialog
import org.fxboomk.fcitx5.android.testing.RequireDefaultImeRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.ClassRule
import org.junit.Test
import java.io.File

/** Exercises the real IME; the class rule manages IME selection, this test restores its UI values. */
@SdkSuppress(minSdkVersion = 31)
class KeyboardGlassImeTest {
    companion object {
        @ClassRule
        @JvmField
        val defaultIme = RequireDefaultImeRule()
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun realImeGlassTracksKeyboardAndRestoresOpaqueBackgroundWhenBlurIsDisabled() {
        val manager = context.getSystemService(WindowManager::class.java)
        assumeTrue(manager.isCrossWindowBlurEnabled)
        val prefs = ThemeManager.prefs
        val storage = prefs.keyboardGlassEnabled.sharedPreferences
        val touched = setOf(prefs.keyboardGlassEnabled.key, prefs.keyboardGlassBlurRadius.key,
            prefs.keyboardGlassBackgroundOpacity.key, prefs.keyOpacity.key,
            prefs.keyboardTopCornerRadius.key, prefs.keyBorder.key, prefs.followSystemDayNightTheme.key,
            prefs.normalModeTheme.key)
        val original = storage.all.filterKeys { it in touched }
        val notifications = AppPrefs.getInstance().internal.needNotifications
        val notificationValue = notifications.sharedPreferences.all[notifications.key] as? Boolean
        onMain { notifications.setValue(false) }
        var activity: MainActivity? = null
        var dialog: KeyboardTestDialog? = null
        val originalBlurSetting = shell("settings get global disable_window_blurs").trim()
        var disabledBlur = false
        try {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            onMain {
                prefs.followSystemDayNightTheme.setValue(false)
                ThemeManager.setNormalModeTheme(ThemePreset.MaterialLight)
                prefs.keyboardTopCornerRadius.setValue(28)
                prefs.keyboardGlassBlurRadius.setValue(24)
                prefs.keyboardGlassBackgroundOpacity.setValue(55)
                prefs.keyOpacity.setValue(75)
                prefs.keyBorder.setValue(true)
                prefs.keyboardGlassEnabled.setValue(true)
                dialog = KeyboardTestDialog(requireNotNull(activity)).also { it.show() }
            }
            await { inputView() != null && blurWindows().size == 1 }
            await {
                val keyboard = inputView()!!.keyboardView
                val blur = blurWindows().singleOrNull() ?: return@await false
                sameFrame(keyboard, blur)
            }
            onMain {
                val input = inputView()!!
                val background = input.keyboardView.getChildAt(0) as ImageView
                assertEquals(140, Color.alpha((background.drawable as android.graphics.drawable.ColorDrawable).color))
                assertTrue(input.keyboardView.cornerRadii().take(4).all { it > 0f })
                assertTrue(input.keyboardView.cornerRadii().drop(4).all { it == 0f })
            }
            assertHardwareTopCorners()
            capture("keyboard-glass-light.png")
            onMain { ThemeManager.setNormalModeTheme(ThemePreset.MaterialDark) }
            await { inputView()?.theme?.isDark == true && blurWindows().size == 1 &&
                sameFrame(inputView()!!.keyboardView, blurWindows().single()) }
            capture("keyboard-glass-dark.png")
            onMain {
                val keyboard = inputView()!!.keyboardView
                // A translation exercises the same render-only path as floating-keyboard dragging.
                keyboard.translationY -= 25f
            }
            awaitServerFrame()
            disabledBlur = true
            shell("settings put global disable_window_blurs 1")
            await {
                val input = inputView() ?: return@await false
                val background = input.keyboardView.getChildAt(0) as ImageView
                blurWindows().isEmpty() &&
                    Color.alpha((background.drawable as android.graphics.drawable.ColorDrawable).color) == 255
            }
            shell("settings put global disable_window_blurs 0")
            disabledBlur = false
            await { blurWindows().size == 1 }
            onMain { dialog!!.dismiss() }
            await { blurWindows().isEmpty() }
        } catch (failure: AssertionError) {
            capture("keyboard-glass-failure.png")
            throw failure
        } finally {
            if (disabledBlur) shell("settings put global disable_window_blurs 0")
            shell(if (originalBlurSetting == "null") "settings delete global disable_window_blurs"
                else "settings put global disable_window_blurs " + originalBlurSetting)
            onMain {
                dialog?.dismiss()
                storage.edit().apply {
                    touched.forEach { key ->
                        when (val value = original[key]) {
                            null -> remove(key)
                            is Boolean -> putBoolean(key, value)
                            is Int -> putInt(key, value)
                            is String -> putString(key, value)
                            else -> error("Unexpected theme preference type")
                        }
                    }
                }.commit()
                ThemeManager.refreshThemes()
                notifications.sharedPreferences.edit().apply {
                    if (notificationValue == null) remove(notifications.key)
                    else putBoolean(notifications.key, notificationValue)
                }.commit()
                activity?.finish()
            }
        }
    }

    private fun inputView(): InputView? = WindowInspector.getGlobalWindowViews()
        .asSequence().flatMap { it.allViews }.filterIsInstance<InputView>().firstOrNull { it.isShown }

    private fun blurWindows() = WindowInspector.getGlobalWindowViews().filter {
        (it.layoutParams as? WindowManager.LayoutParams)?.title == KeyboardGlassWindow.WINDOW_TITLE
    }

    private fun sameFrame(a: View, b: View): Boolean {
        val ap = IntArray(2)
        val bp = IntArray(2)
        a.getLocationOnScreen(ap)
        b.getLocationOnScreen(bp)
        return ap.contentEquals(bp) && a.width == b.width && a.height == b.height
    }

    private fun assertHardwareTopCorners() {
        instrumentation.waitForIdleSync()
        // Surface show/inset animations can outlive the last View layout callback.
        SystemClock.sleep(500)
        var bounds = emptyList<Int>()
        onMain {
            val keyboard = requireNotNull(inputView()).keyboardView
            val pos = IntArray(2).also(keyboard::getLocationOnScreen)
            bounds = listOf(pos[0], pos[1], keyboard.width)
        }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            // Both clipped corners expose the host, while the centre shows the glass.
            // Sample below the antialiased edge and away from toolbar icons.
            val x = bounds[0] + 1
            val y = bounds[1] + 8
            assertEquals(screenshot.getPixel(x, y), screenshot.getPixel(bounds[0] + bounds[2] - 2, y))
            assertNotEquals(screenshot.getPixel(x, y), screenshot.getPixel(bounds[0] + bounds[2] / 2, y))
        } finally {
            screenshot.recycle()
        }
    }

    private fun awaitServerFrame() {
        // Android 16 can update a position-only child window through relayoutAsync while
        // View.getLocationOnScreen still returns its previous frame. Check the WM frame,
        // which controls the actual surface position, rather than weakening this to attrs.
        val deadline = SystemClock.uptimeMillis() + 5000
        var expected = emptyList<Int>()
        var actual = emptyList<Int>()
        val framePattern = Regex("""Frames:.*? frame=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        while (SystemClock.uptimeMillis() < deadline) {
            onMain {
                val keyboard = requireNotNull(inputView()).keyboardView
                val position = IntArray(2).also(keyboard::getLocationOnScreen)
                expected = listOf(position[0], position[1], position[0] + keyboard.width, position[1] + keyboard.height)
            }
            val window = shell("dumpsys window windows")
                .substringAfter(" " + KeyboardGlassWindow.WINDOW_TITLE + "}:", "")
                .substringBefore("\n  Window #")
            actual = framePattern.find(window)?.groupValues?.drop(1)?.map(String::toInt).orEmpty()
            if (expected == actual) return
            SystemClock.sleep(100)
        }
        assertEquals("The actual blur surface must move with the keyboard", expected, actual)
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
        }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private var awaitStep = 0

    private fun await(condition: () -> Boolean) {
        val step = ++awaitStep
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            var complete = false
            onMain { complete = condition() }
            if (complete) return
            SystemClock.sleep(50)
        }
        onMain {
            val windows = WindowInspector.getGlobalWindowViews().joinToString { root ->
                val attributes = root.layoutParams as? WindowManager.LayoutParams
                val pos = IntArray(2).also(root::getLocationOnScreen)
                "${attributes?.title}:type=${attributes?.type},shown=${root.isShown},pos=${pos.toList()},size=${root.width}x${root.height},attrs=${attributes?.x},${attributes?.y}"
            }
            val keyboard = inputView()?.keyboardView
            val pos = IntArray(2).also { keyboard?.getLocationOnScreen(it) }
            assertTrue("Real IME glass step $step failed; blurEnabled=${context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled}; keyboard=${pos.toList()} ${keyboard?.width}x${keyboard?.height}, windows=$windows", condition())
        }
    }
}
