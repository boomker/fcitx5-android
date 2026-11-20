/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonConfigLayouts
import org.fxboomk.fcitx5.android.utils.lazyRoute

/** Displays a single group from an addon or input-method config. */
class ConfigSectionFragment : FcitxPreferenceFragment() {
    private val args by lazyRoute<SettingsRoute.ConfigSection>()
    private val viewModel: MainViewModel by activityViewModels()
    private val compactLayouts by lazy { AppPrefs.getInstance().internal.compactShuangpinInputMethods }
    private val isPinyinAddon get() = args.isAddon && args.uniqueName == "pinyin"
    private val watchesLayout get() = isPinyinAddon ||
        (!args.isAddon && ShuangpinKeyboardLayouts.supports(args.uniqueName))
    private val layoutChangeListener = ManagedPreference.OnChangeListener<Set<String>> { _, _ ->
        val currentView = view ?: return@OnChangeListener
        currentView.post {
            if (view === currentView) refreshShuangpinInitialFuzzyPreference()
        }
    }
    private var imeChangeJob: Job? = null

    override fun getPageTitle(): String = args.sectionTitle

    override fun onPreferenceScreenReady() = refreshShuangpinInitialFuzzyPreference()

    override fun onStart() {
        super.onStart()
        if (!watchesLayout) return
        compactLayouts.registerOnChangeListener(layoutChangeListener)
        if (isPinyinAddon) {
            imeChangeJob = viewLifecycleOwner.lifecycleScope.launch {
                viewModel.fcitx.runImmediately { eventFlow }.collect { event ->
                    if (event is FcitxEvent.IMChangeEvent) refreshShuangpinInitialFuzzyPreference()
                }
            }
        }
        refreshShuangpinInitialFuzzyPreference()
    }

    override fun onStop() {
        if (watchesLayout) compactLayouts.unregisterOnChangeListener(layoutChangeListener)
        imeChangeJob?.cancel()
        imeChangeJob = null
        super.onStop()
    }

    private fun refreshShuangpinInitialFuzzyPreference() {
        val preference = findPreference<Preference>("ShuangpinInitialFuzzy") ?: return
        val layoutIme = if (isPinyinAddon) {
            viewModel.fcitx.runImmediately {
                inputMethodEntryCached.takeIf { it.addon == "pinyin" }?.uniqueName
            }
        } else args.uniqueName
        preference.isEnabled = layoutIme == null ||
            ShuangpinKeyboardLayouts.read(layoutIme, compactLayouts.getValue()) !=
            ShuangpinKeyboardLayout.Compact18
    }

    override fun getConfigLayout(context: Context): ConfigLayout? =
        if (args.isAddon) {
            AddonConfigLayouts.get(context, args.uniqueName)
        } else {
            AddonConfigLayouts.getForInputMethod(context, args.uniqueName)
        }

    override fun getPreeditStyleScope(): PreeditStyleScope? =
        if (args.isAddon) {
            PreeditStyleScope.addon(args.uniqueName)
        } else {
            AddonConfigLayouts.engineAddonForInputMethod(args.uniqueName)
                ?.let { PreeditStyleScope.forInputMethod(args.uniqueName, it) }
        }

    override fun getConfigSection(): Int = args.sectionIndex

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        if (args.isAddon) fcitx.getAddonConfig(args.uniqueName)
        else fcitx.getImConfig(args.uniqueName)

    override suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig) {
        if (args.isAddon) fcitx.setAddonConfig(args.uniqueName, newConfig)
        else fcitx.setImConfig(args.uniqueName, newConfig)
    }
}
