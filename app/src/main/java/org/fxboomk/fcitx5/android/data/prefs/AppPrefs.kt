/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fxboomk.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesVirtualKeyboardPosition
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fxboomk.fcitx5.android.input.keyboard.CapsKeyBehavior
import org.fxboomk.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fxboomk.fcitx5.android.input.keyboard.LangSwitchLongPressBehavior
import org.fxboomk.fcitx5.android.input.keyboard.PredictionBackspaceBehavior
import org.fxboomk.fcitx5.android.input.keyboard.PredictionSpaceBehavior
import org.fxboomk.fcitx5.android.input.keyboard.PunctuationSwipeStrategy
import org.fxboomk.fcitx5.android.input.keyboard.SpaceKeyLabelMode
import org.fxboomk.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeVerticalBehavior
import org.fxboomk.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.picker.PickerWindow
import org.fxboomk.fcitx5.android.input.picker.SymbolCatalogType
import org.fxboomk.fcitx5.android.input.picker.SymbolPanelStyle
import org.fxboomk.fcitx5.android.input.popup.EmojiModifier
import org.fxboomk.fcitx5.android.input.preedit.CompositionAreaStyle
import org.fxboomk.fcitx5.android.utils.DeviceUtil
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.vibrator

internal fun normalizeCandidateDisplayMode(
    mode: FloatingCandidatesMode?
): FloatingCandidatesMode =
    when (mode) {
        FloatingCandidatesMode.Always -> FloatingCandidatesMode.Always
        FloatingCandidatesMode.SystemDefault -> FloatingCandidatesMode.SystemDefault
        else -> FloatingCandidatesMode.InputDevice
    }

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val lastNonEnglishIme = string("last_non_english_ime", "")
        val lastNonEnglishSubMode = string("last_non_english_submode", "")
        val lastRimeChineseSubMode = string("last_rime_chinese_submode", "")
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
        val floatingModeEnabled = bool("floating_mode_enabled", false)
        val autoHideFloatingMoveHandle = bool("auto_hide_floating_move_handle", false)
        val autoFloatingLandscape = bool("auto_floating_landscape", false)
        val floatingKeyboardWidthPortraitRatio = float("floating_keyboard_width_portrait_ratio", 0f)
        val floatingKeyboardWidthLandscapeRatio = float("floating_keyboard_width_landscape_ratio", 0f)
        val floatingKeyboardHeightPortraitRatio = float("floating_keyboard_height_portrait_ratio", 0f)
        val floatingKeyboardHeightLandscapeRatio = float("floating_keyboard_height_landscape_ratio", 0f)
        val floatingKeyboardWidthRatio = float("floating_keyboard_width_ratio", 0f)
        val floatingKeyboardHeightRatio = float("floating_keyboard_height_ratio", 0f)
        val floatingKeyboardWidthLegacy = int("floating_keyboard_width", 0)
        val floatingKeyboardHeightLegacy = int("floating_keyboard_height", 0)
        val floatingKeyboardXPortraitRatio = float("floating_keyboard_x_portrait_ratio", -1f)
        val floatingKeyboardYPortraitRatio = float("floating_keyboard_y_portrait_ratio", -1f)
        val floatingKeyboardXLandscapeRatio = float("floating_keyboard_x_landscape_ratio", -1f)
        val floatingKeyboardYLandscapeRatio = float("floating_keyboard_y_landscape_ratio", -1f)
        val floatingKeyboardXPortrait = int("floating_keyboard_x_portrait", -1)
        val floatingKeyboardYPortrait = int("floating_keyboard_y_portrait", -1)
        val floatingKeyboardXLandscape = int("floating_keyboard_x_landscape", -1)
        val floatingKeyboardYLandscape = int("floating_keyboard_y_landscape", -1)
        val oneHandOnRightPortrait = bool("one_hand_on_right_portrait", true)
        val oneHandOnRightLandscape = bool("one_hand_on_right_landscape", true)

        // Settings initialization flag
        val settingsInitialized = bool("settings_initialized", false)
        val splitKeyboardMigrated = bool("split_keyboard_migrated", false)
        val lastShareReceiveDirectory = string("last_share_receive_directory", "")
        val lastShareReceiveDirectoryRemembered = bool("last_share_receive_directory_remembered", false)
        val preeditStyleScopes = stringSet("preedit_style_scopes", emptySet())

        init {
            // 旧版本只持久化 floating_keyboard_enabled；新版拆出浮动模式开关后，
            // 首次升级时把旧值迁移到 floating_mode_enabled，避免用户的浮动状态被静默关闭。
            if (!sharedPreferences.contains("floating_mode_enabled") &&
                sharedPreferences.contains("floating_keyboard_enabled")
            ) {
                sharedPreferences.edit {
                    putBoolean(
                        "floating_mode_enabled",
                        sharedPreferences.getBoolean("floating_keyboard_enabled", false)
                    )
                    remove("floating_keyboard_enabled")
                }
            }
        }
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val allowOriginalPlugins = switch(
            R.string.allow_original_plugins,
            "allow_original_plugins",
            true
        )
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val blockedPluginPackages = ManagedPreference.PStringSet(
            sharedPreferences,
            "blocked_plugin_packages",
            emptySet()
        ).apply { register() }
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val toolbarDynamicHeight = switch(
            R.string.toolbar_dynamic_height,
            "toolbar_dynamic_height",
            false,
            R.string.toolbar_dynamic_height_summary
        )
        val physicalKeyboardHorizontalCandidateBar = switch(
            R.string.physical_keyboard_horizontal_candidate_bar,
            "physical_keyboard_horizontal_candidate_bar",
            false
        )
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val autoCloseBrackets = switch(R.string.auto_close_brackets, "auto_close_brackets", false)
        val insertSpaceBetweenChineseAndEnglish = switch(
            R.string.insert_space_between_chinese_and_english,
            "insert_space_between_chinese_and_english",
            false
        )
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )
        val capsKeyBehavior = enumList(
            R.string.caps_key_behavior,
            "caps_key_behavior",
            CapsKeyBehavior.Default
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input", ""
        ) {
            showVoiceInputButton.getValue() ||
            spaceKeyLongPressBehavior.getValue() == SpaceLongPressBehavior.VoiceInput
        }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val punctuationSwipeStrategy = enumList(
            R.string.punctuation_swipe_strategy,
            "punctuation_swipe_strategy",
            PunctuationSwipeStrategy.Default
        )
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Auto
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        val spaceKeyLongPressBehavior = run {
            val entryValues = listOf(
                SpaceLongPressBehavior.None,
                SpaceLongPressBehavior.Enumerate,
                SpaceLongPressBehavior.RepeatSpace,
                SpaceLongPressBehavior.VoiceInput,
                SpaceLongPressBehavior.SwitchToEnglish
            )
            list(
                R.string.space_long_press_behavior,
                "space_long_press_behavior",
                SpaceLongPressBehavior.None,
                object : ManagedPreference.StringLikeCodec<SpaceLongPressBehavior> {
                    override fun decode(raw: String): SpaceLongPressBehavior = when (raw) {
                        SpaceLongPressBehavior.ToggleActivate.name -> SpaceLongPressBehavior.Enumerate
                        "ShowPicker" -> SpaceLongPressBehavior.Enumerate
                        else -> enumValueOf(raw)
                    }
                },
                entryValues,
                entryValues.map { it.stringRes }
            )
        }
        val spaceKeyLabelMode = enumList(
            R.string.space_key_label_mode,
            "space_key_label_mode",
            SpaceKeyLabelMode.Default
        )
        val predictionSpaceBehavior = enumList(
            R.string.prediction_space_behavior,
            "prediction_space_behavior",
            PredictionSpaceBehavior.CommitSpace
        )
        val predictionBackspaceBehavior = run {
            // Global fallback with the lowest priority; a built-in engine's own
            // "Backspace behavior on prediction" setting takes precedence.
            val entryValues = listOf(
                PredictionBackspaceBehavior.ClearPredictionCandidates,
                PredictionBackspaceBehavior.ClearPredictionCandidatesAndDeleteText,
                PredictionBackspaceBehavior.DeleteText,
            )
            list(
                R.string.prediction_backspace_behavior,
                "prediction_backspace_behavior",
                PredictionBackspaceBehavior.ClearPredictionCandidates,
                object : ManagedPreference.StringLikeCodec<PredictionBackspaceBehavior> {
                    override fun decode(raw: String): PredictionBackspaceBehavior = when (raw) {
                        // Legacy value, relabelled to "clear prediction candidates".
                        "DismissCandidates" -> PredictionBackspaceBehavior.ClearPredictionCandidates
                        else -> enumValueOf(raw)
                    }
                },
                entryValues,
                entryValues.map { it.stringRes }
            )
        }
        val spaceSwipeVerticalBehavior = enumList(
            R.string.space_swipe_vertical_behavior,
            "space_swipe_vertical_behavior",
            SpaceSwipeVerticalBehavior.ArrowKeys
        )
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val textKeyboardLayoutProfile = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile",
            "default"
        ).apply { register() }
        // 布局配置（文件级）在管理页的展示顺序，换行分隔；缺省按名称排序
        val textKeyboardLayoutProfileOrder = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile_order",
            ""
        ).apply { register() }
        // 布局管理页中被折叠的配置名，换行分隔
        val textKeyboardLayoutProfileCollapsed = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile_collapsed",
            ""
        ).apply { register() }
        // 布局管理页中隐藏未定制父级输入法布局的配置名，换行分隔
        val textKeyboardLayoutProfileFiltered = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_profile_filtered",
            ""
        ).apply { register() }
        // 布局管理页中被折叠的父级输入法布局节点，条目为"配置名 <TAB> 父级输入法布局键"，换行分隔
        val textKeyboardLayoutBaseCollapsed = ManagedPreference.PString(
            sharedPreferences,
            "text_keyboard_layout_base_collapsed",
            ""
        ).apply { register() }
        val langSwitchKeyLongPressBehavior = enumList(
            R.string.lang_switch_key_long_press_behavior,
            "lang_switch_key_long_press_behavior",
            LangSwitchLongPressBehavior.Default
        )
        val langSwitchKeyBehavior = run {
            val entryValues = listOf(
                LangSwitchBehavior.Enumerate,
                LangSwitchBehavior.SwitchToEnglish,
                LangSwitchBehavior.NextInputMethodApp
            )
            list(
                R.string.lang_switch_key_behavior,
                "lang_switch_key_behavior",
                LangSwitchBehavior.Enumerate,
                object : ManagedPreference.StringLikeCodec<LangSwitchBehavior> {
                    override fun decode(raw: String): LangSwitchBehavior = when (raw) {
                        LangSwitchBehavior.ToggleActivate.name -> LangSwitchBehavior.Enumerate
                        else -> enumValueOf(raw)
                    }
                },
                entryValues,
                entryValues.map { it.stringRes }
            )
        }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                48,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                300,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        // ===== Split keyboard settings =====
        // Note: Threshold and gap are managed exclusively via SplitKeyboardCalibrationActivity
        // They are stored in Keyboard category to trigger InputView refresh when changed
        val splitKeyboardEnabled = switch(
            R.string.split_keyboard_enabled,
            "split_keyboard_enabled",
            true,  // Default enabled; may be adjusted based on device type on first install
            R.string.split_keyboard_enabled_summary
        )

        // Internal split keyboard settings (no UI, managed via calibration activity)
        val splitKeyboardThreshold = ManagedPreference.PInt(sharedPreferences, "split_keyboard_threshold", 470).apply { register() }
        val splitKeyboardGapPercent = ManagedPreference.PInt(sharedPreferences, "split_keyboard_gap_percent", 20).apply { register() }

        // When enabled, use landscape layout parameters (height/padding/etc.) while split keyboard is active
        val splitKeyboardUseLandscapeLayout = switch(
            R.string.split_keyboard_use_landscape_layout,
            "split_keyboard_use_landscape_layout",
            false,
            R.string.split_keyboard_use_landscape_layout_summary
        )

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val compositionAreaStyle = enumList(
            R.string.preedit_style,
            "preedit_style",
            CompositionAreaStyle.Default,
            showUi = false
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                8,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        init {
            if (sharedPreferences.contains(CANDIDATE_DISPLAY_MODE_KEY)) {
                val storedMode = runCatching {
                    sharedPreferences.getString(CANDIDATE_DISPLAY_MODE_KEY, null)
                        ?.let(FloatingCandidatesMode::valueOf)
                }.getOrNull()
                val normalizedMode = normalizeCandidateDisplayMode(storedMode)
                if (storedMode != normalizedMode) {
                    sharedPreferences.edit {
                        putString(CANDIDATE_DISPLAY_MODE_KEY, normalizedMode.name)
                    }
                }
            }
        }

        val mode = list(
            R.string.candidate_display_mode,
            CANDIDATE_DISPLAY_MODE_KEY,
            FloatingCandidatesMode.InputDevice,
            object : ManagedPreference.StringLikeCodec<FloatingCandidatesMode> {
                override fun decode(raw: String): FloatingCandidatesMode? =
                    runCatching { FloatingCandidatesMode.valueOf(raw) }.getOrNull()
            },
            listOf(
                FloatingCandidatesMode.InputDevice,
                FloatingCandidatesMode.Always,
                FloatingCandidatesMode.SystemDefault
            ),
            listOf(
                R.string.candidate_display_mode_horizontal,
                R.string.candidate_display_mode_floating,
                R.string.system_default
            )
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val virtualKeyboardPosition = enumList(
            R.string.candidates_position,
            "virtual_keyboard_candidates_position",
            FloatingCandidatesVirtualKeyboardPosition.TopLeft
        )

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val candidateHighlightRadius =
            int(R.string.candidate_highlight_radius, "candidate_highlight_radius", 2, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        init {
            val legacyKey = "clipboard_limit"
            val localKey = "clipboard_limit_local"
            val remoteKey = "clipboard_limit_remote"
            val mediaKey = "clipboard_limit_media"
            if (!sharedPreferences.contains(localKey) ||
                !sharedPreferences.contains(remoteKey) ||
                !sharedPreferences.contains(mediaKey)
            ) {
                val legacyValue = if (sharedPreferences.contains(legacyKey)) {
                    sharedPreferences.getInt(legacyKey, 100)
                } else {
                    100
                }
                sharedPreferences.edit {
                    if (!sharedPreferences.contains(localKey)) putInt(localKey, legacyValue)
                    if (!sharedPreferences.contains(remoteKey)) putInt(remoteKey, legacyValue)
                    if (!sharedPreferences.contains(mediaKey)) putInt(mediaKey, legacyValue)
                    remove(legacyKey)
                }
            }
        }

        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimitLocal = int(
            R.string.clipboard_limit_local,
            "clipboard_limit_local",
            100,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitRemote = int(
            R.string.clipboard_limit_remote,
            "clipboard_limit_remote",
            100,
        ) { clipboardListening.getValue() }
        val clipboardHistoryLimitMedia = int(
            R.string.clipboard_limit_media,
            "clipboard_limit_media",
            100,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            3,
            -1,
            Int.MAX_VALUE,
            "s"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )

        /**
         * 符号 / 表情 / 颜文字面板的呈现风格，见 [SymbolPanelStyle]。
         *
         * 默认 [SymbolPanelStyle.Sidebar]（新版左侧分组栏）：沿用既有外观，老用户升级后
         * 第一眼不变；想换旧版顶部分组栏的用户自己到「便捷功能 → 表情和符号」切。
         *
         * 这里不用 [enumList]：旧版本把 [SymbolPanelStyle.TopBar] 存成 `BottomBar`，
         * 枚举改名后必须继续能解码，否则存量用户的偏好会被静默重置。
         */
        private val symbolPanelStyleCodec =
            object : ManagedPreference.StringLikeCodec<SymbolPanelStyle> {
                override fun decode(raw: String): SymbolPanelStyle =
                    SymbolPanelStyle.fromStored(raw)
            }

        val symbolPanelStyle = ManagedPreference.PStringLike(
            sharedPreferences,
            "symbol_panel_style",
            SymbolPanelStyle.Sidebar,
            symbolPanelStyleCodec
        ).apply {
            register()
            val ui = ManagedPreferenceUi.StringList(
                R.string.symbol_panel_style,
                "symbol_panel_style",
                SymbolPanelStyle.Sidebar,
                symbolPanelStyleCodec,
                SymbolPanelStyle.entries,
                SymbolPanelStyle.entries.map { it.stringRes }
            )
            ui.registerUi()
        }

        /**
         * 三份符号 catalog 当前选中的文件；[UserConfigFiles.SYMBOL_CATALOG_BUILTIN]
         * 表示使用 APK 内置数据，否则是用户自备 catalog 的文件名。
         *
         * 这里不注册 UI：选项来自目录扫描，用 [ManagedPreference.PString] + 自定义入口。
         */
        val symbolCatalogSymbols = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_symbols", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        val symbolCatalogEmoji = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_emoji", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        val symbolCatalogKaomoji = ManagedPreference.PString(
            sharedPreferences, "symbol_catalog_kaomoji", UserConfigFiles.SYMBOL_CATALOG_BUILTIN
        ).apply { register() }

        /** 按 catalog 种类取对应的选择偏好。 */
        fun catalogPreference(type: SymbolCatalogType): ManagedPreference.PString = when (type) {
            SymbolCatalogType.Symbols -> symbolCatalogSymbols
            SymbolCatalogType.Emoji -> symbolCatalogEmoji
            SymbolCatalogType.Kaomoji -> symbolCatalogKaomoji
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround,
                internal.floatingModeEnabled,
                internal.autoHideFloatingMoveHandle,
                internal.autoFloatingLandscape,
                internal.floatingKeyboardWidthPortraitRatio,
                internal.floatingKeyboardWidthLandscapeRatio,
                internal.floatingKeyboardHeightPortraitRatio,
                internal.floatingKeyboardHeightLandscapeRatio,
                internal.floatingKeyboardWidthRatio,
                internal.floatingKeyboardHeightRatio,
                internal.floatingKeyboardWidthLegacy,
                internal.floatingKeyboardHeightLegacy,
                internal.floatingKeyboardXPortraitRatio,
                internal.floatingKeyboardYPortraitRatio,
                internal.floatingKeyboardXLandscapeRatio,
                internal.floatingKeyboardYLandscapeRatio,
                internal.floatingKeyboardXPortrait,
                internal.floatingKeyboardYPortrait,
                internal.floatingKeyboardXLandscape,
                internal.floatingKeyboardYLandscape,
                internal.oneHandOnRightPortrait,
                internal.oneHandOnRightLandscape
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        const val CANDIDATE_DISPLAY_MODE_KEY = "show_candidates_window"

        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}
