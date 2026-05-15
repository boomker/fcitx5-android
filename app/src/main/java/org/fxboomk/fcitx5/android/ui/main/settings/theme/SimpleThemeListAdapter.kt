/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.data.theme.Theme
import splitties.views.dsl.core.Ui

open class SimpleThemeListAdapter<T : Theme>(themes: List<T>) :
    RecyclerView.Adapter<SimpleThemeListAdapter.ViewHolder>() {

    private val entries = themes.toMutableList()

    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    private var selectedIndex = -1

    var selected: Int
        get() = selectedIndex
        set(value) {
            updateSelection(value, notify = true)
        }

    val selectedTheme
        get() = entries.getOrNull(selectedIndex)

    fun setThemes(themes: List<T>, selectedThemeName: String? = null) {
        entries.clear()
        entries.addAll(themes)
        updateSelection(selectedThemeName?.let { name ->
            entries.indexOfFirst { it.name == name }
        } ?: -1, notify = false)
        notifyDataSetChanged()
    }

    private fun updateSelection(value: Int, notify: Boolean) {
        val last = selectedIndex
        selectedIndex = value
        if (!notify) return
        if (last in entries.indices) {
            notifyItemChanged(last)
        }
        if (selectedIndex in entries.indices) {
            notifyItemChanged(selectedIndex)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ThemeThumbnailUi(parent.context))

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (holder.ui is ThemeThumbnailUi) {
            holder.ui.cleanup()
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.ui as ThemeThumbnailUi).apply {
            val theme = entries[position]
            setTheme(theme)
            editButton.visibility = View.GONE
            setChecked(position == selectedIndex)
            root.setOnClickListener {
                val currentPosition = holder.absoluteAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val currentTheme = entries.getOrNull(currentPosition) ?: return@setOnClickListener
                onClick(currentTheme)
                selected = currentPosition
            }
        }
    }

    override fun getItemCount(): Int = entries.size

    open fun onClick(theme: T) {}
}
