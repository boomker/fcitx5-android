/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateDigitSelectionTest {
    @Test
    fun `out of range selection is consumed but an empty candidate list keeps digit input`() = runBlocking {
        var candidates = arrayOf(CandidateWord("", "词", "", false))
        val selected = mutableListOf<Int>()
        val api = Proxy.newProxyInstance(
            FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getCandidates" -> candidates
                "select" -> {
                    selected += args[0] as Int
                    false
                }
                else -> error("Unexpected Fcitx call: ${method.name}")
            }
        } as FcitxAPI

        assertTrue(api.selectCandidateForDigit(KeyAction.CommitAction("0")))
        assertEquals(listOf(9), selected)
        candidates = emptyArray()
        assertFalse(api.selectCandidateForDigit(KeyAction.CommitAction("2")))
        assertEquals("No candidates must leave normal input to the caller", listOf(9), selected)
    }

    @Test
    fun `all soft digit representations use the same visible candidate index`() {
        ('0'..'9').forEach { digit ->
            val index = if (digit == '0') 9 else digit - '1'
            listOf(
                KeyAction.FcitxKeyAction(digit.toString()),
                KeyAction.FcitxKeyAction("KP_$digit"),
                KeyAction.SymAction(KeySym(digit.code)),
                KeyAction.SymAction(
                    KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (digit - '0')), NumLockState
                ),
                KeyAction.CommitAction(digit.toString()),
                KeyAction.CommitAction("\u0000$digit"),
            ).forEach { action ->
                assertEquals("Digit action $action", index, candidateSelectionIndex(action))
            }
        }
    }

    @Test
    fun `command modifiers and key releases keep their existing dispatch`() {
        listOf(
            KeyState.Shift, KeyState.Ctrl, KeyState.Alt, KeyState.Super,
            KeyState.Super2, KeyState.Hyper, KeyState.Meta,
        ).forEach { modifier ->
            val states = KeyStates(modifier, KeyState.Virtual)
            assertNull(candidateSelectionIndex(KeyAction.FcitxKeyAction("2", states = states)))
            assertNull(candidateSelectionIndex(KeyAction.SymAction(KeySym('2'.code), states)))
        }
        assertNull(candidateSelectionIndex(KeyAction.FcitxKeyAction("2", up = true)))
    }

    @Test
    fun `caps and num lock do not prevent soft digit selection`() {
        val states = KeyStates(KeyState.CapsLock, KeyState.NumLock, KeyState.Virtual)
        assertEquals(1, candidateSelectionIndex(KeyAction.FcitxKeyAction("2", states = states)))
        assertEquals(1, candidateSelectionIndex(KeyAction.SymAction(KeySym('2'.code), states)))
    }

    @Test
    fun `text symbols and non ASCII digits are not candidate shortcuts`() {
        listOf("", "12", "a", "２", "٢", "\u00002x", "\u0000", "Ctrl+2", "KP_12").forEach { text ->
            assertNull("Text $text", candidateSelectionIndex(KeyAction.CommitAction(text)))
            if (text.isNotEmpty()) {
                assertNull("Key $text", candidateSelectionIndex(KeyAction.FcitxKeyAction(text)))
            }
        }
        assertNull(candidateSelectionIndex(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))))
        assertNull(candidateSelectionIndex(KeyAction.QuickPhraseAction))
    }
}
