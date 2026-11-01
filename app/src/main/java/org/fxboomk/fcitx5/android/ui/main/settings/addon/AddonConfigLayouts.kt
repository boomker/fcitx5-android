/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import android.content.Context
import android.os.Build
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout
import java.util.Locale

/**
 * Presentation layouts for addon config pages that need more structure than the
 * raw fcitx option order provides.
 */
object AddonConfigLayouts {

    val FUZZY_MAPPING_OPTIONS = setOf(
        "VE_UE", "IN_ING", "EN_ENG", "AN_ANG", "U_OU", "V_U",
        "IAN_IANG", "UAN_UANG", "C_CH", "F_H", "L_N", "L_R", "S_SH", "Z_ZH"
    )

    fun get(context: Context, uniqueName: String): ConfigLayout? = when (uniqueName) {
        "pinyin" -> PinyinConfigLayout.get(context)
        "rime" -> RimeConfigLayout.get()
        else -> null
    }

    /** Engine addon that owns the per-IM config of [uniqueName]. */
    fun engineAddonForInputMethod(uniqueName: String): String? = when {
        uniqueName == "pinyin" ||
            uniqueName == ShuangpinLayoutKeys.BASE_LAYOUT_KEY ||
            ShuangpinLayoutKeys.isSchemeUniqueName(uniqueName) -> "pinyin"

        uniqueName == "rime" -> "rime"
        else -> null
    }

    /** Layout for pinyin, shuangpin and rime input-method configs. */
    fun getForInputMethod(context: Context, uniqueName: String): ConfigLayout? =
        when (engineAddonForInputMethod(uniqueName)) {
            "pinyin" -> PinyinConfigLayout.get(context)
            "rime" -> RimeConfigLayout.get()
            else -> null
        }

    fun isFuzzySection(layout: ConfigLayout, index: Int): Boolean =
        layout.sections.getOrNull(index)?.options?.contains("Fuzzy") == true

    fun isPinyinTextSection(layout: ConfigLayout, index: Int): Boolean =
        layout.sections.getOrNull(index)?.options?.contains("Punctuation") == true &&
            layout.sections.getOrNull(index)?.options?.contains("Chttrans") == true

    internal fun fuzzyMappingOptionOrder(): List<String> =
        PinyinConfigLayout.fuzzyMappingOptionOrder()

    internal fun fuzzyOptions(mapping: Boolean): List<String> =
        PinyinConfigLayout.fuzzyOptions(mapping)

    fun getFuzzyTab(context: Context, uniqueName: String, mapping: Boolean): ConfigLayout? {
        val base = getForInputMethod(context, uniqueName) ?: return null
        return ConfigLayout(
            focusedCustomName = "Fuzzy",
            includeUnlistedOptions = false,
            children = mapOf(
                "Fuzzy" to ConfigLayout(
                    includeUnlistedOptions = false,
                    sections = listOf(
                        ConfigLayout.Section(
                            options = PinyinConfigLayout.fuzzyOptions(mapping)
                        )
                    ),
                    titleOverrides = base.children["Fuzzy"]?.titleOverrides ?: emptyMap(),
                    summaryOverrides = if (mapping) emptyMap() else {
                        base.children["Fuzzy"]?.summaryOverrides ?: emptyMap()
                    },
                    enumSwitches = base.children["Fuzzy"]?.enumSwitches ?: emptyMap()
                )
            )
        )
    }
}

private object PinyinConfigLayout {

    // Within each section, switches come first (ordered by relevance and label
    // length), followed by enums/numbers and finally navigation or advanced
    // entries. Options hidden by fcitx are listed too, so they stay in place
    // when the user disables "Hide hotkey configurations".
    private val sections = listOf(
        ConfigLayout.Section(
            R.string.pinyin_config_section_input,
            listOf(
                "PreeditCursorPositionAtBeginning",
                "PinyinInPreedit",
                "ShowShuangpinMode",
                "PreeditMode",
                "ShuangpinProfile"
            )
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_section_candidates,
            listOf(
                "SpellEnabled",
                "SymbolsEnabled",
                "ChaiziEnabled",
                "StrokeCandidateEnabled",
                "ExtBEnabled",
                "PageSize",
                "WordCandidateLimit",
                "Number of sentence",
                "LongWordLengthLimit"
            )
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_section_prediction,
            listOf(
                "Prediction",
                "KeepCurrentContext",
                "Learning",
                "PredictionSize",
                "BackspaceBehaviorOnPrediction"
            )
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_section_keys,
            listOf(
                "UseKeypadAsSelection",
                "BackSpaceToUnselect",
                "VAsQuickphrase",
                "SwitchInputMethodBehavior",
                "ChooseCharFromPhrase",
                "ChooseLastCharFromPhrase",
                "QuickPhraseKey",
                "PrevPage",
                "NextPage",
                "PrevCandidate",
                "NextCandidate",
                "SecondCandidate",
                "ThirdCandidate",
                "CurrentCandidate",
                "CommitRawInput",
                "FilterByStroke",
                "ForgetWord"
            )
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_fuzzy,
            listOf("Fuzzy")
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_section_lexicon,
            listOf(
                "DictManager",
                "CustomPhrase",
                "QuickPhrase",
                "QuickPhraseTriggerRegex"
            )
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_section_text,
            listOf(
                "Punctuation",
                "Chttrans"
            )
        ),
        ConfigLayout.Section(
            R.string.pinyin_config_section_cloud,
            listOf(
                "CloudPinyinEnabled",
                "CloudPinyinAnimation",
                "KeepCloudPinyinPlaceHolder",
                "CloudPinyinIndex",
                "CloudPinyin"
            )
        ),
    )

