/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGlassPreferencesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withStorage(block: (SharedPreferences) -> Unit) = instrumentation.runOnMainSync {
        val storage = instrumentation.targetContext.getSharedPreferences(
            "keyboard_glass_preferences_test", Context.MODE_PRIVATE
        )
        storage.edit().clear().commit()
        try {
            block(storage)
        } finally {
            storage.edit().clear().commit()
        }
    }

    private fun withPreferences(block: (ThemePrefs) -> Unit) = withStorage { block(ThemePrefs(it)) }

    @Test
    fun existingUsersKeepSquareTopCornersAndGlassDisabled() = withPreferences { prefs ->
        assertEquals(0, prefs.keyboardTopCornerRadius.getValue())
        assertFalse(prefs.keyboardGlassEnabled.getValue())
        assertEquals(24, prefs.keyboardGlassBlurRadius.getValue())
        assertEquals(70, prefs.keyboardGlassBackgroundOpacity.getValue())
        assertEquals(100, prefs.keyOpacity.getValue())
        assertFalse(prefs.keyOpacity.sharedPreferences.contains(prefs.keyOpacity.key))
        assertTrue(prefs.managedPreferencesUi.single {
            it.key == prefs.keyboardTopCornerRadius.key
        }.isEnabled())
        listOf(
            prefs.keyboardGlassBlurRadius,
            prefs.keyboardGlassBackgroundOpacity
        ).forEach { preference ->
            assertFalse(prefs.managedPreferencesUi.single { it.key == preference.key }.isEnabled())
        }
        assertTrue(prefs.managedPreferencesUi.single { it.key == prefs.keyOpacity.key }.isEnabled())
    }

    @Test
    fun disablingGlassPreservesItsValuesAndIndependentTopCorners() = withPreferences { prefs ->
        prefs.keyboardTopCornerRadius.setValue(32)
        prefs.keyboardGlassEnabled.setValue(true)
        prefs.keyboardGlassBlurRadius.setValue(48)
        prefs.keyboardGlassBackgroundOpacity.setValue(45)
        prefs.keyOpacity.setValue(60)
        listOf(
            prefs.keyboardGlassBlurRadius,
            prefs.keyboardGlassBackgroundOpacity
        ).forEach { preference ->
            assertTrue(prefs.managedPreferencesUi.single { it.key == preference.key }.isEnabled())
        }
        prefs.keyboardGlassEnabled.setValue(false)

        val reloaded = ThemePrefs(prefs.keyboardGlassEnabled.sharedPreferences)
        assertFalse(reloaded.keyboardGlassEnabled.getValue())
        assertEquals(32, reloaded.keyboardTopCornerRadius.getValue())
        assertEquals(48, reloaded.keyboardGlassBlurRadius.getValue())
        assertEquals(45, reloaded.keyboardGlassBackgroundOpacity.getValue())
        assertEquals(60, reloaded.keyOpacity.getValue())
        assertTrue(reloaded.managedPreferencesUi.single { it.key == reloaded.keyOpacity.key }.isEnabled())
    }

    @Test
    fun oldGboardTransparencyMigratesBeforeChoosingTheUnifiedOpacity() = withStorage { storage ->
        storage.edit()
            .putInt("gboard_light_main_key_tone", 20)
            .putInt("gboard_light_other_key_tone", 60)
            .commit()

        val prefs = ThemePrefs(storage)

        assertEquals(80, prefs.keyOpacity.getValue())
        assertEquals(80, storage.getInt("gboard_light_main_key_tone", -1))
        assertEquals(40, storage.getInt("gboard_light_other_key_tone", -1))
    }

    @Test
    fun mainOpacityTakesPriorityAndDefaultMainFallsBackToOtherKeys() = withStorage { storage ->
        storage.edit()
            .putBoolean("gboard_key_opacity_semantics_migrated", true)
            .putInt("gboard_light_main_key_tone", 80)
            .putInt("gboard_light_other_key_tone", 45)
            .commit()
        assertEquals(80, ThemePrefs(storage).keyOpacity.getValue())

        storage.edit().remove("key_opacity").putInt("gboard_light_main_key_tone", 100).commit()
        assertEquals(45, ThemePrefs(storage).keyOpacity.getValue())

        storage.edit().remove("key_opacity").remove("gboard_light_main_key_tone").commit()
        assertEquals(45, ThemePrefs(storage).keyOpacity.getValue())
    }

    @Test
    fun enabledLegacyGlassFoldsIntoUnifiedOpacityOnlyOnce() = withStorage { storage ->
        storage.edit()
            .putBoolean("gboard_key_opacity_semantics_migrated", true)
            .putInt("gboard_light_main_key_tone", 33)
            .putBoolean("keyboard_glass_enabled", true)
            .putInt("keyboard_glass_key_opacity", 50)
            .commit()
        val prefs = ThemePrefs(storage)
        assertEquals(17, prefs.keyOpacity.getValue())

        assertEquals(17, ThemePrefs(storage).keyOpacity.getValue())
        prefs.keyOpacity.setValue(62)
        storage.edit().putInt("keyboard_glass_key_opacity", 10).commit()
        assertEquals(62, ThemePrefs(storage).keyOpacity.getValue())
    }

    @Test
    fun disabledOrUnconfiguredLegacyGlassDoesNotReduceOpacity() = withStorage { storage ->
        storage.edit()
            .putBoolean("gboard_key_opacity_semantics_migrated", true)
            .putInt("gboard_light_main_key_tone", 80)
            .putBoolean("keyboard_glass_enabled", false)
            .putInt("keyboard_glass_key_opacity", 50)
            .commit()
        assertEquals(80, ThemePrefs(storage).keyOpacity.getValue())

        storage.edit().remove("key_opacity").remove("keyboard_glass_key_opacity")
            .putBoolean("keyboard_glass_enabled", true).commit()
        assertEquals(80, ThemePrefs(storage).keyOpacity.getValue())
    }

    @Test
    fun legacyGlassWithoutRegionOverridesStillPreservesItsOpacity() = withStorage { storage ->
        storage.edit()
            .putBoolean("keyboard_glass_enabled", true)
            .putInt("keyboard_glass_key_opacity", 75)
            .commit()
        assertEquals(75, ThemePrefs(storage).keyOpacity.getValue())
    }

    @Test
    fun legacyInvalidValuesFallBackAndGlassValuesAreClamped() = withStorage { storage ->
        storage.edit()
            .putBoolean("gboard_key_opacity_semantics_migrated", true)
            .putInt("gboard_light_main_key_tone", Int.MAX_VALUE)
            .putInt("gboard_light_other_key_tone", Int.MIN_VALUE)
            .putBoolean("keyboard_glass_enabled", true)
            .putInt("keyboard_glass_key_opacity", Int.MAX_VALUE)
            .commit()
        assertEquals(100, ThemePrefs(storage).keyOpacity.getValue())

        storage.edit().remove("key_opacity")
            .putInt("gboard_light_main_key_tone", Int.MIN_VALUE)
            .putInt("gboard_light_other_key_tone", 100)
            .commit()
        assertEquals(100, ThemePrefs(storage).keyOpacity.getValue())

        storage.edit().remove("key_opacity")
            .putInt("keyboard_glass_key_opacity", Int.MIN_VALUE)
            .commit()
        assertEquals(0, ThemePrefs(storage).keyOpacity.getValue())

        storage.edit().remove("key_opacity")
            .putString("gboard_light_main_key_tone", "invalid")
            .putInt("gboard_light_other_key_tone", 55)
            .putString("keyboard_glass_key_opacity", "invalid")
            .commit()
        assertEquals(55, ThemePrefs(storage).keyOpacity.getValue())
    }

    @Test
    fun legacyToneValuesKeepTheirOriginalOpacity() = withStorage { storage ->
        for ((raw, expected) in listOf(128 to 50, 255 to 100, -1 to 100)) {
            storage.edit().remove("key_opacity")
                .putInt("gboard_light_main_key_tone", raw)
                .commit()
            assertEquals("Legacy tone $raw", expected, ThemePrefs(storage).keyOpacity.getValue())
        }

        storage.edit().remove("key_opacity")
            .putInt("gboard_light_main_key_tone", -1)
            .putInt("gboard_light_other_key_tone", 128)
            .commit()
        assertEquals(50, ThemePrefs(storage).keyOpacity.getValue())
    }

    @Test
    fun retiredOpacityAndWallpaperSettingsHaveNoUiRegistration() = withPreferences { prefs ->
        val retiredKeys = setOf(
            "gboard_light_main_key_tone",
            "gboard_light_other_key_tone",
            "keyboard_glass_key_opacity",
            "wallpaper_blend_percent"
        )
        assertTrue(prefs.managedPreferences.keys.none { it in retiredKeys })
        assertTrue(prefs.managedPreferencesUi.none { it.key in retiredKeys })
    }
}
