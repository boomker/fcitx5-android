/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import androidx.annotation.StringRes

/**
 * Optional presentation layout for a preference page generated from a fcitx config.
 *
 * Options are matched by their fcitx option name. Anything not listed in any
 * section is rendered in a final fallback section, so options added by newer
 * fcitx versions are never silently dropped.
 */
data class ConfigLayout(
    val sections: List<Section> = emptyList(),
    /** Render sections as navigation entries on the root page. */
    val sectionNavigation: Boolean = false,
    /** Render only the children of this top-level custom option. */
    val focusedCustomName: String? = null,
    /** Keep custom options omitted from [sections] out of the generated page. */
    val includeUnlistedOptions: Boolean = true,
    /** Whether custom options using this layout should be collapsible. */
    val collapsible: Boolean = true,
    /** Initial collapsed state for custom options using this layout. */
    val initiallyCollapsed: Boolean = false,
    @StringRes val fallbackSectionTitle: Int? = null,
    /** Overrides for option titles, keyed by fcitx option name. */
    val titleOverrides: Map<String, Int> = emptyMap(),
    /** Overrides for option summaries, keyed by fcitx option name. */
    val summaryOverrides: Map<String, Int> = emptyMap(),
    /**
     * Overrides for individual enum entries, keyed by fcitx option name and then
     * by the raw enum value.
     */
    val entryTitleOverrides: Map<String, Map<String, Int>> = emptyMap(),
    /** Presentation transforms over pairs of raw enum values and localized labels. */
    val enumEntryTransforms: Map<String, (List<Pair<String, String>>) -> List<Pair<String, String>>> = emptyMap(),
    /**
     * Enum options that should be presented as a two-state switch, keyed by
     * fcitx option name.
     */
    val enumSwitches: Map<String, EnumSwitch> = emptyMap(),
    /**
     * Engine preedit mode option rendered as the app-level preedit style selector.
     */
    val preeditStyleOption: String? = null,
    /** Per-scope storage for the app-level preedit style. */
    val preeditStyleScope: PreeditStyleScope? = null,
    /** Layouts for nested custom options, keyed by fcitx option name. */
    val children: Map<String, ConfigLayout> = emptyMap()
) {
    data class Section(
        @StringRes val title: Int? = null,
        val options: List<String> = emptyList(),
        /** Render this section as a collapsible preference group. */
        val collapsible: Boolean = false,
        /** Initial collapsed state when [collapsible] is true. */
        val initiallyCollapsed: Boolean = false
    )

    /**
     * Maps a boolean switch state to the raw enum values understood by fcitx.
     */
    data class EnumSwitch(val onValue: String, val offValue: String)

    fun titleOverride(context: Context, option: String): String? =
        titleOverrides[option]?.let(context::getString)

    fun summaryOverride(context: Context, option: String): String? =
        summaryOverrides[option]?.let(context::getString)

    fun entryTitleOverride(context: Context, option: String, value: String): String? =
        entryTitleOverrides[option]?.get(value)?.let(context::getString)

    fun sectionTitle(context: Context, index: Int): String? =
        sections.getOrNull(index)?.title?.let(context::getString)
}
