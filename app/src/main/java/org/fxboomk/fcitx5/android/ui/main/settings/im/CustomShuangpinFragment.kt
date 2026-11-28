/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.ui.main.MainViewModel

class CustomShuangpinFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private var pendingTabApplied = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_custom_shuangpin, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabs = view.findViewById<TabLayout>(R.id.custom_shuangpin_tabs)
        val pager = view.findViewById<ViewPager2>(R.id.custom_shuangpin_pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = TAB_COUNT

            override fun createFragment(position: Int): Fragment = when (position) {
                TAB_MAPPING -> CustomShuangpinMappingFragment()
                else -> CustomShuangpinPreferencesFragment()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = getString(
                if (position == TAB_MAPPING) {
                    R.string.custom_shuangpin_tab_mapping
                } else {
                    R.string.custom_shuangpin_tab_preferences
                }
            )
        }.attach()
        applyPendingTab(pager)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(CustomShuangpinSchemeStore.displayName(requireContext()))
    }

    /**
     * Switch to the tab holding the preference the user asked for, if any.
     */
    private fun applyPendingTab(pager: ViewPager2) {
        if (pendingTabApplied) return
        val tab = viewModel.peekPendingPreferenceTab() ?: return
        if (tab !in 0 until TAB_COUNT) return
        pendingTabApplied = true
        if (pager.currentItem != tab) {
            pager.setCurrentItem(tab, false)
            // The pager may not be laid out yet, make sure the tab is applied anyway.
            pager.doOnLayout {
                if (pager.currentItem != tab) {
                    pager.setCurrentItem(tab, false)
                }
            }
        }
    }

    internal companion object {
        const val TAB_MAPPING = 0
        const val TAB_PREFERENCES = 1
        private const val TAB_COUNT = 2
    }
}
