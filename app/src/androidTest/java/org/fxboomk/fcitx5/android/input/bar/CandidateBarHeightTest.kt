/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateBarHeightTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun manualHeightIgnoresMeasuredTopAnnotationUntilAdaptationIsEnabled() = withPreferences(false) { bar ->
        var notifications = 0
        bar.onBarHeightChanged = { notifications++ }
        bar.setCandidateRequiredHeight(60)
        assertEquals(40, bar.candidateRowHeightDp)
        assertEquals(0, notifications)

        AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(true)
        assertEquals("Enabling adaptation must use the configured candidate requirement", 60, bar.candidateRowHeightDp)
        AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(false)
        bar.setCandidateRequiredHeight(0)
        assertEquals(40, bar.candidateRowHeightDp)
        assertEquals(0, notifications)
    }

    @Test
    fun adaptationCanUseLessThanManualHeightAndDisablingRestoresTheManualValue() =
        withPreferences(true, manualHeight = 72) { bar ->
            bar.setCandidateRequiredHeight(48)
            assertEquals(48, bar.candidateRowHeightDp)
            assertEquals("Calculated height must not overwrite the saved manual height",
                72, ThemeManager.prefs.toolbarHeight.getValue())

            AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(false)
            assertEquals(72, bar.candidateRowHeightDp)
            AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(true)
            assertEquals(48, bar.candidateRowHeightDp)
        }

    @Test
    fun anUnmeasuredAdaptiveBarUsesTheSavedManualHeight() = withPreferences(true, manualHeight = 72) { bar ->
        assertEquals(72, bar.candidateRowHeightDp)
        assertEquals(72, ThemeManager.prefs.toolbarHeight.getValue())
    }

    @Test
    fun repeatedConfiguredMeasurementsNotifyOnlyWhenTheComputedHeightChanges() = withPreferences(true) { bar ->
        val heights = mutableListOf<Int>()
        bar.onBarHeightChanged = { heights += bar.candidateRowHeightDp }
        for (required in listOf(48, 48, 60, 60, 48)) {
            bar.setCandidateRequiredHeight(required)
        }
        assertEquals(listOf(48, 60, 48), heights)
        assertEquals(48, bar.candidateRowHeightDp)
    }

    private fun withPreferences(dynamic: Boolean, manualHeight: Int = 40, block: (KawaiiBarComponent) -> Unit) {
        instrumentation.runOnMainSync {
            val height = ThemeManager.prefs.toolbarHeight
            val dynamicHeight = AppPrefs.getInstance().keyboard.toolbarDynamicHeight
            val profiles = AppPrefs.getInstance().internal.candidateBarHeightProfiles
            val originals = listOf(height, dynamicHeight, profiles)
                .associateWith { it.sharedPreferences.all[it.key] }
            try {
                height.setValue(manualHeight)
                dynamicHeight.setValue(dynamic)
                block(KawaiiBarComponent())
            } finally {
                // Restore the profile storage last, after listeners observe restored proxy values.
                originals.forEach { (preference, original) ->
                    val editor = preference.sharedPreferences.edit()
                    when (original) {
                        null -> editor.remove(preference.key)
                        is Int -> editor.putInt(preference.key, original)
                        is Boolean -> editor.putBoolean(preference.key, original)
                        is String -> editor.putString(preference.key, original)
                        else -> error("Unexpected preference type for ${preference.key}")
                    }
                    editor.commit()
                }
            }
        }
    }
}
