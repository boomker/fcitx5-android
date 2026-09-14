/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolKeyTest {

    @Test
    fun fullStopLabelUsesAsciiFullStopForFcitxAction() {
        val action = SymbolKey("。").pressAction()

        assertTrue(action is KeyAction.FcitxKeyAction)
        assertEquals(".", (action as KeyAction.FcitxKeyAction).act)
    }

    @Test
    fun otherSymbolLabelsKeepTheirOriginalFcitxAction() {
        val action = SymbolKey("，").pressAction()

        assertTrue(action is KeyAction.FcitxKeyAction)
        assertEquals("，", (action as KeyAction.FcitxKeyAction).act)
    }

    private fun SymbolKey.pressAction(): KeyAction =
        behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
}
