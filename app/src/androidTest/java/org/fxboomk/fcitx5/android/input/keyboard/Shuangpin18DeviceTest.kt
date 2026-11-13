/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.util.Log
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the shipped arm64 engine and JNI against an explicitly focused test context. */
class Shuangpin18DeviceTest {
    @Test
    fun xiaoheGroupsCommitWholePhrases() = withIme("shuangpin-xiaohe") {
        grouped("nihc")
        selectWord("你好")
        reset()
        grouped("vsgo")
        selectWord("中国")
    }

    @Test
    fun microsoftLGroupIncludesSemicolon() = withIme("shuangpin-ms") {
        grouped("y;")
        selectWord("应")
        reset()
        grouped("nihk")
        selectWord("你好")
    }

    @Test
    fun ziranmaGroupsUseTheSelectedScheme() = withIme("shuangpin-ziranma") {
        grouped("nihk")
        selectWord("你好")
    }

    @Test
    fun backspaceReplacesTheLastGroupedKey() = withIme("shuangpin-xiaohe") {
        grouped("nihv")
        press("BackSpace")
        grouped("c")
        selectWord("你好")
    }

    @Test
    fun separatorAllowsPartialWordSelection() = withIme("shuangpin-xiaohe") {
        grouped("ni")
        val separator = requireNotNull(Shuangpin18Keys.layout(false, false)[2].first().composeOverride)
        val action = separator.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
            as KeyAction.SymAction
        sendKey(action.sym, action.states)
        grouped("hc")
        val index = candidateIndex("你")
        assertTrue("Partial selection was rejected", select(index))
        selectWord("好", "你好")
    }

    @Test
    fun rawCommitContainsVisibleGroups() = withIme("shuangpin-xiaohe") {
        grouped("ni")
        expectCommit("BN IO") { press("Return") }
    }

    @Test
    fun modeNotificationKeepsCompositionAndRestoresOrdinaryShuangpin() = withIme("shuangpin-xiaohe") {
        grouped("nihc")
        val before = getCandidates(0, CANDIDATE_LIMIT).map { it.text }
        mode(true)
        assertEquals("Repeated mode notification changed candidates", before,
            getCandidates(0, CANDIDATE_LIMIT).map { it.text })
        val defaultWord = before.first()
        expectCommit(defaultWord) { mode(false) }
        press("n")
        press("i")
        expectCommit("ni") { press("Return") }
    }

    @Test
    fun quickPhraseCannotCommitGroupedProtocolKeys() = withIme("shuangpin-xiaohe") {
        triggerQuickPhrase()
        press("x")
        grouped("n")
        expectCommit("x") { press("Return") }
    }

    @Test
    fun regroupedLettersDecodeAndCommitTheirConfiguredLabels() = withIme("shuangpin-xiaohe") {
        val groups = GROUPS.map { when (it) { "q" -> "e"; "we" -> "wq"; else -> it } }
        configure(groups)
        customGrouped("wo", groups)
        selectWord("我")
        reset()
        customGrouped("wo", groups)
        expectCommit("WQ IO") { press("Return") }
    }

    @Test
    fun splitAndMergedGroupsDecodeWholePhrases() = withIme("shuangpin-xiaohe") {
        val split = "abcdefghijklmnopqrstuvwxyz;".map(Char::toString)
        configure(split)
        customGrouped("nihc", split)
        selectWord("你好")
        reset()
        customGrouped("ni", split)
        expectCommit("N I") { press("Return") }

        val merged = GROUPS.filter { it != "m" }.map { if (it == "bn") "bnm" else it }
        configure(merged)
        customGrouped("nihc", merged)
        selectWord("你好")
        reset()
        customGrouped("ni", merged)
        expectCommit("BNM IO") { press("Return") }
    }

    @Test
    fun customMicrosoftSemicolonAndCapsModeRestore() = withIme("shuangpin-ms") {
        val groups = GROUPS.map { when (it) { "l;" -> "l"; "y" -> "y;"; else -> it } }
        configure(groups)
        customGrouped("y;", groups)
        selectWord("应")
        reset()
        mode(false)
        press("n")
        press("i")
        expectCommit("ni") { press("Return") }
        mode(true)
        customGrouped("y;", groups)
        selectWord("应")
    }

    @Test
    fun reorderingGroupsKeepsCompositionAndChangingGroupsFinishesIt() = withIme("shuangpin-xiaohe") {
        val groups = GROUPS.map { when (it) { "q" -> "e"; "we" -> "wq"; else -> it } }
        configure(groups)
        customGrouped("nihc", groups)
        val before = getCandidates(0, CANDIDATE_LIMIT).map { it.text }
        configure(groups.reversed())
        assertEquals(before, getCandidates(0, CANDIDATE_LIMIT).map { it.text })
        expectCommit(before.first()) { configure(GROUPS) }
        customGrouped("nihc", GROUPS)
        selectWord("你好")
    }

