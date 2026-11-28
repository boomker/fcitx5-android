/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.utils

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalesTest {
    @Test
    fun englishPrimaryLocaleDoesNotFallBackToSecondaryChineseLocale() {
        assertEquals(
            listOf("en"),
            Locales.localeTags(listOf(Locale.US, Locale.SIMPLIFIED_CHINESE)),
        )
    }

    @Test
    fun chinesePrimaryLocaleKeepsItsTranslationsAndSystemFallbacks() {
        val tags = Locales.localeTags(listOf(Locale.SIMPLIFIED_CHINESE, Locale.US))

        assertEquals("zh_CN", tags.first())
        assertTrue("zh" in tags)
        assertTrue("en_US" in tags)
        assertTrue("en" in tags)
    }
}
