/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.expanded

import android.graphics.Typeface
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.candidates.CandidateItemUi
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.font.FontProviders

open class PagingCandidateViewAdapter(val theme: Theme) :
    PagingDataAdapter<String, CandidateViewHolder>(diffCallback) {

    // Cache candidate font and refresh only when font configuration changes.
    private var candFont: Typeface? = FontProviders.resolveTypeface("cand_font", null)

    private fun refreshCandidateFontIfNeeded() {
        if (FontProviders.needsRefresh()) {
            candFont = FontProviders.resolveTypeface("cand_font", null)
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem === newItem
            }

            override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
                return oldItem.contentEquals(newItem)
            }
        }
    }

    var offset = 0
        private set

    fun refreshWithOffset(offset: Int) {
        refreshCandidateFontIfNeeded()
        this.offset = offset
        refresh()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme, candFont)
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        refreshCandidateFontIfNeeded()
        val text = getItem(position)!!
        holder.ui.applyConfiguredTypeface(candFont)
        holder.ui.text.text = text
        holder.text = text
        holder.idx = position + offset
    }
}
