/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RimeSchemaMenuTest {
    @Test
    fun `new menu shows only user schemas`() {
        assertEquals(
            listOf("user-a", "user-b"),
            visibleSchemaIds(null, listOf("user-a", "user-b"), listOf("bundled", "user-a", "user-b"))
        )
    }

    @Test
    fun `saved order retains added and unchecked schemas while appending new user schemas`() {
        assertEquals(
            listOf("bundled", "user-b", "user-a", "user-new"),
            visibleSchemaIds(
                listOf("bundled", "user-b", "user-a", "removed"),
                listOf("user-a", "user-b", "user-new"),
                listOf("user-a", "user-new", "bundled", "user-b")
            )
        )
    }
}
