/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.common.BaseDynamicListUi
import org.fxboomk.fcitx5.android.ui.common.DynamicListTouchCallback
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import org.fxboomk.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager.RimeSchemaResolver
import org.fxboomk.fcitx5.android.utils.lazyRoute
import org.fxboomk.fcitx5.android.utils.toast
import org.json.JSONArray
import timber.log.Timber

internal fun visibleSchemaIds(
    savedOrder: List<String>?,
    userIds: List<String>,
    availableIds: List<String>
): List<String> {
    val available = availableIds.toSet()
    return ((savedOrder ?: emptyList()) + userIds)
        .filter { it in available }
        .distinct()
}

class GenericMultiSelectFragment : Fragment() {
    private data class MultiItem(val selected: Boolean, val id: String, val label: String)

    private val args by lazyRoute<SettingsRoute.MultiSelect>()
    private val viewModel: MainViewModel by activityViewModels()

    private var containerView: FrameLayout? = null
    private var ui: BaseDynamicListUi<MultiItem>? = null
    private var dirty = false
    private var selectionChanged = false
    private var isLoading = true

    private val enableOrder: Boolean
        get() = args.addon == "rime" && args.path == "schema-selector"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = FrameLayout(requireContext())
        containerView = root
        lifecycleScope.launch {
            val (allItems, userItems) = viewModel.fcitx.runOnReady {
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
                parseItems(payload) to parseItems(cfg.findByName("UserItems")?.value.orEmpty())
            }
            val configuredIds = if (enableOrder) {
                withContext(Dispatchers.IO) { RimeSchemaResolver.readSelectedIds() }
                    .takeIf { it.isNotEmpty() }
                    ?: allItems.filter { it.selected }.map { it.id }
            } else {
                emptyList()
            }
            val selectedIds = configuredIds.toSet()
            val loaded = if (enableOrder) {
                val savedOrder = readSavedSchemaOrder()
                val byId = allItems.associateBy { it.id }
                visibleSchemaIds(savedOrder, userItems.map { it.id }, allItems.map { it.id })
                    .mapNotNull { id -> byId[id]?.copy(selected = id in selectedIds) }
            } else {
                allItems
            }

            var uiRef: BaseDynamicListUi<MultiItem>? = null
            val listUi = object : BaseDynamicListUi<MultiItem>(
                requireContext(),
                if (enableOrder) Mode.ChooseOne {
                    allItems.filter { candidate -> entries.none { it.id == candidate.id } }
                        .map { it.copy(selected = it.id in selectedIds) }
                        .toTypedArray()
                } else Mode.Custom(),
                loaded,
                enableOrder = enableOrder,
                initCheckBox = { item ->
                    visibility = View.VISIBLE
                    // Do not set text, checkbox width is only dp(30), text will overflow
                    // nameText already displays the item label
                    contentDescription = item.label
                    setOnCheckedChangeListener(null)
                    isChecked = item.selected
                    setOnCheckedChangeListener { _, checked ->
                        if (isLoading || checked == item.selected) return@setOnCheckedChangeListener
                        val idx = uiRef?.entries?.indexOfFirst { it.id == item.id } ?: -1
                        if (idx >= 0) {
                            uiRef?.updateItem(idx, item.copy(selected = checked))
                            dirty = true
                            selectionChanged = true
                        }
                    }
                }
            ) {
                override fun showEntry(x: MultiItem): String = x.label
            }
            listUi.addOnItemChangedListener(object : org.fxboomk.fcitx5.android.ui.common.OnItemChangedListener<MultiItem> {
                override fun onItemAdded(idx: Int, item: MultiItem) {
                    dirty = true
                }

                override fun onItemRemoved(idx: Int, item: MultiItem) {
                    dirty = true
                }

                override fun onItemSwapped(fromIdx: Int, toIdx: Int, item: MultiItem) {
                    dirty = true
                }
            })
            if (enableOrder) {
                listUi.addTouchCallback(object : DynamicListTouchCallback<MultiItem>(
                    requireContext(),
                    listUi
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder
                    ): Int = 0
                })
            }
            uiRef = listUi
            ui = listUi
            viewModel.toolbarButton.value = ButtonMode.NONE
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
        val entries = listUi.entries.toList()
        if (enableOrder) saveSchemaOrder(entries.map { it.id })
        if (!selectionChanged && !enableOrder) {
            dirty = false
            return
        }
        if (selectionChanged && entries.count { it.selected } < args.min) {
            requireContext().toast(getString(R.string.generic_multiselect_min, args.min))
            return
        }

        val shouldDeploy = selectionChanged
        val savePath = if (enableOrder && !shouldDeploy) "schema-selector-order" else args.path
        dirty = false
        selectionChanged = false
        lifecycleScope.launch(NonCancellable + Dispatchers.IO) {
            val payload = encodeItems(entries)
            Timber.i(
                "GenericMultiSelect save addon=%s path=%s option=%s rows=%d payloadLen=%d",
                args.addon,
                args.path,
                args.option,
                entries.size,
                payload.length
            )
            val config = RawConfig(
                arrayOf(RawConfig(args.option, payload)) +
                    if (enableOrder) arrayOf(RawConfig("SelectionChanged", if (shouldDeploy) "1" else "0"))
                    else emptyArray()
            )
            viewModel.fcitx.runOnReady {
                setAddonSubConfig(args.addon, savePath, config)
            }
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

    private fun readSavedSchemaOrder(): List<String>? {
        val raw = requireContext().getSharedPreferences(SCHEMA_PREFS, Context.MODE_PRIVATE)
            .getString(SCHEMA_ORDER, null) ?: return null
        return runCatching {
            JSONArray(raw).let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
        }.getOrNull()
    }

    private fun saveSchemaOrder(ids: List<String>) {
        val array = JSONArray()
        ids.forEach(array::put)
        requireContext().getSharedPreferences(SCHEMA_PREFS, Context.MODE_PRIVATE)
            .edit().putString(SCHEMA_ORDER, array.toString()).apply()
    }

    private companion object {
        const val SCHEMA_PREFS = "rime-schema-selector"
        const val SCHEMA_ORDER = "visible-order"
    }
}
