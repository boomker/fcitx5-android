/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputContextDeactivationTest {
    @Test
    fun deactivatingAnInactiveContextKeepsTheActiveContextUsable() = withPinyin {
        deactivate(OTHER_UID)
        typeNihao()
        val ime = currentIme().uniqueName
        val preedit = clientPreeditCached.toString()
        val panelPreedit = inputPanelCached.preedit.toString()
        val candidates = getCandidates(0, 128).map { it.text }
        val state = "currentIme=$ime; preedit=[$preedit]; panelPreedit=[$panelPreedit]; " +
            "firstCandidates=${candidates.take(12)}"
        assertEquals("Deactivating an inactive context must keep pinyin: $state", PINYIN, ime)
        assertTrue("The active context must still produce preedit: $state",
            preedit.isNotEmpty() || panelPreedit.isNotEmpty())
        assertTrue("The active context must still offer 你好: $state", "你好" in candidates)
    }

    @Test
    fun deactivatingTheActiveContextStillReleasesIt() = withPinyin {
        deactivate(TEST_UID)
        try {
            typeNihao()
            val candidates = getCandidates(0, 128).map { it.text }
            assertTrue("Keys without an active context must be ignored; " +
                "currentIme=${currentIme().uniqueName}; preedit=[$clientPreeditCached]; " +
                "firstCandidates=${candidates.take(12)}", candidates.isEmpty())
        } finally {
            withContext(NonCancellable) { activate(TEST_UID, CLIENT_NAME) }
        }
    }

    private suspend fun FcitxAPI.typeNihao() {
        for (key in "nihao") {
            sendKey(key.toString(), states = KeyStates.Virtual.states)
            sendKey(key.toString(), states = KeyStates.Virtual.states, up = true)
        }
    }

    private fun withPinyin(body: suspend FcitxAPI.() -> Unit) = runBlocking {
        val connection = FcitxDaemon.connect(CLIENT_NAME)
        try {
            withTimeout(30_000) {
                connection.runOnReady {
                    val originalEnabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
                    val storage = InstrumentationRegistry.getInstrumentation().targetContext
                        .createDeviceProtectedStorageContext()
                    val root = storage.getExternalFilesDir(null) ?: storage.filesDir
                    val profile = File(root, "config/profile")
                    val originalProfile = profile.takeIf(File::exists)?.readBytes()
                    val enabled = (listOf("keyboard-us") + originalEnabled + PINYIN).distinct()
                    var otherActivated = false
                    var testActivated = false
                    try {
                        if (enabled != originalEnabled) setEnabledIme(enabled.toTypedArray())
                        activate(OTHER_UID, CLIENT_NAME)
                        otherActivated = true
                        activate(TEST_UID, CLIENT_NAME)
                        testActivated = true
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(PINYIN)
                        assertEquals("Setup must activate pinyin", PINYIN, currentIme().uniqueName)
                        reset()
                        body()
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                try {
                                    // Old-code failures leave no active context; recover it for cleanup.
                                    if (testActivated) {
                                        activate(TEST_UID, CLIENT_NAME)
                                        reset()
                                    }
                                } finally {
                                    try {
                                        if (enabled != originalEnabled) {
                                            setEnabledIme(originalEnabled.toTypedArray())
                                        }
                                        if (originalIme in originalEnabled) activateIme(originalIme)
                                    } finally {
                                        try {
                                            if (testActivated) releaseContext(TEST_UID)
                                        } finally {
                                            if (otherActivated) releaseContext(OTHER_UID)
                                        }
                                    }
                                }
                            } finally {
                                if (originalProfile != null) profile.writeBytes(originalProfile)
                                else if (profile.exists()) assertTrue(profile.delete())
                                reloadConfig()
                            }
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { FcitxDaemon.disconnect(CLIENT_NAME) }
        }
    }

    private suspend fun FcitxAPI.releaseContext(uid: Int) {
        try {
            activate(uid, CLIENT_NAME)
            focus(false)
        } finally {
            deactivate(uid)
        }
    }

    private companion object {
        const val CLIENT_NAME = "InputContextDeactivationTest"
        const val OTHER_UID = -310101
        const val TEST_UID = -310102
        const val PINYIN = "pinyin"
    }
}
