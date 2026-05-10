/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonsCustomizerActivityTest {

    @Test
    fun findCurrentButtonPositionIgnoresStaleIndexAndFindsCurrentSectionItem() {
        val items = listOf(
            ButtonsCustomizerActivity.ListItem.ButtonItem(
                ConfigurableButton("undo"),
                ButtonsCustomizerActivity.Section.KawaiiBar,
            ),
            ButtonsCustomizerActivity.ListItem.AddButtonPlaceholder,
            ButtonsCustomizerActivity.ListItem.ButtonItem(
                ConfigurableButton("clipboard"),
                ButtonsCustomizerActivity.Section.StatusArea,
            ),
            ButtonsCustomizerActivity.ListItem.StatusAreaAddButtonPlaceholder,
        )

        assertEquals(
            2,
            items.findCurrentButtonPosition(
                buttonId = "clipboard",
                section = ButtonsCustomizerActivity.Section.StatusArea,
            ),
        )
    }

    @Test
    fun findCurrentButtonPositionReturnsMissingForAbsentButton() {
        val items = listOf(
            ButtonsCustomizerActivity.ListItem.ButtonItem(
                ConfigurableButton("undo"),
                ButtonsCustomizerActivity.Section.KawaiiBar,
            ),
            ButtonsCustomizerActivity.ListItem.AddButtonPlaceholder,
        )

        assertEquals(
            -1,
            items.findCurrentButtonPosition(
                buttonId = "clipboard",
                section = ButtonsCustomizerActivity.Section.StatusArea,
            ),
        )
    }
}
