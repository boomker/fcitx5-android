/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutThemeStore
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.KeyIconStore
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.FontsetEditorActivity
import org.fxboomk.fcitx5.android.utils.SingleLineSummaryPreference
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import org.fxboomk.fcitx5.android.utils.setup

class ThemeAppearanceFragment : ManagedPreferenceFragment(ThemeManager.prefs) {

    companion object {
        private const val THEME_ENTRY_KEY = "theme_list_entry"
        private const val THEME_MODE_ENTRY_KEY = "theme_mode_entry"
        private const val FONTSET_ENTRY_KEY = "theme_fontset_entry"
        private const val KEY_MAIN_FONT_KEY = "key_main_font"
        private const val DEFAULT_KEY_MAIN_FONT_SIZE = 23f
        private const val KEY_STYLE_ENTRY_KEY = "theme_key_style_entry"
        private const val TOOLBAR_STYLE_ENTRY_KEY = "theme_toolbar_style_entry"
        internal const val BUTTON_STYLE_ENTRY_KEY = "theme_button_style_entry"
        private const val CANDIDATE_STYLE_ENTRY_KEY = "theme_candidate_style_entry"
        private const val ANIMATION_ENTRY_KEY = "theme_animation_entry"
        private const val OTHER_STYLE_ENTRY_KEY = "theme_other_style_entry"
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.removeAll()
        val ctx = requireContext()
        screen.addPreference(
            SingleLineSummaryPreference(ctx).apply {
                key = THEME_ENTRY_KEY
                setTitle(R.string.keyboard_theme)
                setSummary(ThemeManager.activeTheme.name)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setOnPreferenceClickListener {
                    navigateWithAnim(SettingsRoute.ThemeList)
                    true
                }
            }
        )
        addSummaryDestination(
            screen,
            ctx,
            THEME_MODE_ENTRY_KEY,
            R.string.theme_category_theme_mode,
            buildThemeModeSummary(ctx),
            SettingsRoute.ThemeMode
        )
        addSummaryDestination(
            screen,
            ctx,
            KEY_STYLE_ENTRY_KEY,
            R.string.theme_category_key_style,
            buildKeyStyleSummary(ctx),
            SettingsRoute.ThemeKeyStyle
        )
        screen.addPreference(
            SingleLineSummaryPreference(ctx).apply {
                key = FONTSET_ENTRY_KEY
                setup(
                    ctx.getString(R.string.edit_fontset),
                    buildFontsetSummary(ctx)
                ) {
                    startActivity(Intent(requireContext(), FontsetEditorActivity::class.java))
                }
            }
        )
        addSummaryDestination(
            screen,
            ctx,
            CANDIDATE_STYLE_ENTRY_KEY,
            R.string.theme_category_candidate_style,
            buildCandidateStyleSummary(ctx),
            SettingsRoute.ThemeCandidateStyle
        )
        addSummaryDestination(
            screen,
            ctx,
            TOOLBAR_STYLE_ENTRY_KEY,
            R.string.theme_category_toolbar_style,
            buildToolbarStyleSummary(ctx),
            SettingsRoute.ThemeToolbarStyle
        )
        screen.addPreference(
            SingleLineSummaryPreference(ctx).apply {
                key = BUTTON_STYLE_ENTRY_KEY
                setup(
                    ctx.getString(R.string.edit_buttons),
                    buildButtonStyleSummary()
                ) {
                    startActivity(Intent(requireContext(), ButtonsCustomizerActivity::class.java))
                }
            }
        )
        addSummaryDestination(
            screen,
            ctx,
            ANIMATION_ENTRY_KEY,
            R.string.theme_category_animation,
            buildAnimationSummary(ctx),
            SettingsRoute.ThemeAnimation
        )
        addSummaryDestination(
            screen,
            ctx,
            OTHER_STYLE_ENTRY_KEY,
            R.string.theme_category_other_style,
            buildOtherStyleSummary(ctx),
            SettingsRoute.ThemeOtherStyle
        )
    }

