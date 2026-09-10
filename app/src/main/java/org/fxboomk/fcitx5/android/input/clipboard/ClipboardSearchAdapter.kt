/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.theme.Theme

class ClipboardSearchAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: Boolean,
    private val onEntryClick: (ClipboardEntry) -> Unit
) : ListAdapter<ClipboardEntry, ClipboardSearchAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val ui: ClipboardEntryUi) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ClipboardEntryUi(parent.context, theme, entryRadius))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        holder.ui.setEntry(
            ClipboardAdapter.excerptText(entry.text, entry.sensitive && maskSensitive),
            entry.pinned
        )
        holder.ui.root.setOnClickListener { onEntryClick(entry) }
        holder.ui.root.setOnLongClickListener(null)
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<ClipboardEntry>() {
            override fun areItemsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ClipboardEntry, newItem: ClipboardEntry) =
                oldItem == newItem
        }
    }
}
