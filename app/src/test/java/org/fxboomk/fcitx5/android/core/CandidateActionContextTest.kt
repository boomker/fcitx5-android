/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateActionContextTest {
    @Test
    fun `legacy calls default unknown while visible and offpage context survive dispatch`() = runBlocking {
        val received = mutableListOf<Boolean?>()
        val api = Proxy.newProxyInstance(
            FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java),
        ) { _, method, args ->
            check(method.name == "triggerCandidateAction")
            received.add(args[2] as Boolean?)
            Unit
        } as FcitxAPI
        api.triggerCandidateAction(4, 0)
        api.triggerCandidateAction(4, 0, false)
        api.triggerCandidateAction(4, 0, true)
        assertEquals(listOf(null, false, true), received)
    }
}
