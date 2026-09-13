/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.theme.Theme

internal class TextKeyboardLayoutState(
    var ime: InputMethodEntry? = null,
) {
    fun getLayout(theme: Theme): List<List<KeyDef>> = TextKeyboard.getLayout(ime, theme)
}
