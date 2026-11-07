/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.action

import android.app.Activity
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarCustomizeActionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun executingFromANonActivityContextOpensTheButtonsAndIconsCustomizer() {
        val monitor = instrumentation.addMonitor(ButtonsCustomizerActivity::class.java.name, null, false)
        var activity: Activity? = null
        try {
            instrumentation.runOnMainSync {
                ToolbarCustomizeAction.execute(
                    context = instrumentation.targetContext,
                    service = FcitxInputMethodService(),
                    fcitx = UnavailableFcitxConnection,
                    windowManager = InputWindowManager()
                )
            }
            activity = monitor.waitForActivityWithTimeout(5000)
            assertNotNull("The action must launch the buttons and icons customizer", activity)
            assertEquals(ButtonsCustomizerActivity::class.java, requireNotNull(activity).javaClass)
            assertTrue(requireNotNull(activity).intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private object UnavailableFcitxConnection : FcitxConnection {
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T =
            throw AssertionError("Toolbar customization must not require the input engine")

        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T =
            throw AssertionError("Toolbar customization must not wait for the input engine")

        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
            throw AssertionError("Toolbar customization must not schedule input engine work")
        }

        override val lifecycleScope: CoroutineScope
            get() = throw AssertionError("Toolbar customization must launch directly")
    }
}
