/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.os.Build
import android.view.View
import androidx.lifecycle.Lifecycle
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.SubtypeManager
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.ui.common.BaseDynamicListUi
import org.fxboomk.fcitx5.android.ui.common.DynamicListUi
import org.fxboomk.fcitx5.android.ui.common.OnItemChangedListener
import org.fxboomk.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fxboomk.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fxboomk.fcitx5.android.ui.main.settings.ProgressFragment
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.navigateWithAnim

class InputMethodListFragment : ProgressFragment(), OnItemChangedListener<InputMethodEntry> {

    private fun updateIMState() {
        if (isInitialized) {
            fcitx.launchOnReady { f ->
                f.setEnabledIme(ui.entries.map { it.uniqueName }.toTypedArray())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    SubtypeManager.syncWith(f.enabledIme())
                }
            }
        }
    }

    private lateinit var ui: BaseDynamicListUi<InputMethodEntry>

    override suspend fun initialize(): View {
        val available = fcitx.runOnReady {
            availableIme().sortedWith(
                compareBy<InputMethodEntry> {
                    preferredAddOrder.indexOf(it.uniqueName)
                        .let { index -> if (index < 0) Int.MAX_VALUE else index }
                }.thenBy { it.displayName }
            )
        }
        val initialEnabled = fcitx.runOnReady { enabledIme().toList() }
        ui = requireContext().DynamicListUi(
            mode = BaseDynamicListUi.Mode.ChooseOne {
                (available - entries.toSet()).toTypedArray()
            },
            initialEntries = initialEnabled,
            enableOrder = true,
            initSettingsButton = { entry ->
                setOnClickListener {
                    if (entry.uniqueName == CustomShuangpinSchemeStore.UNIQUE_NAME) {
                        navigateWithAnim(SettingsRoute.CustomShuangpin)
                    } else {
                        navigateWithAnim(
                            SettingsRoute.InputMethodConfig(entry.displayName, entry.uniqueName),
                        )
                    }
                }
            },
            show = { entry ->
                if (entry.uniqueName == CustomShuangpinSchemeStore.UNIQUE_NAME) {
                    CustomShuangpinSchemeStore.displayName()
                } else {
                    entry.displayName
                }
            }
        )
        ui.addOnItemChangedListener(this@InputMethodListFragment)
        ui.setViewModel(viewModel)
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
        return ui.root
    }

    override fun onStop() {
        if (::ui.isInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::ui.isInitialized) {
            ui.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        if (::ui.isInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    override fun onItemSwapped(fromIdx: Int, toIdx: Int, item: InputMethodEntry) {
        updateIMState()
    }

    override fun onItemAdded(idx: Int, item: InputMethodEntry) {
        updateIMState()
    }

    override fun onItemRemoved(idx: Int, item: InputMethodEntry) {
        updateIMState()
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, InputMethodEntry>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: InputMethodEntry, new: InputMethodEntry) {
        updateIMState()
    }

    private companion object {
        val preferredAddOrder = listOf(
            "pinyin",
            "shuangpin-xiaohe",
            "shuangpin-ms",
            "shuangpin-ziguang",
            "shuangpin-gb",
            CustomShuangpinSchemeStore.UNIQUE_NAME,
            "shuangpin-ziranma",
            "shuangpin-abc",
            "shuangpin-pinyinjiajia",
            "shuangpin-zhongwenzhixing"
        )
    }
}
