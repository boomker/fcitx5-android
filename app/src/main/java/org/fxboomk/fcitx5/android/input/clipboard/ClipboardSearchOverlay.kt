/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.content.Context
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FormattedText
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.theme.Theme

class ClipboardSearchOverlay(
    context: Context,
    theme: Theme,
    entryRadius: Float,
    maskSensitive: Boolean,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit,
    private val onCursorPositioned: () -> Unit,
    private val onEntryClick: (ClipboardEntry) -> Unit
) {
    private val ui = ClipboardSearchUi(context, theme)
    val root get() = ui.root
    private val inputState = ClipboardSearchInputState()

    private val adapter = ClipboardSearchAdapter(theme, entryRadius, maskSensitive, onEntryClick)
    private var searchJob: Job? = null
    private var selectedCategory = ClipboardSearchCategory.Local
    private var categoryExplicitlySelected = false

    init {
        ui.recyclerView.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        ui.recyclerView.itemAnimator = null
        ui.recyclerView.adapter = adapter
        ui.backButton.setOnClickListener { onClose() }
        ui.clearButton.setOnClickListener {
            inputState.clear()
            onInputChanged()
        }
        ui.setOnCategorySelectedListener { category ->
            selectedCategory = category
            categoryExplicitlySelected = true
            onInputChanged()
        }
        ui.setOnCursorPositionedListener(::setCursor)
        ui.setSelectedCategory(selectedCategory)
        showInitialState()
    }

    fun open() {
        inputState.clear()
        selectedCategory = ClipboardSearchCategory.Local
        categoryExplicitlySelected = false
        adapter.submitList(emptyList())
        ui.setSelectedCategory(selectedCategory)
        ui.renderInput(inputState)
        showInitialState()
    }

    fun close() {
        searchJob?.cancel()
        searchJob = null
        adapter.submitList(emptyList())
        inputState.clear()
    }

    fun commit(text: String, cursor: Int = -1) {
        inputState.commit(text, cursor)
        onInputChanged()
    }

    fun setPreedit(text: FormattedText) {
        inputState.setPreedit(text)
        onInputChanged()
    }

    fun backspace() {
        inputState.backspace()
        onInputChanged()
    }

    fun delete() {
        inputState.delete()
        onInputChanged()
    }

    fun moveCursor(delta: Int) {
        inputState.moveCursor(delta)
        ui.renderInput(inputState)
    }

    private fun setCursor(offset: Int) {
        val inputChanged = inputState.setCursor(offset)
        onCursorPositioned()
        if (inputChanged) onInputChanged() else ui.renderInput(inputState)
    }

    fun deleteSurrounding(before: Int, after: Int) {
        inputState.deleteSurrounding(before, after)
        onInputChanged()
    }

    private fun onInputChanged() {
        ui.renderInput(inputState)
        searchJob?.cancel()
        val query = inputState.text.trim()
        if (query.isEmpty() && !categoryExplicitlySelected) {
            adapter.submitList(emptyList())
            showInitialState()
            return
        }
        val category = selectedCategory
        ui.showMessage(ui.ctx.getString(R.string.clipboard_search_searching))
        searchJob = scope.launch(Dispatchers.Main.immediate) {
            delay(120)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ClipboardManager.search(
                        query = query,
                        category = category,
                        fallbackFromLocalToAll = !categoryExplicitlySelected
                    )
                }
            }.getOrElse {
                if (inputState.text.trim() == query && selectedCategory == category) {
                    adapter.submitList(emptyList())
                    ui.showMessage(ui.ctx.getString(R.string.clipboard_search_no_results))
                }
                return@launch
            }
            if (inputState.text.trim() != query || selectedCategory != category) return@launch
            adapter.submitList(result.entries)
            if (result.entries.isEmpty()) {
                ui.showMessage(ui.ctx.getString(R.string.clipboard_search_no_results))
            } else {
                val status = if (result.usedAutomaticFallback) {
                    R.string.clipboard_search_auto_all_results
                } else when (result.category) {
                    ClipboardSearchCategory.All -> R.string.clipboard_search_all_results
                    ClipboardSearchCategory.Favorites -> R.string.clipboard_search_favorite_results
                    ClipboardSearchCategory.Local -> R.string.clipboard_search_local_results
                    ClipboardSearchCategory.Remote -> R.string.clipboard_search_remote_results
                }
                ui.showResults(ui.ctx.getString(status, result.entries.size))
            }
        }
    }

    private fun showInitialState() {
        ui.showMessage(ui.ctx.getString(R.string.clipboard_search_initial))
    }
}