    private val titleOverrides = mapOf(
        "PreeditCursorPositionAtBeginning" to R.string.pinyin_config_cursor_at_start,
        "PinyinInPreedit" to R.string.pinyin_config_full_pinyin,
        "ShowShuangpinMode" to R.string.pinyin_config_show_shuangpin_mode,
        "ShuangpinProfile" to R.string.pinyin_config_shuangpin_profile,
        "SpellEnabled" to R.string.pinyin_config_english_candidates,
        "SymbolsEnabled" to R.string.pinyin_config_symbol_candidates,
        "ChaiziEnabled" to R.string.pinyin_config_chaizi_candidates,
        "StrokeCandidateEnabled" to R.string.pinyin_config_stroke_candidates,
        "ExtBEnabled" to R.string.pinyin_config_extension_b,
        "PageSize" to R.string.pinyin_config_page_size,
        "WordCandidateLimit" to R.string.pinyin_config_phrase_limit,
        "Number of sentence" to R.string.pinyin_config_sentence_count,
        "LongWordLengthLimit" to R.string.pinyin_config_long_word_hint,
        "Prediction" to R.string.pinyin_config_prediction,
        "KeepCurrentContext" to R.string.pinyin_config_keep_context,
        "Learning" to R.string.pinyin_config_learning,
        "PredictionSize" to R.string.pinyin_config_prediction_count,
        "BackspaceBehaviorOnPrediction" to R.string.pinyin_config_backspace_behavior,
        "SwitchInputMethodBehavior" to R.string.pinyin_config_switch_ime_behavior,
        "UseKeypadAsSelection" to R.string.pinyin_config_keypad_selection,
        "BackSpaceToUnselect" to R.string.pinyin_config_backspace_unselect,
        "VAsQuickphrase" to R.string.pinyin_config_v_quickphrase,
        "DictManager" to R.string.pinyin_config_manage_dictionaries,
        "CustomPhrase" to R.string.pinyin_config_custom_phrases,
        "QuickPhrase" to R.string.pinyin_config_quickphrase,
        "Punctuation" to R.string.pinyin_config_punctuation,
        "Chttrans" to R.string.pinyin_config_script_conversion,
        "QuickPhraseTriggerRegex" to R.string.pinyin_config_quickphrase_regex,
        "Fuzzy" to R.string.pinyin_config_fuzzy,
        "CloudPinyinEnabled" to R.string.pinyin_config_cloud_enabled,
        "CloudPinyinAnimation" to R.string.pinyin_config_cloud_animation,
        "KeepCloudPinyinPlaceHolder" to R.string.pinyin_config_cloud_placeholder,
        "CloudPinyinIndex" to R.string.pinyin_config_cloud_index,
        "CloudPinyin" to R.string.pinyin_config_cloud_config
    )

    private val fuzzySettingsOrder = listOf(
        "Correction",
        "NG_GN",
        "Inner",
        "InnerShort",
        "LowerCaseMatchLetter",
        "PartialSp",
        "PartialFinal"
    )

    /** Sort mapping rows by the combined syllable length, keeping c/s/z families together. */
    private val fuzzyMappingSyllables = mapOf(
        "VE_UE" to ("ue" to "ve"),
        "IN_ING" to ("in" to "ing"),
        "EN_ENG" to ("en" to "eng"),
        "AN_ANG" to ("an" to "ang"),
        "U_OU" to ("u" to "ou"),
        "V_U" to ("u" to "v"),
        "IAN_IANG" to ("ian" to "iang"),
        "UAN_UANG" to ("uan" to "uang"),
        "C_CH" to ("c" to "ch"),
        "F_H" to ("f" to "h"),
        "L_N" to ("l" to "n"),
        "L_R" to ("l" to "r"),
        "S_SH" to ("s" to "sh"),
        "Z_ZH" to ("z" to "zh")
    )

