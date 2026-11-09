/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.preference.Preference
import arrow.core.getOrElse
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeCodec
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.picker.SymbolCatalogType
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonConfigLayouts
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonDisplayNames
import org.fxboomk.fcitx5.android.ui.main.settings.addon.PinyinTextConfigFragment
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsHomeFragment
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsSupport
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.SymbolSettingsFragment
import org.fxboomk.fcitx5.android.ui.main.settings.im.CustomShuangpinFragment
import org.fxboomk.fcitx5.android.ui.main.settings.im.CustomShuangpinMappingFragment
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeAppearanceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeSettingsSupport
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.utils.Const
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fxboomk.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fxboomk.fcitx5.android.utils.config.ConfigType
import java.util.Locale

data class SettingsSearchResult(
    val title: String,
    val path: List<String>,
    val route: SettingsRoute? = null,
    val preferenceKey: String? = null,
    val summary: String? = null,
    val keywords: List<String> = emptyList(),
    val externalUri: String? = null,
    /**
     * Index of the tab holding the preference, for pages that host their preferences
     * in a ViewPager. Null when the page has a single tab.
     */
    val tab: Int? = null
) {
    private val searchableTitle: String by lazy {
        title.lowercase(Locale.getDefault())
    }

    private val searchableText: String by lazy {
        (listOf(title, preferenceKey.orEmpty(), summary.orEmpty()) + path + keywords)
            .joinToString(" ")
            .lowercase(Locale.getDefault())
    }

    fun matches(query: String): Boolean {
        val terms = query.searchTerms()
        return terms.isNotEmpty() && terms.all(searchableText::contains)
    }

    fun titleContainsSearchTerm(query: String): Boolean {
        val terms = query.searchTerms()
        return terms.any(searchableTitle::contains)
    }
}

fun List<SettingsSearchResult>.sortedForSettingsSearch(query: String): List<SettingsSearchResult> =
    sortedWith(
        compareBy<SettingsSearchResult> { if (it.titleContainsSearchTerm(query)) 0 else 1 }
            .thenBy { it.path.joinToString("/") }
            .thenBy { it.title }
    )

private fun String.searchTerms(): List<String> = trim()
    .lowercase(Locale.getDefault())
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }

/**
 * Localized labels needed to build the searchable entries of the custom shuangpin
 * "Syllable mapping" tab. Kept free of any Android dependency so that the entry
 * list can be verified by plain unit tests.
 */
internal data class CustomShuangpinMappingLabels(
    val pageTitle: String,
    val tabTitle: String,
    val zeroInitialTitle: String,
    val initialsTitle: String,
    val finalsTitle: String,
    val syllablesTitle: String,
    val editSyllablesTitle: String,
    val noneTitle: String,
    val keyword: String
)

/**
 * Searchable entries of the custom shuangpin "Syllable mapping" tab. Every entry
 * points at the tab holding the preference, so that selecting it opens the right
 * page and scrolls to the right item.
 */
internal fun buildCustomShuangpinMappingItems(
    labels: CustomShuangpinMappingLabels,
    schemeInitials: Map<String, String>,
    schemeFinals: Map<String, String>
): List<SettingsSearchResult> {
    val basePath = listOf(labels.pageTitle, labels.tabTitle)
    val keywords = listOf(labels.keyword)
    return buildList {
        add(
            SettingsSearchResult(
                title = labels.zeroInitialTitle,
                path = basePath,
                route = SettingsRoute.CustomShuangpin,
                preferenceKey = CustomShuangpinMappingFragment.KEY_ZERO_INITIAL,
                summary = labels.zeroInitialTitle,
                keywords = keywords,
                tab = CustomShuangpinFragment.TAB_MAPPING
            )
        )
        add(
            SettingsSearchResult(
                title = labels.initialsTitle,
                path = basePath,
                route = SettingsRoute.CustomShuangpin,
                preferenceKey = CustomShuangpinMappingFragment.KEY_INITIALS_GROUP,
                summary = labels.initialsTitle,
                keywords = keywords,
                tab = CustomShuangpinFragment.TAB_MAPPING
            )
        )
        add(
            SettingsSearchResult(
                title = labels.finalsTitle,
                path = basePath,
                route = SettingsRoute.CustomShuangpin,
                preferenceKey = CustomShuangpinMappingFragment.KEY_FINALS_GROUP,
                summary = labels.finalsTitle,
                keywords = keywords,
                tab = CustomShuangpinFragment.TAB_MAPPING
            )
        )
        add(
            SettingsSearchResult(
                title = labels.syllablesTitle,
                path = basePath,
                route = SettingsRoute.CustomShuangpin,
                preferenceKey = CustomShuangpinMappingFragment.KEY_SYLLABLES_GROUP,
                summary = labels.syllablesTitle,
                keywords = keywords,
                tab = CustomShuangpinFragment.TAB_MAPPING
            )
        )
        add(
            SettingsSearchResult(
                title = labels.editSyllablesTitle,
                path = basePath + labels.syllablesTitle,
                route = SettingsRoute.CustomShuangpin,
                preferenceKey = CustomShuangpinMappingFragment.KEY_SYLLABLES,
                summary = labels.syllablesTitle,
                keywords = keywords,
                tab = CustomShuangpinFragment.TAB_MAPPING
            )
        )
        CustomShuangpinSchemeCodec.INITIALS.forEach { initial ->
            add(
                SettingsSearchResult(
                    title = initial,
                    path = basePath + labels.initialsTitle,
                    route = SettingsRoute.CustomShuangpin,
                    preferenceKey = CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + initial,
                    summary = schemeInitials[initial].orEmpty().ifEmpty { labels.noneTitle },
                    keywords = keywords,
                    tab = CustomShuangpinFragment.TAB_MAPPING
                )
            )
        }
        CustomShuangpinSchemeCodec.FINALS.forEach { final ->
            add(
                SettingsSearchResult(
                    title = final,
                    path = basePath + labels.finalsTitle,
                    route = SettingsRoute.CustomShuangpin,
                    preferenceKey = CustomShuangpinMappingFragment.KEY_FINAL_PREFIX + final,
                    summary = schemeFinals[final].orEmpty().ifEmpty { labels.noneTitle },
                    keywords = keywords,
                    tab = CustomShuangpinFragment.TAB_MAPPING
                )
            )
        }
    }
}

