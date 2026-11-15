/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinReturnCandidateTest {
    @Test
    fun `both enter keys synchronize composing pinyin candidates`() {
        for (sym in listOf(FcitxKeyMapping.FcitxKey_Return, FcitxKeyMapping.FcitxKey_KP_Enter)) {
            assertTrue(shouldSyncPinyinCandidateForReturn(sym, "pinyin", hasPreedit = true, up = false))
        }
    }

    @Test
    fun `prediction candidates do not navigate before enter`() {
        assertFalse(shouldSyncPinyinCandidateForReturn(
            FcitxKeyMapping.FcitxKey_Return, "pinyin", hasPreedit = false, up = false,
        ))
    }

    @Test
    fun `other engines retain their enter handling`() {
        for (addon in listOf("rime", "table", "keyboard")) {
            assertFalse(shouldSyncPinyinCandidateForReturn(
                FcitxKeyMapping.FcitxKey_Return, addon, hasPreedit = true, up = false,
            ))
        }
    }

    @Test
    fun `key release does not repeat candidate navigation`() {
        assertFalse(shouldSyncPinyinCandidateForReturn(
            FcitxKeyMapping.FcitxKey_Return, "pinyin", hasPreedit = true, up = true,
        ))
    }

    @Test
    fun `typing and candidate selection keys leave local highlight browsing alone`() {
        for (sym in listOf('n'.code, FcitxKeyMapping.FcitxKey_Tab, FcitxKeyMapping.FcitxKey_space)) {
            assertFalse(shouldSyncPinyinCandidateForReturn(sym, "pinyin", hasPreedit = true, up = false))
        }
    }
}
