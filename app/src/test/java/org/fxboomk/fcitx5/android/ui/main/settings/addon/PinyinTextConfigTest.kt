/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import org.fxboomk.fcitx5.android.ui.main.settings.ConfigLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinTextConfigTest {

    @Test
    fun textOptionsTargetSeparateTabs() {
        assertEquals(
            PinyinTextConfigFragment.TAB_PUNCTUATION,
            PinyinTextConfigFragment.tabForOption("Punctuation")
        )
        assertEquals(
            PinyinTextConfigFragment.TAB_SCRIPT_CONVERSION,
            PinyinTextConfigFragment.tabForOption("Chttrans")
        )
        assertNull(PinyinTextConfigFragment.tabForOption("Other"))
    }

    @Test
    fun onlyThePunctuationAndConversionSectionUsesTheTabbedPage() {
        val layout = ConfigLayout(
            sections = listOf(
                ConfigLayout.Section(options = listOf("Punctuation", "Chttrans")),
                ConfigLayout.Section(options = listOf("Punctuation"))
            )
        )

        assertTrue(AddonConfigLayouts.isPinyinTextSection(layout, 0))
        assertFalse(AddonConfigLayouts.isPinyinTextSection(layout, 1))
    }
}
