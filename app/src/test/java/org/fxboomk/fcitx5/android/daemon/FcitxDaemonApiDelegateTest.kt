/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.daemon

import java.lang.reflect.Modifier
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.junit.Assert.assertTrue
import org.junit.Test

class FcitxDaemonApiDelegateTest {
    @Test
    fun daemonDelegateImplementsEveryApiMethod() {
        // Inspect the compiled delegate without starting Android or the native engine.
        // Incremental Kotlin builds can otherwise keep an old `FcitxAPI by realFcitx`
        // implementation after the interface gains a new method.
        val delegate = Class.forName(
            "org.fxboomk.fcitx5.android.daemon.FcitxDaemon\$fcitxImpl\$2\$1",
            false,
            javaClass.classLoader,
        )
        val forwards = delegate.declaredMethods
        val missing = FcitxAPI::class.java.methods.filter { method ->
            !Modifier.isStatic(method.modifiers) && forwards.none {
                it.name == method.name && it.parameterTypes.contentEquals(method.parameterTypes)
            }
        }
        // Inheriting a default API body can silently bypass the real engine.
        assertTrue("Daemon has no declared forwarding methods: $missing", missing.isEmpty())
    }
}
