/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.common.preference

import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceDialogFragmentCompat
import splitties.dimensions.dp
import splitties.views.dsl.core.verticalMargin

fun PreferenceDialogFragmentCompat.fixDialogMargin(contentView: View) {
    // dialogMessage text in AlertDialog has 48dp bottom margin, just make it smaller
    contentView.findViewById<View>(android.R.id.message)?.updateLayoutParams<MarginLayoutParams> {
        verticalMargin = requireContext().dp(8)
    }
}
