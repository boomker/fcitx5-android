/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.common.preference

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory

/** Restores preference dialogs saved before their package and class names changed. */
internal class PreferenceDialogFragmentFactory(
    private val delegate: FragmentFactory,
) : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
        when (className) {
            "org.fxboomk.fcitx5.android.ui.main.modified.MyEditTextPreferenceDialogFragment" ->
                ResettableEditTextPreferenceDialogFragment()
            "org.fxboomk.fcitx5.android.ui.main.modified.MyListPreferenceDialogFragment" ->
                ResettableListPreferenceDialogFragment()
            else -> delegate.instantiate(classLoader, className)
        }
}