    @Test
    fun configurationDuringQuickPhraseAppliesAfterItCloses() = withIme("shuangpin-xiaohe") {
        val groups = GROUPS.map { when (it) { "q" -> "e"; "we" -> "wq"; else -> it } }
        configure(groups)
        mode(false)
        triggerQuickPhrase()
        press("x")
        configure(GROUPS)
        expectCommit("x") { press("Return") }
        customGrouped("wo", GROUPS)
        expectCommit("WE IO") { press("Return") }
    }

    private suspend fun FcitxAPI.configure(groups: List<String>) {
        Shuangpin18Keys.configurationActions(groups).forEach { sendAction(it) }
        mode(true)
    }

    private suspend fun FcitxAPI.customGrouped(input: String, groups: List<String>) {
        input.forEach { chr -> sendAction(Shuangpin18Keys.action(groups.single { chr in it })) }
    }

    private suspend fun FcitxAPI.sendAction(action: KeyAction.SymAction) {
        sendKey(action.sym, action.states)
        sendKey(action.sym, action.states, up = true)
    }

    private fun withIme(uniqueName: String, body: suspend FcitxAPI.() -> Unit) = runBlocking {
        val clientName = "Shuangpin18DeviceTest-$uniqueName"
        val connection = FcitxDaemon.connect(clientName)
        try {
            withTimeout(READY_TIMEOUT_MS) {
                connection.runOnReady {
                    val originalEnabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                        .createDeviceProtectedStorageContext()
                    val root = context.getExternalFilesDir(null) ?: context.filesDir
                    val profileFile = File(root, "config/profile")
                    val originalProfile = profileFile.takeIf(File::exists)?.readBytes()
                    val names = (listOf("keyboard-us") + originalEnabled + uniqueName).distinct()
                    assertTrue("The installed APK does not expose $uniqueName",
                        availableIme().any { it.uniqueName == uniqueName })
                    try {
                        if (names != originalEnabled) setEnabledIme(names.toTypedArray())
                        activate(TEST_CONTEXT_UID, "shuangpin-18-device-test")
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(uniqueName)
                        assertEquals("The test input method was not activated", uniqueName,
                            currentIme().uniqueName)
                        reset()
                        mode(true)
                        body()
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                withTimeout(EVENT_TIMEOUT_MS) {
                                    reset()
                                    if (names != originalEnabled) setEnabledIme(originalEnabled.toTypedArray())
                                    if (originalIme in originalEnabled) activateIme(originalIme)
                                    focus(false)
                                    deactivate(TEST_CONTEXT_UID)
                                }
                            } finally {
                                // Preserve exact group layout/defaults even if native teardown fails.
                                if (originalProfile != null) profileFile.writeBytes(originalProfile)
                                else if (profileFile.exists()) assertTrue(profileFile.delete())
                            }
                        }
                    }
                }
            }
        } finally {
            FcitxDaemon.disconnect(clientName)
        }
    }

    private suspend fun FcitxAPI.grouped(input: String) {
        for (chr in input) {
            if (chr == '\'') {
                press("apostrophe")
            } else {
                val index = GROUPS.indexOfFirst { chr in it }
                require(index >= 0)
                val sym = KeySym(0x0200E000 + index)
                sendKey(sym, KeyStates.Virtual)
                sendKey(sym, KeyStates.Virtual, up = true)
            }
        }
    }

    private suspend fun FcitxAPI.mode(compact: Boolean) {
        val sym = KeySym(if (compact) 0x0200E101 else 0x0200E100)
        sendKey(sym, KeyStates.Virtual)
        sendKey(sym, KeyStates.Virtual, up = true)
    }

    private suspend fun FcitxAPI.press(key: String) {
        sendKey(key, states = KeyStates.Virtual.states)
        sendKey(key, states = KeyStates.Virtual.states, up = true)
    }

    private suspend fun FcitxAPI.candidateIndex(word: String): Int {
        val candidates = getCandidates(0, CANDIDATE_LIMIT)
        val index = candidates.indexOfFirst { it.text == word }
        report("scheme=${currentIme().uniqueName}; expected=$word; " +
            "first10=${candidates.take(10).map { it.text }}; index=$index; sample=${candidates.size}")
        assertTrue("Missing '$word' among ${candidates.map { it.text }}", index >= 0)
        return index
    }

    private suspend fun FcitxAPI.selectWord(word: String, committed: String = word) {
        val index = candidateIndex(word)
        expectCommit(committed) { assertTrue("Candidate selection was rejected", select(index)) }
    }

    private suspend fun FcitxAPI.expectCommit(expected: String, action: suspend FcitxAPI.() -> Unit) {
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            action()
            assertEquals("Native commit differs", expected, commit.await())
        }
    }

    private fun report(message: String) {
        Log.i("Shuangpin18DeviceTest", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\n[Shuangpin18DeviceTest] $message\n")
        })
    }

    private companion object {
        const val TEST_CONTEXT_UID = -180011
        const val READY_TIMEOUT_MS = 30_000L
        const val EVENT_TIMEOUT_MS = 10_000L
        const val CANDIDATE_LIMIT = 256
        val GROUPS = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd",
            "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")
    }
}
