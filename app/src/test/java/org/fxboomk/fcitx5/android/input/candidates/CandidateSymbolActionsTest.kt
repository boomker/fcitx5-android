/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates

import org.fxboomk.fcitx5.android.core.CandidateAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateSymbolActionsTest {
    @Test
    fun `bubble contains only marked symbol alternatives in engine order`() {
        val first = action(100, "🔛", icon = "folded-symbol")
        val second = action(101, "👩‍💻", icon = "folded-symbol")
        val actions = arrayOf(
            action(0, "Forget candidate"),
            first,
            action(102, "", icon = "folded-symbol", separator = true),
            action(1, "Pin to top as custom phrase"),
            second,
            action(103, " ", icon = "folded-symbol")
        )

        assertEquals(listOf(first, second), actions.foldedSymbolActions())
    }

    @Test
    fun `emoji labels and action numbers alone do not identify a folded run`() {
        assertTrue(arrayOf(action(100, "🔛"), action(101, "👧")).foldedSymbolActions().isEmpty())
        assertTrue(emptyArray<CandidateAction>().foldedSymbolActions().isEmpty())
    }

    @Test
    fun `alternative identity is preserved for identical labels`() {
        val first = action(100, "👋", icon = "folded-symbol")
        val second = action(105, "👋", icon = "folded-symbol")

        assertEquals(listOf(100, 105), arrayOf(first, second).foldedSymbolActions().map { it.id })
    }

    private fun action(
        id: Int,
        text: String,
        icon: String = "",
        separator: Boolean = false
    ) = CandidateAction(id, text, separator, icon, false, false)
}
