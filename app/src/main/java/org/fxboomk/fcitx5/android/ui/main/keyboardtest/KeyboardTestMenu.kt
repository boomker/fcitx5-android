/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.keyboardtest

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.fxboomk.fcitx5.android.R
import splitties.resources.styledColor

/** A page-scoped action; leaving the page also discards its transient test input. */
internal class KeyboardTestMenu(
    private val activity: AppCompatActivity,
    owner: LifecycleOwner
) : MenuProvider, DefaultLifecycleObserver {
    private var dialog: KeyboardTestDialog? = null

    init {
        owner.lifecycle.addObserver(this)
        activity.addMenuProvider(this, owner, Lifecycle.State.RESUMED)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        if (menu.findItem(R.id.keyboard_test_entry) != null) return
        menu.add(Menu.NONE, R.id.keyboard_test_entry, 100, R.string.keyboard_test_title).apply {
            setIcon(R.drawable.ic_baseline_keyboard_24)
            icon?.mutate()?.setTint(activity.styledColor(android.R.attr.colorControlNormal))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            val description = activity.getString(R.string.keyboard_test_title)
            MenuItemCompat.setContentDescription(this, description)
            MenuItemCompat.setTooltipText(this, description)
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.keyboard_test_entry) return false
        if (!activity.isFinishing && !activity.isDestroyed && dialog?.isShowing != true) {
            dialog = KeyboardTestDialog(activity).also { test ->
                test.setOnDismissListener { if (dialog === test) dialog = null }
                test.show()
            }
        }
        return true
    }

    override fun onStop(owner: LifecycleOwner) {
        dialog?.dismiss()
        dialog = null
    }
}

internal fun Fragment.installKeyboardTestMenu() {
    KeyboardTestMenu(requireActivity() as AppCompatActivity, viewLifecycleOwner)
}
