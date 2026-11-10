/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.SeekBar
import androidx.core.view.allViews
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceScreen
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ThemeOtherStyleUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val radiusKeys = listOf(
        "clipboard_entry_radius", "text_editing_button_radius",
        "cursor_movement_button_radius", "text_selection_button_radius"
    )

    @Test
    fun otherStylePageOrdersActualPreferencesByChineseNameLength() = withPage { _, screen ->
        val chinese = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        })
        onMain {
            val preferences = (0 until screen.preferenceCount).map(screen::getPreference)
            assertEquals(radiusKeys.toSet(), preferences.map { it.key }.toSet())
            val titles = preferences.map { preference ->
                val ui = ThemeManager.prefs.managedPreferencesUi.single { it.key == preference.key }
                    as ManagedPreferenceUi.SeekBarInt
                chinese.getString(ui.title)
            }
            val lengths = titles.map { it.codePointCount(0, it.length) }
            assertEquals(lengths.sorted(), lengths)
            assertEquals(listOf(9, 10, 13, 13), lengths)
            assertEquals("光标移动面板・按钮圆角半径", titles[2])
            assertEquals("文本选择面板・按钮圆角半径", titles[3])
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun gestureRadiusDialogsPersistIndependentValuesAndKeepTheirRange() = withPage { _, screen ->
        val prefs = ThemeManager.prefs
        val legacy = prefs.textEditingButtonRadius.getValue()
        val cursor = screen.findPreference<DialogSeekBarPreference>(prefs.cursorMovementButtonRadius.key)!!
        val selection = screen.findPreference<DialogSeekBarPreference>(prefs.textSelectionButtonRadius.key)!!
        onMain {
            listOf(cursor, selection).forEach {
                assertEquals(0, it.min)
                assertEquals(48, it.max)
                assertEquals(8, it.default)
                assertEquals("dp", it.unit)
            }
        }
        changeDialogValue(cursor, 0)
        changeDialogValue(selection, 48)
        onMain {
            assertEquals(0, prefs.cursorMovementButtonRadius.getValue())
            assertEquals(48, prefs.textSelectionButtonRadius.getValue())
            assertEquals(0, prefs.cursorMovementButtonRadius.sharedPreferences.getInt(cursor.key, -1))
            assertEquals(48, prefs.textSelectionButtonRadius.sharedPreferences.getInt(selection.key, -1))
            assertEquals(legacy, prefs.textEditingButtonRadius.getValue())
        }
        changeDialogValue(cursor, 13)
        onMain {
            assertEquals(13, prefs.cursorMovementButtonRadius.getValue())
            assertEquals(48, prefs.textSelectionButtonRadius.getValue())
            assertEquals(legacy, prefs.textEditingButtonRadius.getValue())
        }
    }

    private fun changeDialogValue(preference: DialogSeekBarPreference, value: Int) {
        onMain {
            preference.performClick()
            val root = WindowInspector.getGlobalWindowViews().last { window ->
                window.allViews.any { it is SeekBar }
            }
            val slider = root.allViews.filterIsInstance<SeekBar>().single()
            assertEquals(48, slider.max)
            slider.progress = value
            root.findViewById<Button>(android.R.id.button1).performClick()
        }
        await { preference.value == value }
    }

    private fun withPage(block: (MainActivity, PreferenceScreen) -> Unit) {
        val preferences = ThemeManager.prefs.cursorMovementButtonRadius.sharedPreferences
        val saved = radiusKeys.associateWith { key ->
            if (preferences.contains(key)) preferences.getInt(key, 0) else null
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_RUN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, SettingsRoute.ThemeOtherStyle)
            }
        ) as MainActivity
        try {
            await { otherStyle(activity.supportFragmentManager.fragments)?.preferenceScreen?.preferenceCount == 4 }
            lateinit var screen: PreferenceScreen
            onMain { screen = requireNotNull(otherStyle(activity.supportFragmentManager.fragments)).preferenceScreen }
            block(activity, screen)
        } finally {
            onMain {
                activity.finish()
                val editor = preferences.edit()
                saved.forEach { (key, value) ->
                    if (value == null) editor.remove(key) else editor.putInt(key, value)
                }
                assertTrue(editor.commit())
                ThemeManager.syncToDeviceEncryptedStorage()
            }
        }
    }

    private fun otherStyle(fragments: List<Fragment>): ThemeOtherStyleFragment? {
        fragments.forEach { fragment ->
            if (fragment is ThemeOtherStyleFragment) return fragment
            otherStyle(fragment.childFragmentManager.fragments)?.let { return it }
        }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            onMain { result = condition() }
            if (result) return
            SystemClock.sleep(50)
        }
        var result = false
        onMain { result = condition() }
        assertTrue("Timed out waiting for the other style page", result)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