data class ToolbarMenuSearchSpec(
    @StringRes val title: Int,
    val route: SettingsRoute? = null,
    @StringRes val parent: Int? = null,
    @StringRes val section: Int? = null,
    @StringRes val summary: Int? = null,
    val externalUri: String? = null,
    val keywords: List<String> = emptyList()
)

object SettingsSearchIndex {
    val toolbarMenuSearchSpecs = listOf(
        ToolbarMenuSearchSpec(R.string.faq, parent = R.string.about, externalUri = Const.faqUrl),
        ToolbarMenuSearchSpec(R.string.developer, SettingsRoute.Developer, R.string.advanced),
        ToolbarMenuSearchSpec(R.string.about, SettingsRoute.About),
        ToolbarMenuSearchSpec(
            R.string.real_time_logs,
            SettingsRoute.Developer,
            R.string.advanced,
            R.string.developer
        ),
        ToolbarMenuSearchSpec(R.string.verbose_log, SettingsRoute.Advanced, R.string.advanced),
        ToolbarMenuSearchSpec(
            R.string.editor_info_inspector,
            SettingsRoute.Developer,
            R.string.advanced,
            R.string.developer
        ),
        ToolbarMenuSearchSpec(
            R.string.restart_fcitx_instance,
            SettingsRoute.Developer,
            R.string.advanced,
            R.string.developer,
            summary = R.string.restart_fcitx_instance_confirm
        ),
        ToolbarMenuSearchSpec(
            R.string.delete_and_sync_data,
            SettingsRoute.Developer,
            R.string.advanced,
            R.string.developer,
            summary = R.string.delete_and_sync_data_message
        ),
        ToolbarMenuSearchSpec(R.string.clear_clb_db, SettingsRoute.Clipboard, R.string.clipboard),
        ToolbarMenuSearchSpec(
            R.string.capture_heap_dump,
            SettingsRoute.Developer,
            R.string.advanced,
            R.string.developer
        ),
        ToolbarMenuSearchSpec(R.string.privacy_policy, SettingsRoute.About, R.string.about),
        ToolbarMenuSearchSpec(
            R.string.open_source_licenses,
            SettingsRoute.License,
            R.string.about,
            summary = R.string.licenses_of_third_party_libraries
        ),
        ToolbarMenuSearchSpec(R.string.source_code, SettingsRoute.About, R.string.about),
        ToolbarMenuSearchSpec(
            R.string.license,
            SettingsRoute.About,
            R.string.about,
            keywords = listOf(Const.licenseSpdxId)
        ),
        ToolbarMenuSearchSpec(
            R.string.current_version,
            SettingsRoute.About,
            R.string.about,
            R.string.version,
            keywords = listOf(Const.versionName)
        ),
        ToolbarMenuSearchSpec(
            R.string.upgrade_latest,
            SettingsRoute.About,
            R.string.about,
            R.string.version,
            keywords = listOf("check update", "检查更新")
        ),
        ToolbarMenuSearchSpec(
            R.string.build_git_hash,
            SettingsRoute.About,
            R.string.about,
            R.string.version,
            keywords = listOf(BuildConfig.BUILD_GIT_HASH)
        ),
        ToolbarMenuSearchSpec(
            R.string.build_time,
            SettingsRoute.About,
            R.string.about,
            R.string.version
        ),
        ToolbarMenuSearchSpec(
            R.string.manage_plugins,
            SettingsRoute.Plugin,
            R.string.plugins,
            summary = R.string.manage_plugins_hint
        ),
        ToolbarMenuSearchSpec(
            R.string.uninstall_selected_plugins,
            SettingsRoute.Plugin,
            R.string.plugins,
            R.string.manage_plugins,
            R.string.uninstall_plugins_confirm
        ),
        ToolbarMenuSearchSpec(
            R.string.upgrade_selected_plugins,
            SettingsRoute.Plugin,
            R.string.plugins,
            R.string.manage_plugins
        )
    )

