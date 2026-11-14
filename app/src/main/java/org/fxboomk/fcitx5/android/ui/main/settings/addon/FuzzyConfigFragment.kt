/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.utils.lazyRoute

class FuzzyConfigFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val args by lazyRoute<SettingsRoute.FuzzyConfig>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_fuzzy_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = view.findViewById<TabLayout>(R.id.fuzzy_config_tabs)
        val pager = view.findViewById<ViewPager2>(R.id.fuzzy_config_pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int) = FuzzyConfigTabFragment.newInstance(
                args.uniqueName,
                args.isAddon,
                args.sectionTitle,
                position == TAB_MAPPINGS
            )
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(if (position == TAB_MAPPINGS) R.string.pinyin_config_fuzzy_mappings
            else R.string.pinyin_config_fuzzy_settings)
        }.attach()
        val pendingTab = viewModel.peekPendingPreferenceTab()
        if (pendingTab != null && pendingTab in 0..1 && pager.currentItem != pendingTab) {
            pager.setCurrentItem(pendingTab, false)
            pager.doOnLayout {
                if (pager.currentItem != pendingTab) pager.setCurrentItem(pendingTab, false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(args.sectionTitle)
    }

    private companion object {
        const val TAB_MAPPINGS = 1
    }
}

class FuzzyConfigTabFragment : FcitxPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val compactLayouts by lazy {
        AppPrefs.getInstance().internal.compactShuangpinInputMethods
    }
    private val layoutChangeListener = ManagedPreference.OnChangeListener<Set<String>> { _, _ ->
        val currentView = view ?: return@OnChangeListener
        currentView.post {
            if (view === currentView) refreshCorrectionPreference()
        }
    }
    private var imeChangeJob: Job? = null
    private val uniqueName get() = requireArguments().getString(ARG_UNIQUE_NAME).orEmpty()
    private val isAddon get() = requireArguments().getBoolean(ARG_IS_ADDON)
    private val sectionTitle get() = requireArguments().getString(ARG_SECTION_TITLE).orEmpty()
    private val mappings get() = requireArguments().getBoolean(ARG_MAPPINGS)
    override val shouldUpdateToolbarTitle = false
    override val preferenceTabIndex: Int get() = if (mappings) 1 else 0

    override fun getPageTitle() = sectionTitle

    override fun onPreferenceScreenReady() = refreshCorrectionPreference()

    override fun onStart() {
        super.onStart()
        if (mappings) return
        compactLayouts.registerOnChangeListener(layoutChangeListener)
        if (isAddon && uniqueName == "pinyin") {
            imeChangeJob = viewLifecycleOwner.lifecycleScope.launch {
                viewModel.fcitx.runImmediately { eventFlow }.collect { event ->
                    if (event is FcitxEvent.IMChangeEvent) refreshCorrectionPreference()
                }
            }
        }
        refreshCorrectionPreference()
    }

    override fun onResume() {
        super.onResume()
        refreshCorrectionPreference()
    }

    override fun onStop() {
        if (!mappings) compactLayouts.unregisterOnChangeListener(layoutChangeListener)
        imeChangeJob?.cancel()
        imeChangeJob = null
        super.onStop()
    }

    private fun refreshCorrectionPreference() {
        if (mappings || preferenceScreen?.findPreference<Preference>("Correction") == null) return
        val layoutIme = if (isAddon && uniqueName == "pinyin") {
            viewModel.fcitx.runImmediately {
                inputMethodEntryCached.takeIf { it.addon == "pinyin" }?.uniqueName
            }
        } else uniqueName
        updateFuzzyCorrectionPreference(preferenceScreen, layoutIme, compactLayouts.getValue())
    }

    override fun getConfigLayout(context: android.content.Context): ConfigLayout? =
        AddonConfigLayouts.getFuzzyTab(context, uniqueName, mappings)

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        if (isAddon) fcitx.getAddonConfig(uniqueName) else fcitx.getImConfig(uniqueName)

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        val latest = if (isAddon) {
            fcitx.getAddonConfig(uniqueName)
        } else {
            fcitx.getImConfig(uniqueName)
        }
        val mergedConfig = mergeFuzzyConfigForSave(
            latest,
            newConfig,
            AddonConfigLayouts.fuzzyOptions(mappings).toSet()
        )
        if (isAddon) {
            fcitx.setAddonConfig(uniqueName, mergedConfig)
        } else {
            fcitx.setImConfig(uniqueName, mergedConfig)
        }
    }

    companion object {
        private const val ARG_UNIQUE_NAME = "unique_name"
        private const val ARG_IS_ADDON = "is_addon"
        private const val ARG_SECTION_TITLE = "section_title"
        private const val ARG_MAPPINGS = "mappings"

        fun newInstance(
            uniqueName: String,
            isAddon: Boolean,
            sectionTitle: String,
            mappings: Boolean
        ) = FuzzyConfigTabFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_UNIQUE_NAME, uniqueName)
                putBoolean(ARG_IS_ADDON, isAddon)
                putString(ARG_SECTION_TITLE, sectionTitle)
                putBoolean(ARG_MAPPINGS, mappings)
            }
        }
    }
}

/** Disable interaction for compact layouts while retaining the saved enum value. */
internal fun updateFuzzyCorrectionPreference(
    screen: PreferenceScreen?,
    uniqueName: String?,
    compactInputMethods: Set<String>
) {
    screen?.findPreference<Preference>("Correction")?.isEnabled =
        uniqueName == null || ShuangpinKeyboardLayouts.read(uniqueName, compactInputMethods) !=
            ShuangpinKeyboardLayout.Compact18
}

/**
 * Build the config payload for saving one fuzzy tab.
 *
 * The config returned by `getImConfig` / `getAddonConfig` wraps the option tree in a
 * top-level `cfg` item next to `desc`, while `setImConfig` / `setAddonConfig` expect the
 * option tree itself. Unwrap it before merging, otherwise fcitx sees no fuzzy options at all.
 */
internal fun mergeFuzzyConfigForSave(
    latest: RawConfig,
    editedConfig: RawConfig,
    optionNames: Set<String>
): RawConfig =
    mergeFuzzyOptions(latest.findByName("cfg") ?: latest, editedConfig, optionNames)

/** Merge only the options rendered by one fuzzy tab into the latest config snapshot. */
internal fun mergeFuzzyOptions(
    latestConfig: RawConfig,
    editedConfig: RawConfig,
    optionNames: Set<String>
): RawConfig {
    val latestFuzzy = latestConfig.findByName("Fuzzy") ?: return latestConfig
    val editedFuzzy = editedConfig.findByName("Fuzzy") ?: return latestConfig
    val editedByName = editedFuzzy.subItems.orEmpty().associateBy { it.name }
    val mergedFuzzy = latestFuzzy.copy(
        subItems = latestFuzzy.subItems?.map { option ->
            if (option.name in optionNames) editedByName[option.name] ?: option else option
        }?.toTypedArray()
    )
    return latestConfig.copy(
        subItems = latestConfig.subItems?.map { option ->
            if (option.name == "Fuzzy") mergedFuzzy else option
        }?.toTypedArray()
    )
}
