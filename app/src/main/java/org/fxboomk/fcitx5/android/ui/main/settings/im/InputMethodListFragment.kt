/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
@file:Suppress("NotifyDataSetChanged")
package org.fxboomk.fcitx5.android.ui.main.settings.im

import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.SubtypeManager
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeCodec
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.ui.common.BaseDynamicListUi
import org.fxboomk.fcitx5.android.ui.common.DynamicListUi
import org.fxboomk.fcitx5.android.ui.common.OnItemChangedListener
import org.fxboomk.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fxboomk.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fxboomk.fcitx5.android.ui.main.settings.ProgressFragment
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.utils.onPositiveButtonClick
import org.fxboomk.fcitx5.android.utils.toast
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import splitties.dimensions.dp

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

    private fun showRenameSchemeDialog() {
        val context = requireContext()
        val scheme = CustomShuangpinSchemeStore.load()
        val nameInput = EditText(context).apply {
            setText(scheme.name)
            setSingleLine()
            setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        }
        val idInput = EditText(context).apply {
            setText(scheme.id)
            setSingleLine()
            hint = context.getString(R.string.custom_shuangpin_scheme_id_hint)
            setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(8))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameInput)
            addView(TextView(context).apply {
                setText(R.string.custom_shuangpin_scheme_id)
                setPadding(context.dp(20), context.dp(12), context.dp(20), 0)
            })
            addView(idInput)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.custom_shuangpin_scheme_name)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .onPositiveButtonClick {
                val name = nameInput.text.toString().trim()
                val id = idInput.text.toString().trim().lowercase()
                when {
                    !CustomShuangpinSchemeCodec.isValidName(name) -> {
                        context.toast(R.string.custom_shuangpin_name_invalid)
                        false
                    }
                    !ShuangpinLayoutKeys.isValidCustomSuffix(id) -> {
                        context.toast(R.string.custom_shuangpin_id_invalid)
                        false
                    }
                    name == scheme.name && id == scheme.id -> true
                    else -> {
                        renameScheme(name, id)
                        true
                    }
                }
            }
        nameInput.requestFocus()
    }

    private fun renameScheme(name: String, id: String) {
        val context = requireContext()
        val previousUniqueName = CustomShuangpinSchemeStore.currentUniqueName()
        CustomShuangpinSchemeStore.rename(name, id).onFailure {
            context.toast(it)
            return
        }
        if (!isInitialized) return
        val currentUniqueName = CustomShuangpinSchemeStore.currentUniqueName()
        if (previousUniqueName != currentUniqueName) {
            fcitx.launchOnReady { f ->
                // native 侧 reloadConfig() 会一并触发 Instance::refresh()，
                // 让新写入的 inputmethod/<uniqueName>.conf 立刻被枚举到。
                f.reloadConfig()
                f.refreshImEntry()
                f.setEnabledIme(
                    ui.entries.map { entry ->
                        if (entry.uniqueName == previousUniqueName) currentUniqueName
                        else entry.uniqueName
                    }.toTypedArray()
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    SubtypeManager.syncWith(f.enabledIme())
                }
            }
            val previousEntry = ui.entries.firstOrNull { it.uniqueName == previousUniqueName }
            if (previousEntry != null) {
                ui.updateItem(
                    ui.indexItem(previousEntry),
                    previousEntry.copy(uniqueName = currentUniqueName)
                )
            }
        }
        ui.notifyDataSetChanged()
    }

    private lateinit var ui: BaseDynamicListUi<InputMethodEntry>

    override suspend fun initialize(): View {
        val available = fcitx.runOnReady {
            availableIme().sortedWith(
                compareBy<InputMethodEntry> { preferredOrderWeight(it.uniqueName) }
                    .thenBy { it.displayName }
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
                    if (CustomShuangpinSchemeStore.isCustomScheme(entry.uniqueName)) {
                        navigateWithAnim(SettingsRoute.CustomShuangpin)
                    } else {
                        navigateWithAnim(
                            SettingsRoute.InputMethodConfig(entry.displayName, entry.uniqueName),
                        )
                    }
                }
            },
            show = { entry ->
                if (CustomShuangpinSchemeStore.isCustomScheme(entry.uniqueName)) {
                    CustomShuangpinSchemeStore.displayName()
                } else {
                    entry.displayName
                }
            },
            initRenameButton = { entry ->
                if (CustomShuangpinSchemeStore.isCustomScheme(entry.uniqueName)) {
                    visibility = View.VISIBLE
                    setOnClickListener { showRenameSchemeDialog() }
                } else {
                    visibility = View.GONE
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
        /**
         * 「输入法」列表的排序顺序（内置条目）。
         *
         * 自定义双拼方案的 uniqueName 可被用户修改，因此不在此列表中，
         * 而是通过 [preferredOrderWeight] 稳定地排在 shuangpin-gb 与
         * shuangpin-ziranma 之间。
         */
        private val STATIC_ADD_ORDER = listOf(
            "pinyin",
            "shuangpin-xiaohe",
            "shuangpin-ms",
            "shuangpin-ziguang",
            "shuangpin-gb",
            "shuangpin-ziranma",
            "shuangpin-abc",
            "shuangpin-pinyinjiajia"
        )

        /** 自定义双拼方案的排序权重：介于 shuangpin-gb(8) 与 shuangpin-ziranma(10) 之间。 */
        private const val CUSTOM_SCHEME_WEIGHT = 9

        fun preferredOrderWeight(uniqueName: String): Int {
            val index = STATIC_ADD_ORDER.indexOf(uniqueName)
            if (index >= 0) return index * 2
            return if (CustomShuangpinSchemeStore.isCustomScheme(uniqueName)) {
                CUSTOM_SCHEME_WEIGHT
            } else {
                Int.MAX_VALUE
            }
        }
    }
}