    private val hideKeyConfig: Boolean
        get() = AppPrefs.getInstance().advanced.hideKeyConfig.getValue()

    fun androidItems(context: Context): List<SettingsSearchResult> {
        val prefs = AppPrefs.getInstance()
        return buildList {
            addPage(context, R.string.global_options, SettingsRoute.GlobalConfig)
            addPage(context, R.string.addons, SettingsRoute.AddonList)
            addPage(context, R.string.input_methods, SettingsRoute.InputMethodList)
            addPage(context, R.string.theme_appearance, SettingsRoute.Theme)
            addPage(context, R.string.virtual_keyboard, SettingsRoute.VirtualKeyboard)
            addPage(context, R.string.emoji_and_symbols, SettingsRoute.Symbol)
            addPage(context, R.string.llm_settings_title, SettingsRoute.Llm)
            addPage(context, R.string.clipboard, SettingsRoute.Clipboard)
            addPage(context, R.string.plugins, SettingsRoute.Plugin)
            addPage(context, R.string.advanced, SettingsRoute.Advanced)

            addAll(keyboardItems(context, prefs.keyboard))
            addAll(managedItems(context, prefs.symbols, SettingsRoute.Symbol, R.string.emoji_and_symbols))
            addAll(symbolCatalogItems(context))
            addAll(themeItems(context))
            addAll(managedItems(context, prefs.clipboard, SettingsRoute.Clipboard, R.string.clipboard))
            addAll(
                managedItems(context, prefs.advanced, SettingsRoute.Advanced, R.string.advanced)
                    .filterNot {
                        it.preferenceKey in ThemeSettingsSupport.advancedMovedKeys ||
                            it.preferenceKey == prefs.advanced.allowOriginalPlugins.key
                    }
            )
            addAll(advancedExtraItems(context))
            addAll(llmItems(context))
            addAll(toolbarMenuItems(context))
        }.distinctBy { listOf(it.route.toString(), it.preferenceKey.orEmpty(), it.title).joinToString("|") }
    }

    suspend fun fcitxItems(context: Context, fcitx: FcitxAPI): List<SettingsSearchResult> =
        buildList {
            addAll(
                rawConfigItems(
                    context,
                    context.getString(R.string.global_options),
                    SettingsRoute.GlobalConfig,
                    fcitx.getGlobalConfig()
                )
            )
            fcitx.enabledIme().forEach { ime ->
                if (CustomShuangpinSchemeStore.isCustomScheme(ime.uniqueName)) {
                    add(
                        SettingsSearchResult(
                            title = CustomShuangpinSchemeStore.displayName(),
                            path = listOf(context.getString(R.string.input_methods)),
                            route = SettingsRoute.CustomShuangpin,
                            summary = context.getString(R.string.custom_shuangpin)
                        )
                    )
                    addAll(customShuangpinMappingItems(context))
                    addAll(
                        rawConfigItems(
                            context,
                            CustomShuangpinSchemeStore.displayName(),
                            SettingsRoute.CustomShuangpin,
                            fcitx.getImConfig(ime.uniqueName),
                            tab = CustomShuangpinFragment.TAB_PREFERENCES,
                            extraPath = listOf(
                                context.getString(R.string.custom_shuangpin_tab_preferences)
                            ),
                            layout = AddonConfigLayouts.getForInputMethod(
                                context,
                                ime.uniqueName
                            )
                        )
                    )
                    return@forEach
                }
                addAll(
                    rawConfigItems(
                        context,
                        ime.displayName,
                        SettingsRoute.InputMethodConfig(ime.displayName, ime.uniqueName),
                        fcitx.getImConfig(ime.uniqueName),
                        layout = AddonConfigLayouts.getForInputMethod(
                            context,
                            ime.uniqueName
                        )
                    )
                )
            }
            fcitx.addons()
                .filter { it.enabled && it.isConfigurable }
                .forEach { addon ->
                    val raw = fcitx.getAddonConfig(addon.uniqueName)
                    if (PreferenceScreenFactory.hasVisibleOptions(raw)) {
                        val addonName = AddonDisplayNames.of(context, addon)
                        addAll(
                            rawConfigItems(
                                context,
                                addonName,
                                SettingsRoute.AddonConfig(addonName, addon.uniqueName),
                                raw,
                                layout = AddonConfigLayouts.get(context, addon.uniqueName)
                            )
                        )
                    }
                }
        }