    private val groupedFuzzyMappings = setOf("C_CH", "S_SH", "Z_ZH")

    private val fuzzyMappingOrder = fuzzyMappingSyllables.entries
        .sortedWith(
            compareBy<Map.Entry<String, Pair<String, String>>>(
                { it.value.first.length + it.value.second.length },
                { if (it.key in groupedFuzzyMappings) 0 else 1 },
                { it.value.first },
                { it.value.second.length },
                { it.value.second }
            )
        )
        .map { it.key }

    private val fuzzyOrder = fuzzySettingsOrder + fuzzyMappingOrder

    private val fuzzySummaryOverrides = mapOf(
        "Correction" to R.string.pinyin_config_fuzzy_correction_summary,
        "NG_GN" to R.string.pinyin_config_fuzzy_common_typo_summary,
        "Inner" to R.string.pinyin_config_fuzzy_inner_summary,
        "InnerShort" to R.string.pinyin_config_fuzzy_inner_short_summary,
        "LowerCaseMatchLetter" to R.string.pinyin_config_fuzzy_lowercase_summary,
        "PartialFinal" to R.string.pinyin_config_fuzzy_partial_final_summary,
        "PartialSp" to R.string.pinyin_config_fuzzy_partial_sp_summary
    )

    private val fuzzyTitleOverrides = mapOf(
        "NG_GN" to R.string.pinyin_config_fuzzy_common_typo,
        "Inner" to R.string.pinyin_config_fuzzy_inner,
        "InnerShort" to R.string.pinyin_config_fuzzy_inner_short,
        "LowerCaseMatchLetter" to R.string.pinyin_config_fuzzy_lowercase,
        "PartialFinal" to R.string.pinyin_config_fuzzy_partial_final,
        "PartialSp" to R.string.pinyin_config_fuzzy_partial_sp,
        "Correction" to R.string.pinyin_config_fuzzy_correction
    )

    fun fuzzyOptions(mapping: Boolean): List<String> =
        if (mapping) fuzzyMappingOptionOrder() else fuzzySettingsOrder

    fun fuzzyMappingOptionOrder(): List<String> = fuzzyMappingOrder

    fun get(context: Context): ConfigLayout {
        val shortTitles = useShortTitles(context)
        return ConfigLayout(
            sections = sections,
            sectionNavigation = true,
            fallbackSectionTitle = R.string.pinyin_config_section_other,
            titleOverrides = (if (shortTitles) titleOverrides else emptyMap()) +
                mapOf("PreeditMode" to R.string.preedit_style),
            summaryOverrides = mapOf(
                "PreeditCursorPositionAtBeginning" to
                    R.string.pinyin_config_cursor_at_start_summary
            ),
            entryTitleOverrides = if (shortTitles) {
                mapOf(
                    "SwitchInputMethodBehavior" to mapOf(
                        "Commit current preedit" to R.string.pinyin_config_commit_preedit
                    )
                )
            } else {
                emptyMap()
            },
            preeditStyleOption = "PreeditMode",
            children = mapOf(
                "Fuzzy" to ConfigLayout(
                    collapsible = false,
                    sections = listOf(ConfigLayout.Section(options = fuzzyOrder)),
                    titleOverrides = if (shortTitles) fuzzyTitleOverrides else emptyMap(),
                    summaryOverrides = if (shortTitles) fuzzySummaryOverrides else emptyMap(),
                    enumSwitches = mapOf(
                        "Correction" to ConfigLayout.EnumSwitch(
                            onValue = "QWERTY",
                            offValue = "None"
                        )
                    )
                )
            )
        )
    }

    /**
     * Short titles are only defined for English and Chinese. Other locales keep
     * the (already localized) fcitx descriptions instead of falling back to
     * English resource strings.
     */
    private fun useShortTitles(context: Context): Boolean {
        val language = context.resources.configuration.locales[0].language
        return language == Locale.ENGLISH.language || language == Locale.CHINESE.language
    }
}

private object RimeConfigLayout {
    fun get(): ConfigLayout = ConfigLayout(
        titleOverrides = mapOf(
            "PreeditMode" to R.string.preedit_style,
            "PreeditCursorPositionAtBeginning" to R.string.pinyin_config_cursor_at_start
        ),
        summaryOverrides = mapOf(
            "PreeditCursorPositionAtBeginning" to
                R.string.pinyin_config_cursor_at_start_summary
        ),
        preeditStyleOption = "PreeditMode"
    )
}
