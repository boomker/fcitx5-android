/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsSearchIndex
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsSearchPreference
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsSearchResult
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.addCategory
import org.fxboomk.fcitx5.android.utils.addPreference
import org.fxboomk.fcitx5.android.utils.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var searchPreference: SettingsSearchPreference
    private var searchQuery = ""
    private var searchItems: List<SettingsSearchResult> = emptyList()
    private var loadingFcitxSearchItems = false

    override fun onStart() {
        super.onStart()
        viewModel.enableAboutButton()
    }

    override fun onStop() {
        viewModel.disableAboutButton()
        super.onStop()
    }

    private fun PreferenceCategory.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: SettingsRoute
    ) {
        addPreference(title, icon = icon) {
            navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        searchItems = SettingsSearchIndex.androidItems(context)
        searchPreference = SettingsSearchPreference(context).apply {
            query = searchQuery
            onQueryChanged = onQueryChanged@ { query ->
                if (searchQuery == query) return@onQueryChanged
                searchQuery = query
                this.query = query
                renderContent()
            }
        }
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            addPreference(searchPreference)
        }
        renderContent()
        loadFcitxSearchItems()
    }

    private fun loadFcitxSearchItems() {
        if (loadingFcitxSearchItems) return
        loadingFcitxSearchItems = true
        renderContent()
        val context = requireContext()
        lifecycleScope.launch {
            val result = runCatching {
                viewModel.fcitx.runOnReady {
                    SettingsSearchIndex.fcitxItems(context, this)
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { items ->
                searchItems = (searchItems + items).distinctBy {
                    listOf(it.route.toString(), it.preferenceKey.orEmpty(), it.title)
                        .joinToString("|")
                }
            }
            loadingFcitxSearchItems = false
            renderContent()
        }
    }

    private fun renderContent() {
        val screen = preferenceScreen ?: return
        searchPreference.query = searchQuery
        screen.removeContentPreferences()
        if (searchQuery.isBlank()) {
            screen.addHomePreferences()
        } else {
            screen.addSearchResults()
        }
    }

    private fun PreferenceScreen.removeContentPreferences() {
        while (preferenceCount > 1) {
            removePreference(getPreference(1))
        }
    }

    private fun PreferenceScreen.addHomePreferences() {
        apply {
            addCategory("Fcitx") {
                addDestinationPreference(
                    R.string.global_options,
                    R.drawable.ic_baseline_tune_24,
                    SettingsRoute.GlobalConfig
                )
                addDestinationPreference(
                    R.string.addons,
                    R.drawable.ic_baseline_extension_24,
                    SettingsRoute.AddonList
                )
                addDestinationPreference(
                    R.string.input_methods,
                    R.drawable.ic_baseline_language_24,
                    SettingsRoute.InputMethodList
                )
            }
            addCategory("Android") {
                addDestinationPreference(
                    R.string.theme_appearance,
                    R.drawable.ic_baseline_palette_24,
                    SettingsRoute.Theme
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    SettingsRoute.VirtualKeyboard
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    SettingsRoute.CandidatesWindow
                )
                addDestinationPreference(
                    R.string.llm_settings_title,
                    R.drawable.ic_baseline_auto_awesome_24,
                    SettingsRoute.Llm
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard,
                    SettingsRoute.Clipboard
                )
                addDestinationPreference(
                    R.string.plugins,
                    R.drawable.ic_baseline_android_24,
                    SettingsRoute.Plugin
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    SettingsRoute.Advanced
                )
            }
        }
    }

    private fun PreferenceScreen.addSearchResults() {
        val results = searchItems
            .filter { it.matches(searchQuery) }
            .sortedWith(compareBy({ it.path.joinToString("/") }, { it.title }))
        addCategory(R.string.settings_search_results) {
            results.forEach { result ->
                addPreference(
                    title = result.title,
                    summary = result.path.joinToString(" / ")
                ) {
                    result.preferenceKey?.let(viewModel::requestPreferenceScroll)
                    navigateWithAnim(result.route)
                }
            }
            if (results.isEmpty()) {
                addPreference(
                    if (loadingFcitxSearchItems) {
                        R.string.settings_search_loading_fcitx
                    } else {
                        R.string.settings_search_no_results
                    }
                )
            } else if (loadingFcitxSearchItems) {
                addPreference(R.string.settings_search_loading_fcitx)
            }
        }
    }
}
