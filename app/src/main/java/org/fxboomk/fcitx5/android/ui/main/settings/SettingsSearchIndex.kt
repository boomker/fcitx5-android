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
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.predict.LlmPrefs
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.KeyboardSettingsSupport
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
    val externalUri: String? = null
) {
    private val searchableText: String by lazy {
        (listOf(title, preferenceKey.orEmpty(), summary.orEmpty()) + path + keywords)
            .joinToString(" ")
            .lowercase(Locale.getDefault())
    }

    fun matches(query: String): Boolean {
        val terms = query.trim()
            .lowercase(Locale.getDefault())
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return terms.isNotEmpty() && terms.all(searchableText::contains)
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
        ToolbarMenuSearchSpec(R.string.faq, externalUri = Const.faqUrl),
        ToolbarMenuSearchSpec(R.string.developer, SettingsRoute.Developer),
        ToolbarMenuSearchSpec(R.string.about, SettingsRoute.About),
        ToolbarMenuSearchSpec(R.string.real_time_logs, SettingsRoute.Developer, R.string.developer),
        ToolbarMenuSearchSpec(R.string.verbose_log, SettingsRoute.Developer, R.string.developer),
        ToolbarMenuSearchSpec(
            R.string.editor_info_inspector,
            SettingsRoute.Developer,
            R.string.developer
        ),
        ToolbarMenuSearchSpec(
            R.string.restart_fcitx_instance,
            SettingsRoute.Developer,
            R.string.developer,
            summary = R.string.restart_fcitx_instance_confirm
        ),
        ToolbarMenuSearchSpec(
            R.string.delete_and_sync_data,
            SettingsRoute.Developer,
            R.string.developer,
            summary = R.string.delete_and_sync_data_message
        ),
        ToolbarMenuSearchSpec(R.string.clear_clb_db, SettingsRoute.Developer, R.string.developer),
        ToolbarMenuSearchSpec(R.string.capture_heap_dump, SettingsRoute.Developer, R.string.developer),
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
            R.string.check_for_updates,
            SettingsRoute.About,
            R.string.about,
            R.string.version
        ),
        ToolbarMenuSearchSpec(
            R.string.install_update,
            SettingsRoute.About,
            R.string.about,
            R.string.version
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
            addPage(context, R.string.candidates_window, SettingsRoute.CandidatesWindow)
            addPage(context, R.string.llm_settings_title, SettingsRoute.Llm)
            addPage(context, R.string.clipboard, SettingsRoute.Clipboard)
            addPage(context, R.string.plugins, SettingsRoute.Plugin)
            addPage(context, R.string.advanced, SettingsRoute.Advanced)

            addAll(keyboardItems(context, prefs.keyboard))
            addAll(managedItems(context, ThemeManager.prefs, SettingsRoute.Theme, R.string.theme_appearance))
            addAll(managedItems(context, prefs.candidates, SettingsRoute.CandidatesWindow, R.string.candidates_window))
            addAll(managedItems(context, prefs.clipboard, SettingsRoute.Clipboard, R.string.clipboard))
            addAll(managedItems(context, prefs.advanced, SettingsRoute.Advanced, R.string.advanced))
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
                addAll(
                    rawConfigItems(
                        context,
                        ime.displayName,
                        SettingsRoute.InputMethodConfig(ime.displayName, ime.uniqueName),
                        fcitx.getImConfig(ime.uniqueName)
                    )
                )
            }
            fcitx.addons()
                .filter { it.enabled && it.isConfigurable && it.uniqueName != "pinyinhelper" }
                .forEach { addon ->
                    val raw = fcitx.getAddonConfig(addon.uniqueName)
                    if (PreferenceScreenFactory.hasVisibleOptions(raw)) {
                        addAll(
                            rawConfigItems(
                                context,
                                addon.displayName,
                                SettingsRoute.AddonConfig(addon.displayName, addon.uniqueName),
                                raw
                            )
                        )
                    }
                }
        }

    private fun MutableList<SettingsSearchResult>.addPage(
        context: Context,
        @StringRes title: Int,
        route: SettingsRoute
    ) {
        val label = context.getString(title)
        add(SettingsSearchResult(label, listOf(label), route))
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
            R.string.keyboard_settings_touch_and_sound,
            SettingsRoute.VirtualKeyboardTouchAndSound,
            KeyboardSettingsSupport.touchAndSoundKeys
        )
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_settings_toolbar_and_voice,
            SettingsRoute.VirtualKeyboardToolbarAndInput,
            KeyboardSettingsSupport.toolbarAndInputKeys
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
        addKeyboardSection(
            context,
            keyboardPrefs,
            R.string.keyboard_settings_candidates,
            SettingsRoute.VirtualKeyboardCandidates,
            KeyboardSettingsSupport.candidatesKeys
        )
        addAdvancedKeyboardItem(context, R.string.edit_fontset, R.string.edit_fontset_summary)
        addAdvancedKeyboardItem(context, R.string.edit_popup_preset, R.string.edit_popup_preset_summary)
        addAdvancedKeyboardItem(context, R.string.edit_text_keyboard_layout, R.string.edit_text_keyboard_layout_summary)
        addAdvancedKeyboardItem(
            context,
            R.string.text_keyboard_layout_file_select_title
        )
        addAdvancedKeyboardItem(context, R.string.edit_buttons, R.string.edit_buttons_summary)
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
        @StringRes summary: Int? = null
    ) {
        add(
            SettingsSearchResult(
                context.getString(title),
                listOf(
                    context.getString(R.string.virtual_keyboard),
                    context.getString(R.string.keyboard_settings_advanced_customization)
                ),
                SettingsRoute.VirtualKeyboardAdvancedCustomization,
                summary = summary?.let(context::getString)
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
            SettingsSearchResult(context.getString(R.string.llm_space_commit_prediction), listOf(llm, advanced), SettingsRoute.LlmAdvanced, LlmPrefs.KEY_SPACE_COMMIT_PREDICTION, context.getString(R.string.llm_space_commit_prediction_summary)),
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
        raw: RawConfig
    ): List<SettingsSearchResult> {
        val desc = raw.findByName("desc") ?: return emptyList()
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { return emptyList() }
        val path = listOf(pageTitle, topLevelDesc.name).distinct()
        return topLevelDesc.values.flatMap { descriptorItems(context, route, path, it) }
    }

    private fun descriptorItems(
        context: Context,
        route: SettingsRoute,
        path: List<String>,
        descriptor: ConfigDescriptor<*, *>
    ): List<SettingsSearchResult> {
        if (hideKeyConfig && ConfigType.pretty(descriptor.ty).contains("Key")) {
            return emptyList()
        }
        return if (descriptor is ConfigCustom) {
            val section = descriptor.description ?: descriptor.name
            descriptor.customTypeDef?.values.orEmpty().flatMap {
                descriptorItems(context, route, path + section, it)
            }
        } else {
            listOf(
                SettingsSearchResult(
                    title = descriptorTitle(context, descriptor),
                    path = path,
                    route = route,
                    preferenceKey = descriptor.name,
                    summary = descriptor.tooltip,
                    keywords = listOf(descriptor.name, ConfigType.pretty(descriptor.ty))
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
