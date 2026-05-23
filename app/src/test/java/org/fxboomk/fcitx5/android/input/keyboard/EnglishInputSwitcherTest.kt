/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnglishInputSwitcherTest {

    @Test
    fun picksPseudoEnglishEntryFromMatchingSchemeMenu() {
        val english = action(id = 1, label = "English")
        val pinyin = action(id = 2, label = "拼音")
        val wubi = action(id = 3, label = "五笔")
        val punctuationMenu = action(
            id = 20,
            label = "Punctuation",
            menu = arrayOf(action(id = 21, label = "，"), action(id = 22, label = "。"))
        )
        val actions = arrayOf(
            punctuationMenu,
            action(
                id = 10,
                label = "Scheme",
                menu = arrayOf(english, pinyin, wubi, separator(), action(id = 4, label = "Settings"))
            )
        )

        val target = findEnglishModeAction(actions, inputMethod(subModeLabel = "拼音"))

        assertEquals(1, target?.id)
    }

    @Test
    fun returnsNullWhenNoSchemeMenuExists() {
        val actions = arrayOf(
            action(
                id = 10,
                label = "Toolbar",
                menu = arrayOf(action(id = 11, label = "Theme"))
            )
        )

        val target = findEnglishModeAction(actions, inputMethod(subModeLabel = "拼音"))

        assertNull(target)
    }

    @Test
    fun restoresPreviousNonEnglishSubModeFromSchemeMenu() {
        val actions = arrayOf(
            action(
                id = 10,
                label = "Scheme",
                menu = arrayOf(
                    checkedAction(id = 1, label = "English"),
                    action(id = 2, label = "拼音"),
                    action(id = 3, label = "五笔"),
                    separator()
                )
            )
        )

        val target = findRestorableSubModeAction(actions, inputMethod(subModeLabel = "English"), "五笔")

        assertEquals(3, target?.id)
    }

    private fun inputMethod(subModeLabel: String) = InputMethodEntry(
        uniqueName = "pinyin",
        name = "Pinyin",
        icon = "",
        nativeName = "",
        label = "拼音",
        languageCode = "zh-CN",
        addon = "pinyin",
        isConfigurable = true,
        subMode = "pinyin",
        subModeLabel = subModeLabel,
        subModeIcon = ""
    )

    private fun action(
        id: Int,
        label: String,
        menu: Array<Action>? = null
    ) = Action(
        id = id,
        isSeparator = false,
        isCheckable = false,
        isChecked = false,
        name = label,
        icon = "",
        shortText = label,
        longText = label,
        menu = menu
    )

    private fun checkedAction(
        id: Int,
        label: String,
        menu: Array<Action>? = null
    ) = Action(
        id = id,
        isSeparator = false,
        isCheckable = true,
        isChecked = true,
        name = label,
        icon = "",
        shortText = label,
        longText = label,
        menu = menu
    )

    private fun separator() = Action(
        id = -1,
        isSeparator = true,
        isCheckable = false,
        isChecked = false,
        name = "",
        icon = "",
        shortText = "",
        longText = "",
        menu = null
    )
}
