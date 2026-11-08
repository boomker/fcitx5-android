/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout

/** Presentation only: the option names and values still belong to the table engine. */
internal object WubiConfigLayout {
    fun getGlobal(): ConfigLayout = ConfigLayout(
        enumEntryTransforms = mapOf("ShuangpinProfile" to ::lookupShuangpinEntries)
    )

    private fun lookupShuangpinEntries(entries: List<Pair<String, String>>): List<Pair<String, String>> =
        entries.filterNot { it.first == "Zhongwenzhixing" }.sortedWith(
            compareBy<Pair<String, String>> {
                when (it.first) {
                    "Do not display" -> 0
                    "Custom" -> 2
                    else -> 1
                }
            }.thenBy { it.second.codePointCount(0, it.second.length) }
        )

    fun get(): ConfigLayout = ConfigLayout(
        sectionNavigation = true,
        fallbackSectionTitle = R.string.pinyin_config_section_other,
        sections = listOf(
            ConfigLayout.Section(
                R.string.wubi_config_section_input,
                listOf(
                    "FirstCandidateAsPreedit",
                    "PreeditCursorPositionAtBeginning",
                    "CommitWhenDeactivate",
                    "CommitInvalidSegment",
                    "ExactMatch",
                    "AutoSelect",
                    "CommitAfterSelect",
                    "AutoSelectLength",
                    "AutoSelectRegex",
                    "NoMatchAutoSelectLength",
                    "NoMatchAutoSelectRegex"
                )
            ),
            ConfigLayout.Section(
                R.string.wubi_config_section_candidates,
                listOf(
                    "SortByCodeLength",
                    "UseSystemLanguageModel",
                    "UseContextRelatedOrder",
                    "PageSize",
                    "OrderPolicy",
                    "NoSortInputLength",
                    "CandidateLayoutHint"
                )
            ),
            ConfigLayout.Section(
                R.string.wubi_config_section_lexicon,
                listOf(
                    "Learning",
                    "AutoPhraseWithPhrase",
                    "AutoPhraseLength",
                    "SaveAutoPhraseAfter",
                    "MarkerForAutoPhrase",
                    "TableGlobal",
                    // Keep dictionary metadata with its options when exposed.
                    "File",
                    "Icon",
                    "AutoRuleSet"
                )
            ),
            ConfigLayout.Section(
                R.string.wubi_config_section_hints,
                listOf(
                    "Hint",
                    "DisplayCustomHint",
                    "SpaceBeforeHint",
                    "HintSeparator"
                )
            ),
            ConfigLayout.Section(
                R.string.wubi_config_section_keys,
                listOf(
                    "UseFullWidth",
                    "IgnorePunc",
                    "KeypadAsInput",
                    "QuickPhraseText",
                    "QuickPhraseKey",
                    "MatchingKey",
                    "PinyinKey",
                    "PrevPage",
                    "NextPage",
                    "PrevCandidate",
                    "NextCandidate",
                    "DefaultCandidate",
                    "SecondCandidate",
                    "ThirdCandidate",
                    "Selection",
                    "EndKey"
                )
            )
        ),
        titleOverrides = mapOf(
            "FirstCandidateAsPreedit" to R.string.wubi_config_first_candidate_preedit,
            "PreeditCursorPositionAtBeginning" to R.string.pinyin_config_cursor_at_start,
            "CommitWhenDeactivate" to R.string.wubi_config_commit_when_deactivate,
            "CommitInvalidSegment" to R.string.wubi_config_commit_invalid_segment,
            "AutoSelect" to R.string.wubi_config_auto_select,
            "CommitAfterSelect" to R.string.wubi_config_commit_after_select,
            "AutoSelectLength" to R.string.wubi_config_auto_select_length,
            "AutoSelectRegex" to R.string.wubi_config_auto_select_regex,
            "NoMatchAutoSelectLength" to R.string.wubi_config_no_match_auto_select_length,
            "NoMatchAutoSelectRegex" to R.string.wubi_config_no_match_auto_select_regex,
            "SortByCodeLength" to R.string.wubi_config_sort_by_code_length,
            "UseSystemLanguageModel" to R.string.wubi_config_system_language_model,
            "UseContextRelatedOrder" to R.string.wubi_config_context_order,
            "PageSize" to R.string.pinyin_config_page_size,
            "NoSortInputLength" to R.string.wubi_config_no_sort_input_length,
            "CandidateLayoutHint" to R.string.wubi_config_candidate_direction,
            "Learning" to R.string.pinyin_config_learning,
            "AutoPhraseWithPhrase" to R.string.wubi_config_auto_phrase_with_phrase,
            "AutoPhraseLength" to R.string.wubi_config_auto_phrase_length,
            "SaveAutoPhraseAfter" to R.string.wubi_config_save_auto_phrase_after,
            "MarkerForAutoPhrase" to R.string.wubi_config_auto_phrase_marker,
            "TableGlobal" to R.string.global_options,
            "Hint" to R.string.wubi_config_hint,
            "DisplayCustomHint" to R.string.wubi_config_custom_hint,
            "SpaceBeforeHint" to R.string.wubi_config_space_before_hint,
            "HintSeparator" to R.string.wubi_config_hint_separator,
            "KeypadAsInput" to R.string.wubi_config_keypad_input,
            "QuickPhraseText" to R.string.wubi_config_quickphrase_text,
            "MatchingKey" to R.string.wubi_config_matching_key,
            "PinyinKey" to R.string.wubi_config_pinyin_key
        ),
        summaryOverrides = mapOf(
            "PreeditCursorPositionAtBeginning" to R.string.pinyin_config_cursor_at_start_summary,
            "NoSortInputLength" to R.string.wubi_config_no_sort_input_length_summary,
            "CommitAfterSelect" to R.string.wubi_config_commit_after_select_summary,
            "CommitWhenDeactivate" to R.string.wubi_config_commit_when_deactivate_summary
        )
    )
}
