/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.lifecycle.LifecycleOwner
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.item
import splitties.resources.styledColor

class EditDeleteMenuProvider<T>(
    private val viewModel: MainViewModel,
    menuHost: T,
    lifecycleOwner: LifecycleOwner,
) : MenuProvider where T : MenuHost, T : Context {

    private val tint = menuHost.styledColor(android.R.attr.colorControlNormal)

    init {
        // Automatically rebuild the menu whenever the edit/delete button state changes
        viewModel.toolbarEditButtonVisible.observe(lifecycleOwner) { menuHost.invalidateMenu() }
        viewModel.toolbarEditButtonOnClickListener.observe(lifecycleOwner) { menuHost.invalidateMenu() }
        viewModel.toolbarDeleteButtonOnClickListener.observe(lifecycleOwner) { menuHost.invalidateMenu() }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        val editListener = viewModel.toolbarEditButtonOnClickListener.value
        val editVisible = viewModel.toolbarEditButtonVisible.value == true
        if (editListener != null && editVisible) {
            menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, tint, true) {
                editListener()
            }
        }
        val deleteListener = viewModel.toolbarDeleteButtonOnClickListener.value
        if (deleteListener != null) {
            menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, tint, true) {
                deleteListener()
            }
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
}