/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import org.junit.Assert.assertEquals
import org.junit.Test

class PreeditSyllableCountTest {
    @Test
    fun countsCompleteAndUnfinishedSyllables() {
        mapOf("" to 0, "ni" to 1, "ni hc" to 2, "ni hc n" to 3,
            "ni hc ma" to 3, "ni hao ma" to 3, "w p" to 2,
            "y; lü n:" to 3).forEach { (text, expected) ->
            assertEquals(text, expected, preeditSyllableCount(text))
        }
    }

    @Test
    fun separatorsDoNotAddPhantomSyllables() {
        listOf("ni'hc'ma", "  ni  hc  ma  ", "ni''hc' ma'").forEach {
            assertEquals(it, 3, preeditSyllableCount(it))
        }
        assertEquals(0, preeditSyllableCount(" ' "))
        assertEquals(2, preeditSyllableCount("ni hc'"))
    }

    @Test
    fun selectedWordsKeepTheirSyllablesEvenWithoutASeparatorBeforeTheRemainingCode() {
        mapOf("你hc" to 2, "你'hc" to 2, "你hc ma" to 3, "你好ma" to 3,
            "𠮷好ma" to 3).forEach { (text, expected) ->
            assertEquals(text, expected, preeditSyllableCount(text))
        }
    }
}
