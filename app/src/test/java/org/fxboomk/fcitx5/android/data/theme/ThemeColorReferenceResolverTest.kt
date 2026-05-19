/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorReferenceResolverTest {

    @Test
    fun resolvesKnownThemeToken() {
        val theme = ThemePreset.MaterialLight

        assertEquals(theme.keyTextColor, resolveThemeColorToken(theme, "keyTextColor"))
        assertEquals(theme.accentKeyTextColor, resolveThemeColorToken(theme, "accentKeyTextColor"))
    }

    @Test
    fun returnsNullForUnknownThemeToken() {
        assertNull(resolveThemeColorToken(ThemePreset.MaterialLight, "missingColorToken"))
    }
}