    /**
     * Items living in the "Syllable mapping" tab of the custom shuangpin page.
     */
    private fun customShuangpinMappingItems(context: Context): List<SettingsSearchResult> {
        val scheme = CustomShuangpinSchemeStore.load()
        return buildCustomShuangpinMappingItems(
            CustomShuangpinMappingLabels(
                pageTitle = CustomShuangpinSchemeStore.displayName(),
                tabTitle = context.getString(R.string.custom_shuangpin_tab_mapping),
                zeroInitialTitle = context.getString(R.string.custom_shuangpin_zero_initial),
                initialsTitle = context.getString(R.string.custom_shuangpin_initials),
                finalsTitle = context.getString(R.string.custom_shuangpin_finals),
                syllablesTitle = context.getString(R.string.custom_shuangpin_syllables),
                editSyllablesTitle = context.getString(R.string.custom_shuangpin_edit_syllables),
                noneTitle = context.getString(R.string.custom_shuangpin_key_none),
                keyword = context.getString(R.string.custom_shuangpin)
            ),
            scheme.initials,
            scheme.finals
        )
    }

    private fun MutableList<SettingsSearchResult>.addPage(
        context: Context,
        @StringRes title: Int,
        route: SettingsRoute
    ) {
        val label = context.getString(title)
        add(SettingsSearchResult(label, listOf(label), route))
    }

    private fun themeItems(context: Context): List<SettingsSearchResult> = buildList {
        val prefs = AppPrefs.getInstance()
        val pageTitle = context.getString(R.string.theme_appearance)
        val pagePath = listOf(pageTitle)
        val candidatePath = pagePath + context.getString(R.string.theme_category_candidate_style)
        add(
            SettingsSearchResult(
                title = context.getString(R.string.keyboard_theme),
                path = pagePath,
                route = SettingsRoute.ThemeList,
                summary = ThemeManager.activeTheme.name
            )
        )
        listOf(
            R.string.theme_category_theme_mode to SettingsRoute.ThemeMode,
            R.string.theme_category_key_style to SettingsRoute.ThemeKeyStyle,
            R.string.theme_category_candidate_style to SettingsRoute.ThemeCandidateStyle,
            R.string.theme_category_toolbar_style to SettingsRoute.ThemeToolbarStyle,
            R.string.theme_category_animation to SettingsRoute.ThemeAnimation,
            R.string.theme_category_other_style to SettingsRoute.ThemeOtherStyle
        ).forEach { (title, route) ->
            add(SettingsSearchResult(context.getString(title), pagePath, route))
        }
        add(
            SettingsSearchResult(
                title = context.getString(R.string.edit_buttons),
                path = pagePath,
                route = SettingsRoute.Theme,
                preferenceKey = ThemeAppearanceFragment.BUTTON_STYLE_ENTRY_KEY,
                summary = context.getString(R.string.edit_buttons_summary)
            )
        )
        add(
            SettingsSearchResult(
                title = context.getString(R.string.edit_fontset),
                path = pagePath,
                route = SettingsRoute.Theme,
                summary = context.getString(R.string.edit_fontset_summary)
            )
        )
        addThemeSection(
            context,
            ThemeManager.prefs,
            SettingsRoute.ThemeMode,
            pagePath,
            R.string.theme_category_theme_mode,
            ThemeSettingsSupport.themeModeKeys
        )
        addThemeSection(
            context,
            ThemeManager.prefs,
            SettingsRoute.ThemeKeyStyle,
            pagePath,
            R.string.theme_category_key_style,
            ThemeSettingsSupport.keyStyleThemeKeys
        )
        addThemeSection(
            context,
            prefs.keyboard,
            SettingsRoute.ThemeKeyStyle,
            pagePath,
            R.string.theme_category_key_style,
            ThemeSettingsSupport.keyStyleKeyboardKeys
        )
        addThemeSection(
            context,
            ThemeManager.prefs,
            SettingsRoute.ThemeToolbarStyle,
            pagePath,
            R.string.theme_category_toolbar_style,
            ThemeSettingsSupport.toolbarStyleThemeKeys
        )
        addThemeSection(
            context,
            prefs.keyboard,
            SettingsRoute.ThemeToolbarStyle,
            pagePath,
            R.string.theme_category_toolbar_style,
            ThemeSettingsSupport.toolbarStyleKeyboardKeys
        )
        addThemeSection(
            context,
            prefs.candidates,
            SettingsRoute.ThemeCandidateStyle,
            candidatePath,
            null,
            listOf(AppPrefs.CANDIDATE_DISPLAY_MODE_KEY)
        )
        when (prefs.candidates.mode.getValue()) {
            FloatingCandidatesMode.InputDevice -> {
                addThemeSection(
                    context,
                    prefs.keyboard,
                    SettingsRoute.ThemeCandidateStyle,
                    candidatePath,
                    R.string.candidate_display_mode_horizontal,
                    ThemeSettingsSupport.candidateHorizontalKeysBeforeAppearance
                )
                addThemeSection(
                    context,
                    prefs.candidates,
                    SettingsRoute.ThemeCandidateStyle,
                    candidatePath,
                    R.string.candidate_display_mode_horizontal,
                    ThemeSettingsSupport.candidateHorizontalAppearanceKeys
                )
                addThemeSection(
                    context,
                    prefs.keyboard,
                    SettingsRoute.ThemeCandidateStyle,
                    candidatePath,
                    R.string.candidate_display_mode_horizontal,
                    ThemeSettingsSupport.candidateHorizontalKeysAfterAppearance
                )
            }
            FloatingCandidatesMode.Always -> {
                addThemeSection(
                    context,
                    prefs.candidates,
                    SettingsRoute.ThemeCandidateStyle,
                    candidatePath,
                    R.string.candidates_window,
                    ThemeSettingsSupport.candidateWindowKeys
                )
                addThemeSection(
                    context,
                    prefs.candidates,
                    SettingsRoute.ThemeCandidateStyle,
                    candidatePath,
                    R.string.candidates_items,
                    ThemeSettingsSupport.candidateItemKeys
                )
            }
            FloatingCandidatesMode.SystemDefault,
            FloatingCandidatesMode.Disabled -> Unit
        }
        addThemeSection(
            context,
            ThemeManager.prefs,
            SettingsRoute.ThemeAnimation,
            pagePath,
            R.string.theme_category_animation,
            ThemeSettingsSupport.animationThemeKeys
        )
        addThemeSection(
            context,
            prefs.advanced,
            SettingsRoute.ThemeAnimation,
            pagePath,
            R.string.theme_category_animation,
            ThemeSettingsSupport.animationAdvancedKeys
        )
        addThemeSection(
            context,
            ThemeManager.prefs,
            SettingsRoute.ThemeOtherStyle,
            pagePath,
            R.string.theme_category_other_style,
            ThemeSettingsSupport.otherStyleThemeKeys
        )
    }

