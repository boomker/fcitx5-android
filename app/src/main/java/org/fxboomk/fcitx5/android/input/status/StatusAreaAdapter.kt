/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.data.theme.Theme

abstract class StatusAreaAdapter : RecyclerView.Adapter<StatusAreaAdapter.Holder>() {
    inner class Holder(val ui: StatusAreaEntryUi) : RecyclerView.ViewHolder(ui.root)

    var entries: Array<StatusAreaEntry> = arrayOf()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(StatusAreaEntryUi(parent.context, theme))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.ui.setEntry(entry)
        holder.ui.root.setOnClickListener {
            onItemClick(it, entry)
        }
        // Handle long press for ActionEntry with long press action
        if (entry is StatusAreaEntry.ActionEntry && entry.longPressAction != null) {
            holder.ui.root.setOnLongClickListener {
                onItemLongClick(it, entry, entry.longPressAction)
            }
        } else {
            holder.ui.root.setOnLongClickListener(null)
        }
    }

    abstract val theme: Theme

    override fun getItemCount() = entries.size

    abstract fun onItemClick(view: View, entry: StatusAreaEntry)
    
    open fun onItemLongClick(view: View, entry: StatusAreaEntry, action: StatusAreaEntry.ActionEntry.LongPressActionType): Boolean {
        return false
    }
}