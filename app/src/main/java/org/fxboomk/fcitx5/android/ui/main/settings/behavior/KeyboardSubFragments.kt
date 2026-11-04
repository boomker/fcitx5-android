/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fxboomk.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fxboomk.fcitx5.android.ui.main.settings.voice.JsVoicePluginsActivity
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.setup

abstract class KeyboardSectionFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    final override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.removeAll()
        onBuildPreferenceScreen(screen)
    }

    protected abstract fun onBuildPreferenceScreen(screen: PreferenceScreen)
}

class KeyboardBasicSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_basic_behavior, basicBehaviorKeys)
        }
    }
}

class KeyboardVoiceSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(
                screen,
                R.string.keyboard_category_voice,
                listOf("show_voice_input_button", "preferred_voice_input"),
            )
        }
        screen.addPreference(
            Preference(requireContext()).apply {
                key = "tool_js_voice_plugins"
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setup(
                    context.getString(R.string.js_voice_plugins_title),
                    context.getString(R.string.js_voice_plugins_summary),
                ) {
                    startActivity(JsVoicePluginsActivity.intent(requireContext()))
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        findPreference<ListPreference>("preferred_voice_input")?.let { preference ->
            ManagedPreferenceUi.VoiceInputList.refreshEntries(preference)
            // Re-resolve the summary after plugins were imported or removed.
            preference.value = preference.value
        }
    }
}

class KeyboardTouchAndSoundSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_touch_and_sound, touchAndSoundKeys)
        }
    }
}

class KeyboardKeyAndGestureSettingsFragment : KeyboardSectionFragment() {
    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        KeyboardSettingsSupport.run {
            addKeyboardSection(screen, R.string.keyboard_settings_key_and_gesture, keyAndGestureKeys)
        }
    }
}

class KeyboardLayoutAndSplitSettingsFragment : KeyboardSectionFragment() {

    private var calibrationPreference: Preference? = null
    private var useLandscapePreference: Preference? = null
    private var floatingModePreference: MySwitchPreference? = null
    private var autoFloatingLandscapePreference: MySwitchPreference? = null
    private var autoHideMoveHandlePreference: MySwitchPreference? = null

    private val onSplitEnabledChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key == KeyboardSettingsSupport.SPLIT_ENABLED_KEY) {
            val enabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled.getValue()
            calibrationPreference?.isEnabled = enabled
            useLandscapePreference?.isEnabled = enabled
        }
    }

    private val onFloatingPreferenceChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        val internal = AppPrefs.getInstance().internal
        if (key == internal.floatingModeEnabled.key ||
            key == internal.autoFloatingLandscape.key ||
            key == internal.autoHideFloatingMoveHandle.key
        ) {
            syncFloatingSwitches()
        }
    }

    override fun onBuildPreferenceScreen(screen: PreferenceScreen) {
        val prefs = AppPrefs.getInstance()
        calibrationPreference = KeyboardSettingsSupport.createSplitKeyboardCalibrationPreference(this)
        KeyboardSettingsSupport.run {
            addKeyboardSection(
                screen,
                R.string.keyboard_modes_category_split,
                KeyboardSettingsSupport.layoutKeys,
                calibrationPreference
            )
        }
        screen.addCategory(R.string.keyboard_modes_category_floating) {
            addPreference(createFloatingSwitch(
                R.string.floating_keyboard_enabled,
                R.string.floating_keyboard_enabled_summary,
                prefs.internal.floatingModeEnabled.key
            ).also { floatingModePreference = it })
            addPreference(createFloatingSwitch(
                R.string.auto_floating_landscape,
                R.string.auto_floating_landscape_summary,
                prefs.internal.autoFloatingLandscape.key
            ).also { autoFloatingLandscapePreference = it })
            addPreference(createFloatingSwitch(
                R.string.floating_move_handle_auto_hide,
                R.string.floating_move_handle_auto_hide_summary,
                prefs.internal.autoHideFloatingMoveHandle.key
            ).also { autoHideMoveHandlePreference = it })
        }
        useLandscapePreference = screen.findPreference("split_keyboard_use_landscape_layout")
        useLandscapePreference?.isEnabled = prefs.keyboard.splitKeyboardEnabled.getValue()
        prefs.keyboard.registerOnChangeListener(onSplitEnabledChangeListener)
        prefs.internal.registerOnChangeListener(onFloatingPreferenceChangeListener)
        syncFloatingSwitches()
    }

    override fun onResume() {
        super.onResume()
        syncFloatingSwitches()
    }

    private fun createFloatingSwitch(
        titleRes: Int,
        summaryRes: Int,
        preferenceKey: String
    ): MySwitchPreference = MySwitchPreference(requireContext()).apply {
        key = preferenceKey
        setTitle(titleRes)
        setSummary(summaryRes)
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }

    private fun syncFloatingSwitches() {
        val internal = AppPrefs.getInstance().internal
        floatingModePreference?.isChecked = internal.floatingModeEnabled.getValue()
        autoFloatingLandscapePreference?.isChecked = internal.autoFloatingLandscape.getValue()
        autoHideMoveHandlePreference?.isChecked = internal.autoHideFloatingMoveHandle.getValue()
    }

    override fun onDestroy() {
        val prefs = AppPrefs.getInstance()
        prefs.keyboard.unregisterOnChangeListener(onSplitEnabledChangeListener)
        prefs.internal.unregisterOnChangeListener(onFloatingPreferenceChangeListener)
        super.onDestroy()
    }
}
