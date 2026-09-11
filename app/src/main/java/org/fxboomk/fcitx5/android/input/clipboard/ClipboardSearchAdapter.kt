/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class ClipboardSearchAdapter(
    private val theme: Theme,
    private val entryRadius: Float,
    private val maskSensitive: Boolean,
    private val onEntryClick: (ClipboardEntry) -> Unit
) : ListAdapter<ClipboardEntry, ClipboardSearchAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val ui: ClipboardEntryUi) : RecyclerView.ViewHolder(ui.root) {
        var thumbnailJob: Job? = null
        var boundThumbnailKey: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ui = ClipboardEntryUi(
            parent.context,
            theme,
            entryRadius,
            searchResultLayout = true
        ).apply {
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                parent.context.dp(84)
            )
        }
        return ViewHolder(ui)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val thumbnailKey = ClipboardAdapter.imagePreviewKey(entry)
        val isImage = thumbnailKey != null
        val displayText = when {
            isImage -> ""
            entry.isUriEntry() -> ClipboardAdapter.compactUriLabel(holder.ui.ctx, entry)
            else -> ClipboardAdapter.excerptText(entry.text, entry.sensitive && maskSensitive)
        }
        val cachedThumbnail = thumbnailKey?.let(ClipboardAdapter.thumbnailCache::get)
        holder.thumbnailJob?.cancel()
        holder.boundThumbnailKey = thumbnailKey
        holder.ui.setEntry(
            displayText,
            entry.pinned,
            previewBitmap = cachedThumbnail,
            compactMedia = isImage
        )
        if (thumbnailKey != null && cachedThumbnail == null) {
            holder.thumbnailJob = scope.launch {
                val bitmap = ClipboardAdapter.loadImagePreview(holder.ui.ctx, entry)
                if (bitmap != null) ClipboardAdapter.thumbnailCache.put(thumbnailKey, bitmap)
                if (holder.boundThumbnailKey == thumbnailKey) {
                    holder.ui.setEntry(displayText, entry.pinned, bitmap, compactMedia = true)
                }
            }
        }
        holder.ui.root.setOnClickListener { onEntryClick(entry) }
        holder.ui.root.setOnLongClickListener(null)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.thumbnailJob?.cancel()
        holder.thumbnailJob = null
        holder.boundThumbnailKey = null
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
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