    private fun addSummaryDestination(
        screen: PreferenceScreen,
        ctx: Context,
        key: String,
        @StringRes title: Int,
        summary: String,
        route: SettingsRoute
    ) {
        screen.addPreference(
            SingleLineSummaryPreference(ctx).apply {
                this.key = key
                setup(ctx.getString(title), summary) {
                    navigateWithAnim(route)
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val ctx = context ?: return
        findPreference<Preference>(THEME_ENTRY_KEY)?.summary = ThemeManager.activeTheme.name
        findPreference<Preference>(THEME_MODE_ENTRY_KEY)?.summary = buildThemeModeSummary(ctx)
        findPreference<Preference>(FONTSET_ENTRY_KEY)?.summary = buildFontsetSummary(ctx)
        findPreference<Preference>(KEY_STYLE_ENTRY_KEY)?.summary = buildKeyStyleSummary(ctx)
        findPreference<Preference>(TOOLBAR_STYLE_ENTRY_KEY)?.summary =
            buildToolbarStyleSummary(ctx)
        findPreference<Preference>(BUTTON_STYLE_ENTRY_KEY)?.summary = buildButtonStyleSummary()
        findPreference<Preference>(CANDIDATE_STYLE_ENTRY_KEY)?.summary =
            buildCandidateStyleSummary(ctx)
        findPreference<Preference>(ANIMATION_ENTRY_KEY)?.summary = buildAnimationSummary(ctx)
        findPreference<Preference>(OTHER_STYLE_ENTRY_KEY)?.summary = buildOtherStyleSummary(ctx)
    }

    private fun buildFontsetSummary(ctx: Context): String {
        val fontset = ConfigProviders.readFontsetPathMapSnapshot().getOrNull()?.value
        val keyMainSet = fontset?.get(KEY_MAIN_FONT_KEY)?.any { it.isNotBlank() } == true
        val fontSize = FontProviders.getFontSize(KEY_MAIN_FONT_KEY, DEFAULT_KEY_MAIN_FONT_SIZE)
        return ctx.getString(
            R.string.edit_fontset_summary_key_main,
            ctx.getString(
                if (keyMainSet) R.string.fontset_font_set else R.string.fontset_font_not_set
            ),
            ctx.getString(R.string.font_size_value, fontSize.toInt())
        )
    }

    private fun buildButtonStyleSummary(): String = requireContext().getString(
        R.string.edit_buttons_summary,
        ButtonsLayoutThemeStore.activeTheme().name,
        KeyIconStore.activeTheme().name
    )

    private fun buildThemeModeSummary(ctx: Context): String {
        val prefs = ThemeManager.prefs
        val summaryRes = if (prefs.followSystemDayNightTheme.getValue()) {
            R.string.theme_mode_summary_follow_system
        } else {
            R.string.theme_mode_summary_manual
        }
        return ctx.getString(
            summaryRes,
            prefs.lightModeThemes.getThemes().size,
            prefs.darkModeThemes.getThemes().size
        )
    }

    private fun buildKeyStyleSummary(ctx: Context): String {
        val prefs = ThemeManager.prefs
        return ctx.getString(
            R.string.theme_key_style_summary,
            enabledLabel(ctx, prefs.keyBorder.getValue()),
            prefs.keyRadius.getValue()
        )
    }

    private fun buildToolbarStyleSummary(ctx: Context): String {
        val prefs = ThemeManager.prefs
        return ctx.getString(
            R.string.theme_toolbar_style_summary,
            enabledLabel(ctx, prefs.navbarBorder.getValue()),
            enabledLabel(ctx, AppPrefs.getInstance().keyboard.toolbarDynamicHeight.getValue()),
            prefs.toolbarHeight.getValue()
        )
    }

    private fun buildCandidateStyleSummary(ctx: Context): String {
        val candidates = AppPrefs.getInstance().candidates
        val mode = candidates.mode.getValue()
        val modeLabel = ctx.getString(
            when (mode) {
                FloatingCandidatesMode.Always -> R.string.candidate_display_mode_floating
                FloatingCandidatesMode.SystemDefault -> R.string.system_default
                else -> R.string.candidate_display_mode_horizontal
            }
        )
        val highlightRadius = candidates.candidateHighlightRadius.getValue()
        return if (mode == FloatingCandidatesMode.Always) {
            ctx.getString(
                R.string.theme_candidate_style_summary_floating,
                modeLabel,
                ctx.getString(candidates.orientation.getValue().stringRes),
                highlightRadius
            )
        } else {
            ctx.getString(
                R.string.theme_candidate_style_summary,
                modeLabel,
                highlightRadius
            )
        }
    }

    private fun buildAnimationSummary(ctx: Context): String = ctx.getString(
        R.string.theme_animation_summary,
        enabledLabel(ctx, ThemeManager.prefs.keyRippleEffect.getValue()),
        enabledLabel(ctx, !AppPrefs.getInstance().advanced.disableAnimation.getValue())
    )

    private fun buildOtherStyleSummary(ctx: Context): String {
        val prefs = ThemeManager.prefs
        return ctx.getString(
            R.string.theme_other_style_summary,
            prefs.textEditingButtonRadius.getValue(),
            prefs.clipboardEntryRadius.getValue()
        )
    }

    private fun enabledLabel(ctx: Context, enabled: Boolean): String =
        ctx.getString(if (enabled) R.string.enabled else R.string.disabled)
}
