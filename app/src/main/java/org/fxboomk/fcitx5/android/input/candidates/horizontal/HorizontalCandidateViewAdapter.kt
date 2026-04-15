/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates.horizontal

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.candidates.CandidateItemUi
import org.fxboomk.fcitx5.android.input.candidates.CandidateViewHolder
import org.fxboomk.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {

    // Cache candidate font and refresh only when font configuration changes.
    private var candFont: Typeface? = FontProviders.resolveTypeface("cand_font", null)

    private fun refreshCandidateFontIfNeeded(): Boolean {
        if (FontProviders.needsRefresh()) {
            candFont = FontProviders.resolveTypeface("cand_font", null)
            return true
        }
        return false
    }

    init {
        setHasStableIds(true)
    }

    var candidates: Array<String> = arrayOf()
        private set

    var total = -1
        private set

    var activeIndex = -1
        private set

    var indexOffset = 0
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(
        data: Array<String>,
        total: Int,
        activeIndex: Int = this.activeIndex,
        indexOffset: Int = this.indexOffset,
    ) {
        val fontChanged = refreshCandidateFontIfNeeded()
        if (
            !fontChanged &&
            this.total == total &&
            this.activeIndex == activeIndex &&
            this.indexOffset == indexOffset &&
            this.candidates.contentEquals(data)
        ) {
            return
        }
        this.candidates = data
        this.total = total
        this.activeIndex = activeIndex
        this.indexOffset = indexOffset
        notifyDataSetChanged()
    }

    fun updateActiveIndex(index: Int) {
        if (index == activeIndex) return
        val previous = activeIndex
        activeIndex = index
        if (previous in candidates.indices) {
            notifyItemChanged(previous)
        }
        if (activeIndex in candidates.indices) {
            notifyItemChanged(activeIndex)
        }
    }

    override fun getItemCount() = candidates.size

    override fun getItemId(position: Int) = candidates.getOrNull(position).hashCode().toLong()

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme, candFont)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        refreshCandidateFontIfNeeded()
        val text = candidates[position]
        holder.ui.applyConfiguredTypeface(candFont)
        holder.ui.text.text = text
        holder.ui.setActive(position == activeIndex)
        holder.text = text
        holder.idx = position + indexOffset
    }

}
