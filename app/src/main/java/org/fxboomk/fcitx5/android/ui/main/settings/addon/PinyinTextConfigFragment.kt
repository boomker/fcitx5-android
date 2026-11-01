/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import org.fxboomk.fcitx5.android.ui.main.settings.FcitxPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.settings.PunctuationEditorFragment
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.lazyRoute

class PinyinTextConfigFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private val args by lazyRoute<SettingsRoute.PinyinTextConfig>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pinyin_text_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = view.findViewById<TabLayout>(R.id.pinyin_text_config_tabs)
        val pager = view.findViewById<ViewPager2>(R.id.pinyin_text_config_pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = TAB_COUNT

            override fun createFragment(position: Int): Fragment = when (position) {
                TAB_PUNCTUATION -> PunctuationEditorFragment.newInstance(args.sectionTitle)
                else -> PinyinTextConversionFragment()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.setText(
                if (position == TAB_PUNCTUATION) {
                    R.string.pinyin_config_punctuation
                } else {
                    R.string.pinyin_config_script_conversion
                }
            )
        }.attach()
        val pendingTab = viewModel.peekPendingPreferenceTab()
        if (pendingTab != null && pendingTab in 0 until TAB_COUNT && pager.currentItem != pendingTab) {
            pager.setCurrentItem(pendingTab, false)
            pager.doOnLayout {
                if (pager.currentItem != pendingTab) pager.setCurrentItem(pendingTab, false)
            }
        }
        val pendingKey = viewModel.peekPendingPreferenceScrollKey()
        if (pendingKey != null && optionForTab(pendingTab ?: -1) == pendingKey) {
            viewModel.consumePendingPreferenceScrollKey(pendingKey)
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(args.sectionTitle)
    }

    internal companion object {
        const val TAB_PUNCTUATION = 0
        const val TAB_SCRIPT_CONVERSION = 1
        private const val TAB_COUNT = 2

        internal fun tabForOption(optionName: String): Int? = when (optionName) {
            OPTION_PUNCTUATION -> TAB_PUNCTUATION
            OPTION_SCRIPT_CONVERSION -> TAB_SCRIPT_CONVERSION
            else -> null
        }

        internal fun optionForTab(tab: Int): String? = when (tab) {
            TAB_PUNCTUATION -> OPTION_PUNCTUATION
            TAB_SCRIPT_CONVERSION -> OPTION_SCRIPT_CONVERSION
            else -> null
        }

        private const val OPTION_PUNCTUATION = "Punctuation"
        private const val OPTION_SCRIPT_CONVERSION = "Chttrans"
    }
}

class PinyinTextConversionFragment : FcitxPreferenceFragment() {
    override val shouldUpdateToolbarTitle = false
    override val preferenceTabIndex: Int = PinyinTextConfigFragment.TAB_SCRIPT_CONVERSION
    override val preferenceFragmentManager: FragmentManager
        get() = parentFragment?.parentFragmentManager ?: parentFragmentManager

    override fun getPageTitle(): String = getString(R.string.pinyin_config_script_conversion)

    override suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig =
        fcitx.getAddonConfig(CHTTRANS_ADDON)

    override suspend fun saveConfig(
        fcitx: FcitxAPI,
        newConfig: RawConfig
    ) {
        fcitx.setAddonConfig(CHTTRANS_ADDON, newConfig)
    }

    private companion object {
        const val CHTTRANS_ADDON = "chttrans"
    }
}