    private fun MutableList<SettingsSearchResult>.addThemeSection(
        context: Context,
        provider: ManagedPreferenceProvider,
        route: SettingsRoute,
        basePath: List<String>,
        @StringRes sectionTitle: Int?,
        keys: List<String>
    ) {
        val uiByKey = provider.managedPreferencesUi.associateBy { it.key }
        val path = sectionTitle?.let { basePath + context.getString(it) } ?: basePath
        keys.mapNotNull(uiByKey::get).forEach { ui ->
            add(ui.toSearchResult(context, route, path))
        }
    }

    private fun keyboardItems(
        context: Context,
        keyboardPrefs: AppPrefs.Keyboard
    ): List<SettingsSearchResult> = buildList {
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_settings_basic_behavior,
            SettingsRoute.VirtualKeyboardBasic,
            KeyboardSettingsSupport.basicBehaviorKeys
        )
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_category_voice,
            SettingsRoute.VirtualKeyboardVoice,
            listOf("show_voice_input_button", "preferred_voice_input")
        )
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_settings_touch_and_sound,
            SettingsRoute.VirtualKeyboardTouchAndSound,
            KeyboardSettingsSupport.touchAndSoundKeys
        )
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_settings_key_and_gesture,
            SettingsRoute.VirtualKeyboardKeyAndGesture,
            KeyboardSettingsSupport.keyAndGestureKeys
        )
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_settings_layout_and_split,
            SettingsRoute.VirtualKeyboardLayoutAndSplit,
            KeyboardSettingsSupport.layoutKeys
        )
        add(
            SettingsSearchResult(
                context.getString(R.string.split_keyboard_calibration_title),
                listOf(
                    context.getString(R.string.virtual_keyboard),
                    context.getString(R.string.keyboard_settings_layout_and_split)
                ),
                SettingsRoute.VirtualKeyboardLayoutAndSplit,
                "split_keyboard_calibration",
                context.getString(R.string.split_keyboard_calibration_summary)
            )
        )
        val floatingPagePath = listOf(
            context.getString(R.string.virtual_keyboard),
            context.getString(R.string.keyboard_settings_layout_and_split)
        )
        listOf(
            Triple(
                R.string.floating_keyboard_enabled,
                R.string.floating_keyboard_enabled_summary,
                AppPrefs.getInstance().internal.floatingModeEnabled.key
            ),
            Triple(
                R.string.auto_floating_landscape,
                R.string.auto_floating_landscape_summary,
                AppPrefs.getInstance().internal.autoFloatingLandscape.key
            ),
            Triple(
                R.string.floating_move_handle_auto_hide,
                R.string.floating_move_handle_auto_hide_summary,
                AppPrefs.getInstance().internal.autoHideFloatingMoveHandle.key
            )
        ).forEach { (title, summary, key) ->
            add(
                SettingsSearchResult(
                    context.getString(title),
                    floatingPagePath,
                    SettingsRoute.VirtualKeyboardLayoutAndSplit,
                    key,
                    context.getString(summary)
                )
            )
        }
        addAdvancedKeyboardItem(
            context,
            R.string.edit_swipe_symbol_mapping,
            R.string.edit_swipe_symbol_mapping_summary,
            KeyboardSettingsHomeFragment.SWIPE_SYMBOL_MAPPING_ENTRY_KEY
        )
        addAdvancedKeyboardItem(
            context,
            R.string.edit_popup_preset,
            R.string.edit_popup_preset_summary,
            KeyboardSettingsHomeFragment.POPUP_PRESET_ENTRY_KEY
        )
        addAdvancedKeyboardItem(
            context,
            R.string.edit_text_keyboard_layout,
            R.string.edit_text_keyboard_layout_summary,
            KeyboardSettingsHomeFragment.TEXT_LAYOUT_EDIT_ENTRY_KEY
        )
        addAdvancedKeyboardItem(
            context,
            R.string.text_keyboard_layout_manage_title,
            null,
            KeyboardSettingsHomeFragment.TEXT_LAYOUT_MANAGE_ENTRY_KEY
        )
    }

    private fun MutableList<SettingsSearchResult>.addKeyboardSection(
        context: Context,
        keyboardPrefs: AppPrefs.Keyboard,
        @StringRes sectionTitle: Int,
        route: SettingsRoute,
        keys: List<String>
    ) {
        val uiByKey = keyboardPrefs.managedPreferencesUi.associateBy { it.key }
        val path = listOf(context.getString(R.string.virtual_keyboard), context.getString(sectionTitle))
        keys.mapNotNull(uiByKey::get).forEach { ui ->
            add(ui.toSearchResult(context, route, path))
        }
    }

    private fun MutableList<SettingsSearchResult>.addAdvancedKeyboardItem(
        context: Context,
        @StringRes title: Int,
        @StringRes summary: Int?,
        preferenceKey: String
    ) {
        add(
            SettingsSearchResult(
                context.getString(title),
                listOf(
                    context.getString(R.string.virtual_keyboard),
                    context.getString(R.string.keyboard_settings_advanced_customization)
                ),
                SettingsRoute.VirtualKeyboard,
                preferenceKey,
                summary?.let(context::getString)
            )
        )
    }

    private fun managedItems(
        context: Context,
        provider: ManagedPreferenceProvider,
        route: SettingsRoute,
        @StringRes pageTitle: Int
    ): List<SettingsSearchResult> {
        val path = listOf(context.getString(pageTitle))
        return provider.managedPreferencesUi.map { it.toSearchResult(context, route, path) }
    }

    /**
     * The three catalog pickers are plain preferences created inside
     * [SymbolSettingsFragment] rather than managed preferences, so they need
     * their own search entries to be reachable from settings search.
     */
    private fun symbolCatalogItems(context: Context): List<SettingsSearchResult> {
        val pageTitle = context.getString(R.string.emoji_and_symbols)
        val path = listOf(pageTitle)
        return SymbolCatalogType.entries.map { type ->
            SettingsSearchResult(
                title = context.getString(
                    when (type) {
                        SymbolCatalogType.Symbols -> R.string.symbol_catalog_symbols_title
                        SymbolCatalogType.Emoji -> R.string.symbol_catalog_emoji_title
                        SymbolCatalogType.Kaomoji -> R.string.symbol_catalog_kaomoji_title
                    }
                ),
                path = path,
                route = SettingsRoute.Symbol,
                preferenceKey = SymbolSettingsFragment.catalogPreferenceKey(type)
            )
        }
    }

    private fun org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceUi<*>.toSearchResult(
        context: Context,
        route: SettingsRoute,
        path: List<String>
    ): SettingsSearchResult {
        val preference = createUi(context)
        return SettingsSearchResult(
            title = preference.titleText(),
            path = path,
            route = route,
            preferenceKey = key,
            summary = preference.summary?.toString()
        )
    }

    private fun Preference.titleText(): String = title?.toString().orEmpty()

    private fun advancedExtraItems(context: Context): List<SettingsSearchResult> =
        listOf(
            SettingsSearchResult(
                context.getString(R.string.browse_user_data_dir),
                listOf(context.getString(R.string.advanced)),
                SettingsRoute.Advanced
            ),
            SettingsSearchResult(
                context.getString(R.string.export_user_data),
                listOf(context.getString(R.string.advanced)),
                SettingsRoute.Advanced
            ),
            SettingsSearchResult(
                context.getString(R.string.import_user_data),
                listOf(context.getString(R.string.advanced)),
                SettingsRoute.Advanced
            )
        )

    private fun toolbarMenuItems(context: Context): List<SettingsSearchResult> {
        val appLabel = AppUtil.appLabel(context)
        return toolbarMenuSearchSpecs.map { spec ->
            SettingsSearchResult(
                title = context.getString(spec.title),
                path = buildList {
                    add(appLabel)
                    spec.parent?.let { add(context.getString(it)) }
                    spec.section?.let { add(context.getString(it)) }
                },
                route = spec.route,
                summary = spec.summary?.let(context::getString),
                keywords = spec.keywords,
                externalUri = spec.externalUri
            )
        }
    }

    private fun llmItems(context: Context): List<SettingsSearchResult> {
        val llm = context.getString(R.string.llm_settings_title)
        val advanced = context.getString(R.string.llm_advanced)
        return listOf(
            SettingsSearchResult(context.getString(R.string.llm_enable), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_ENABLED, context.getString(R.string.llm_enable_summary)),
            SettingsSearchResult(context.getString(R.string.llm_auto_predict), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_AUTO_PREDICT_ENABLED, context.getString(R.string.llm_auto_predict_summary)),
            SettingsSearchResult(context.getString(R.string.llm_provider), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_PROVIDER),
            SettingsSearchResult(context.getString(R.string.llm_api_url), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_BASE_URL),
            SettingsSearchResult(context.getString(R.string.llm_api_key), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_API_KEY),
            SettingsSearchResult(context.getString(R.string.llm_model_status), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_MODEL),
            SettingsSearchResult(context.getString(R.string.llm_max_prediction_candidates), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_MAX_PREDICTION_CANDIDATES),
            SettingsSearchResult(context.getString(R.string.llm_prediction_display_mode), listOf(llm), SettingsRoute.Llm, LlmPrefs.KEY_PREDICTION_DISPLAY_MODE),
            SettingsSearchResult(advanced, listOf(llm), SettingsRoute.Llm, "llm_advanced_entry", context.getString(R.string.llm_advanced_summary)),
            SettingsSearchResult(context.getString(R.string.llm_local_model_import), listOf(llm), SettingsRoute.Llm, "llm_local_model_import_entry", context.getString(R.string.llm_local_model_import_merged_summary)),
            SettingsSearchResult(context.getString(R.string.llm_model_test), listOf(llm), SettingsRoute.Llm, "llm_model_test", context.getString(R.string.llm_model_test_summary)),
            SettingsSearchResult(context.getString(R.string.llm_persona_style), listOf(llm, advanced), SettingsRoute.LlmAdvanced, "llm_persona_list"),
            SettingsSearchResult(context.getString(R.string.llm_custom_persona), listOf(llm, advanced), SettingsRoute.LlmAdvanced, LlmPrefs.KEY_CUSTOM_PERSONA, context.getString(R.string.llm_custom_persona_summary)),
            SettingsSearchResult(context.getString(R.string.llm_sample_count), listOf(llm, advanced), SettingsRoute.LlmAdvanced, LlmPrefs.KEY_SAMPLE_COUNT),
            SettingsSearchResult(context.getString(R.string.llm_max_context_chars), listOf(llm, advanced), SettingsRoute.LlmAdvanced, LlmPrefs.KEY_MAX_CONTEXT_CHARS),
            SettingsSearchResult(context.getString(R.string.llm_max_output_tokens), listOf(llm, advanced), SettingsRoute.LlmAdvanced, LlmPrefs.KEY_MAX_OUTPUT_TOKENS)
        )
    }

    private fun rawConfigItems(
        context: Context,
        pageTitle: String,
        route: SettingsRoute,
        raw: RawConfig,
        tab: Int? = null,
        extraPath: List<String> = emptyList(),
        layout: ConfigLayout? = null
    ): List<SettingsSearchResult> {
        val desc = raw.findByName("desc") ?: return emptyList()
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { return emptyList() }
        val path = (listOf(pageTitle) + extraPath + listOf(topLevelDesc.name)).distinct()
        val groupedLayout = layout?.takeIf { it.sectionNavigation }
        return topLevelDesc.values.flatMap { descriptor ->
            val sectionIndex = groupedLayout?.sections?.indexOfFirst {
                descriptor.name in it.options
            }?.takeIf { it >= 0 } ?: groupedLayout?.sections?.size
            val sectionTitle = sectionIndex?.let { index ->
                groupedLayout?.sectionTitle(context, index)
                    ?: groupedLayout?.fallbackSectionTitle?.let(context::getString)
            }
            val destination = if (sectionIndex != null && sectionTitle != null) {
                if (groupedLayout != null && AddonConfigLayouts.isFuzzySection(groupedLayout, sectionIndex)) {
                    SettingsRoute.FuzzyConfig(
                        pageTitle = path.first(),
                        uniqueName = when (route) {
                            is SettingsRoute.AddonConfig -> route.uniqueName
                            is SettingsRoute.InputMethodConfig -> route.uniqueName
                            is SettingsRoute.CustomShuangpin -> CustomShuangpinSchemeStore.currentUniqueName()
                            else -> ""
                        },
                        isAddon = route is SettingsRoute.AddonConfig,
                        sectionTitle = sectionTitle
                    )
                } else if (
                    groupedLayout != null &&
                    AddonConfigLayouts.isPinyinTextSection(groupedLayout, sectionIndex)
                ) {
                    SettingsRoute.PinyinTextConfig(sectionTitle)
                } else SettingsRoute.ConfigSection(
                    pageTitle = path.first(),
                    uniqueName = when (route) {
                        is SettingsRoute.AddonConfig -> route.uniqueName
                        is SettingsRoute.InputMethodConfig -> route.uniqueName
                        is SettingsRoute.CustomShuangpin ->
                            CustomShuangpinSchemeStore.currentUniqueName()
                        else -> ""
                    },
                    isAddon = route is SettingsRoute.AddonConfig,
                    sectionIndex = sectionIndex,
                    sectionTitle = sectionTitle
                )
            } else {
                route
            }
            val itemPath = if (sectionTitle == null) {
                path
            } else {
                val sectionPath = path + sectionTitle
                if (destination is SettingsRoute.PinyinTextConfig) {
                    sectionPath + context.getString(
                        if (descriptor.name == "Punctuation") {
                            R.string.pinyin_config_punctuation
                        } else {
                            R.string.pinyin_config_script_conversion
                        }
                    )
                } else {
                    sectionPath
                }
            }
            descriptorItems(
                context,
                destination,
                itemPath,
                descriptor,
                tab,
                groupedLayout?.children?.get(descriptor.name) ?: layout
            )
        }
    }

    private fun descriptorItems(
        context: Context,
        route: SettingsRoute,
        path: List<String>,
        descriptor: ConfigDescriptor<*, *>,
        tab: Int? = null,
        layout: ConfigLayout? = null
    ): List<SettingsSearchResult> {
        if (hideKeyConfig && ConfigType.pretty(descriptor.ty).contains("Key")) {
            return emptyList()
        }
        return if (descriptor is ConfigCustom) {
            val section = layout?.titleOverride(context, descriptor.name)
                ?: descriptor.description ?: descriptor.name
            val childLayout = layout?.children?.get(descriptor.name)
            descriptor.customTypeDef?.values.orEmpty().flatMap {
                val fuzzyTab = if (descriptor.name == "Fuzzy") {
                    if (it.name in AddonConfigLayouts.FUZZY_MAPPING_OPTIONS) 1 else 0
                } else tab
                descriptorItems(context, route, path + section, it, fuzzyTab, childLayout)
            }
        } else {
            val descriptorTab = when (descriptor.name) {
                "Punctuation", "Chttrans" ->
                    PinyinTextConfigFragment.tabForOption(descriptor.name)
                else -> tab
            }
            listOf(
                SettingsSearchResult(
                    title = layout?.titleOverride(context, descriptor.name)
                        ?: descriptorTitle(context, descriptor),
                    path = path,
                    route = route,
                    preferenceKey = descriptor.name,
                    summary = descriptor.tooltip,
                    keywords = listOf(descriptor.name, ConfigType.pretty(descriptor.ty)),
                    tab = descriptorTab
                )
            )
        }
    }

    private fun descriptorTitle(
        context: Context,
        descriptor: ConfigDescriptor<*, *>
    ): String {
        val baseTitle = descriptor.description ?: descriptor.name
        if (descriptor is ConfigExternal &&
            descriptor.knownType == ConfigExternal.ETy.MultiSelect
        ) {
            val uri = descriptor.uri
            val parsed = uri?.let { Uri.parse(it) }
            val segments = parsed?.pathSegments
            if (segments != null && segments.size >= 3 && segments.first() == "addon") {
                val addon = segments[1]
                val path = segments.drop(2).joinToString("/")
                return AppUtil.normalizeAddonMultiSelectTitle(context, baseTitle, addon, path)
            }
        }
        return baseTitle
    }
}
