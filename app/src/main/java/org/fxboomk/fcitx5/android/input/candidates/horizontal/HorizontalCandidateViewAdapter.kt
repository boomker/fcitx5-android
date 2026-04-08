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

    private fun refreshCandidateFontIfNeeded() {
        if (FontProviders.needsRefresh()) {
            candFont = FontProviders.resolveTypeface("cand_font", null)
        }
    }

    init {
        setHasStableIds(true)
    }

    var candidates: Array<String> = arrayOf()
        private set

    var total = -1
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun updateCandidates(data: Array<String>, total: Int) {
        refreshCandidateFontIfNeeded()
        this.candidates = data
        this.total = total
        notifyDataSetChanged()
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
        holder.text = text
        holder.idx = position
    }

}
