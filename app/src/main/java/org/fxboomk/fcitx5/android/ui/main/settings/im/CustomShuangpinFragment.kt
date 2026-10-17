/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.MainViewModel

class CustomShuangpinFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

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
                0 -> CustomShuangpinMappingFragment()
                else -> CustomShuangpinPreferencesFragment()
            }
        }
        TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = getString(
                if (position == 0) {
                    R.string.custom_shuangpin_tab_mapping
                } else {
                    R.string.custom_shuangpin_tab_preferences
                }
            )
        }.attach()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.custom_shuangpin))
    }

    private companion object {
        const val TAB_COUNT = 2
    }
}
