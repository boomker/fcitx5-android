/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class GenericMultiSelectFragment : Fragment() {
    private data class MultiItem(val selected: Boolean, val id: String, val label: String)

    private val args by lazyRoute<SettingsRoute.MultiSelect>()
    private val viewModel: MainViewModel by activityViewModels()

    private var containerView: FrameLayout? = null
    private var ui: BaseDynamicListUi<MultiItem>? = null
    private var dirty = false
    private var isLoading = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext())
        containerView = root
        lifecycleScope.launch {
            val loaded = viewModel.fcitx.runOnReady {
                val raw = getAddonSubConfig(args.addon, args.path)
                val cfg = raw.findByName("cfg") ?: raw
                val payload = cfg.findByName(args.option)?.value.orEmpty()
                Timber.i(
                    "GenericMultiSelect load addon=%s path=%s option=%s rawItems=%s payloadLen=%d",
                    args.addon,
                    args.path,
                    args.option,
                    cfg.subItems?.joinToString(",") { it.name },
                    payload.length
                )
                parseItems(payload)
            }

            var uiRef: BaseDynamicListUi<MultiItem>? = null
            val listUi = object : BaseDynamicListUi<MultiItem>(
                requireContext(),
                Mode.Custom(),
                loaded,
                enableOrder = false,
                initCheckBox = { item ->
                    visibility = View.VISIBLE
                    // Do not set text, checkbox width is only dp(30), text will overflow
                    // nameText already displays the item label
                    contentDescription = item.label
                    isChecked = item.selected
                    setOnCheckedChangeListener { _, checked ->
                        if (isLoading) return@setOnCheckedChangeListener
                        val idx = uiRef?.entries?.indexOfFirst { it.id == item.id } ?: -1
                        if (idx >= 0) {
                            uiRef?.updateItem(idx, item.copy(selected = checked))
                            dirty = true
                        }
                    }
                }
            ) {
                override fun showEntry(x: MultiItem): String = x.label
            }
            uiRef = listUi
            ui = listUi
            viewModel.disableToolbarEditButton()
            viewModel.setToolbarTitle(args.title)
            withContext(Dispatchers.Main) {
                containerView?.removeAllViews()
                containerView?.addView(listUi.root)
            }
            isLoading = false
        }
        return root
    }

    override fun onStop() {
        super.onStop()
        val listUi = ui ?: return
        if (!dirty) return
        val selectedCount = listUi.entries.count { it.selected }
        if (selectedCount < args.min) {
            requireContext().toast(getString(R.string.generic_multiselect_min, args.min))
            return
        }

        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            val payload = encodeItems(listUi.entries)
            Timber.i(
                "GenericMultiSelect save addon=%s path=%s option=%s rows=%d payloadLen=%d",
                args.addon,
                args.path,
                args.option,
                listUi.entries.size,
                payload.length
            )
            val config = RawConfig(arrayOf(RawConfig(args.option, payload)))
            viewModel.fcitx.runOnReady {
                setAddonSubConfig(args.addon, args.path, config)
            }
            dirty = false
        }
    }

    override fun onDestroy() {
        containerView = null
        ui = null
        isLoading = true
        super.onDestroy()
    }

    private fun parseItems(text: String): List<MultiItem> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            MultiItem(
                selected = parts[0] == "1",
                id = parts[1],
                label = sanitizeLabel(parts[2])
            )
        }.toList()
    }

    private fun sanitizeLabel(raw: String): String {
        val cleaned = raw.replace('\t', ' ').replace('|', ' ').trim()
        return cleaned.ifEmpty { raw.trim() }
    }

    private fun encodeItems(items: List<MultiItem>): String =
        items.joinToString("\n") { "${if (it.selected) "1" else "0"}\t${it.id}\t${it.label}" }
}
