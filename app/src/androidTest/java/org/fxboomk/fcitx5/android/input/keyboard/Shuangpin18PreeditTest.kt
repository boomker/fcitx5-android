/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verify the shipped JNI formatter, display option and ordered native syllable confirmations. */
class Shuangpin18PreeditTest {
    @Test
    fun defaultPreeditUsesTheDecodedOrdinaryShuangpinKeys() = withIme(XIAOHE) {
        grouped("nihc")
        awaitPreedit("ni hc")
        assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
        assertEquals("The cursor must use displayed characters", 5, clientPreeditCached.cursor)
    }

    @Test
    fun completePinyinAppearsOnlyWhenItsOptionIsEnabled() = withIme(XIAOHE, completePinyin = true) {
        grouped("nihc")
        awaitPreedit("ni hao")
        assertEquals(6, clientPreeditCached.cursor)
        assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
    }

    @Test
    fun unfinishedSyllablesShowOneOrdinaryKeyInsteadOfTheGroupLabel() = withIme(XIAOHE) {
        grouped("n")
        awaitPreedit("a single source key") { it.length == 1 && it[0] in "bn" }
        grouped("i")
        awaitPreedit("ni")
        grouped("h")
        awaitPreedit("ni h")
        assertFalse("Grouped labels leaked into the composition", clientPreeditCached.toString().contains("bn io"))
    }

    @Test
    fun microsoftSemicolonIsShownAsItsActualSchemeCode() = withIme("shuangpin-ms") {
        grouped("y;")
        awaitPreedit("y;")
        assertEquals("应", getCandidates(0, CANDIDATE_LIMIT).first().text)
    }

    @Test
    fun microsoftCompletePinyinUsesTheSameDisplayOption() =
        withIme("shuangpin-ms", completePinyin = true) {
            grouped("y;")
            awaitPreedit("ying")
            assertEquals("应", getCandidates(0, CANDIDATE_LIMIT).first().text)
        }

    @Test
    fun syllablesAreConfirmedFromTheFirstPairToTheNext() = withIme(XIAOHE) {
        grouped("nihc")
        awaitPreedit("ni hc")
        pickSyllable("bi")
        awaitPreedit("the confirmed first bi syllable") { it.startsWith("bi ") }
        awaitPanel("the second pair's syllables") {
            it.tabs.any { action -> action.text == "hua" } &&
                it.tabs.none { action -> action.text == "bi" || action.text == "ni" }
        }
        assertFalse("The ni candidate survived confirming bi", getCandidates(0, CANDIDATE_LIMIT).any { it.text == "你好" })

        pickSyllable("hua")
        awaitPreedit("bi hx")
        awaitPanel("all syllables confirmed") { it.tabs.none { action -> action.id >= 0 } }
        assertTrue("Confirming both syllables left no candidate", getCandidates(0, CANDIDATE_LIMIT).isNotEmpty())
    }

    @Test
    fun backspaceGivesBackTheLastConfirmedPairFirst() = withIme(XIAOHE) {
        grouped("nihc")
        awaitPreedit("ni hc")
        pickSyllable("bi")
        pickSyllable("hua")
        awaitPreedit("bi hx")

        nextPanel("the last pair given back") { press("BackSpace") }
        awaitPreedit("the first bi confirmation kept") { it.startsWith("bi ") }
        awaitPanel("the second pair offered again") {
            it.tabs.any { action -> action.text == "hua" } &&
                it.tabs.none { action -> action.text == "bi" || action.text == "ni" }
        }
        assertFalse(getCandidates(0, CANDIDATE_LIMIT).any { it.text == "你好" })

        nextPanel("the first pair given back") { press("BackSpace") }
        awaitPreedit("ni hc")
        awaitPanel("the first pair offered again") { it.tabs.any { action -> action.text == "bi" } }
        assertTrue(getCandidates(0, CANDIDATE_LIMIT).any { it.text == "你好" })
    }

    @Test
    fun keysTypedAfterAConfirmationAreDeletedBeforeGivingItBack() = withIme(XIAOHE) {
        grouped("nihc")
        pickSyllable("bi")
        pickSyllable("hua")
        awaitPreedit("bi hx")
        grouped("ni")
        awaitPreedit("a new pair after both confirmations") { it.startsWith("bi hx ") && it.length > 6 }

        nextPanel("the last newly typed key deleted") { press("BackSpace") }
        awaitPreedit("both confirmations kept after deleting one new key") { it.startsWith("bi hx ") }
        nextPanel("the other newly typed key deleted") { press("BackSpace") }
        awaitPreedit("bi hx")
        awaitPanel("both original pairs still confirmed") { it.tabs.none { action -> action.id >= 0 } }

        nextPanel("the last confirmation given back after new keys") { press("BackSpace") }
        awaitPanel("hua offered again") { it.tabs.any { action -> action.text == "hua" } }
    }

    @Test
    fun theSameSyllableCanBeConfirmedForRepeatedPairs() = withIme(XIAOHE) {
        grouped("nini")
        pickSyllable("bi")
        awaitPreedit("bi confirmed only in the first pair") { it.startsWith("bi ") }
        pickSyllable("bi")
        awaitPreedit("bi bi")
        awaitPanel("both repeated pairs confirmed") { it.tabs.none { action -> action.id >= 0 } }

        nextPanel("the second repeated pair given back") { press("BackSpace") }
        awaitPreedit("the first repeated pair still confirmed") { it.startsWith("bi ") }
        awaitPanel("the second bi available again") { it.tabs.any { action -> action.text == "bi" } }
    }

    @Test
    fun aPartialWordLeavesOnlyItsRemainingPairToConfirm() = withIme(XIAOHE) {
        grouped("nihc")
        pickSyllable("ni")
        val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == "你" }
        assertTrue("Partial candidate 你 is missing", index >= 0)
        nextPanel("the partial word kept") {
            assertTrue("Partial selection was rejected", select(index))
        }
        awaitPreedit("你hc")
        pickSyllable("hua")
        awaitPreedit("你hx")
        awaitPanel("the remaining pair confirmed") { it.tabs.none { action -> action.id >= 0 } }
        assertFalse("好 survived confirming hua", getCandidates(0, CANDIDATE_LIMIT).any { it.text == "好" })
        completeWithWord("花", "你花")
    }

    @Test
    fun completePinyinDisplayKeepsTheSameConfirmationOrder() = withIme(XIAOHE, completePinyin = true) {
        grouped("nihc")
        awaitPreedit("ni hao")
        pickSyllable("bi")
        awaitPreedit("the first confirmation in complete pinyin") { it.startsWith("bi ") }
        pickSyllable("hua")
        awaitPreedit("bi hua")
        awaitPanel("complete-pinyin syllables all confirmed") { it.tabs.none { action -> action.id >= 0 } }
    }

    @Test
    fun anInvalidPairOffersItsFirstSingleKeyAndKeepsTheOriginalCode() = withIme(XIAOHE) {
        // QWERTY correction can turn wp into a valid pair. Exercise the
        // parser's single-key fallback with correction disabled for this test.
        val config = getImConfig(XIAOHE)["cfg"].deepCopy().apply {
            getOrCreate("Fuzzy").getOrCreate("Correction").value = "None"
        }
        setImConfig(XIAOHE, config)
        reset()
        mode(true)
        grouped("wp")
        pickSyllable("w")
        awaitPreedit("w p")
        awaitPanel("the second single key after confirming w") {
            it.tabs.any { action -> action.id >= 0 && action.text == "p" } &&
                it.tabs.none { action -> action.text == "w" }
        }
        pickSyllable("p")
        awaitPreedit("w p")
        awaitPanel("both single-key syllables confirmed") { it.tabs.none { action -> action.id >= 0 } }
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            press("Return")
            assertEquals("WE P", commit.await())
        }
    }

    @Test
    fun partiallySelectedWordsPreserveTheExplicitSyllableSeparator() = withIme(XIAOHE) {
        grouped("ni'hc")
        val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == "你" }
        assertTrue("Partial candidate 你 is missing", index >= 0)
        assertTrue("Partial selection was rejected", select(index))
        awaitPreedit("你'hc")
        assertEquals(4, clientPreeditCached.cursor)
        completeWithWord("好", "你好")
    }

    @Test
    fun partiallySelectedWordsKeepTheUnseparatedRemainingShuangpinCode() = withIme(XIAOHE) {
        grouped("nihc")
        val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == "你" }
        assertTrue("Partial candidate 你 is missing", index >= 0)
        assertTrue("Partial selection was rejected", select(index))
        awaitPreedit("你hc")
        assertEquals(3, clientPreeditCached.cursor)
        completeWithWord("好", "你好")
    }

    @Test
    fun editingTheFirstSyllableDoesNotUseExpandedGroupOffsets() = withIme(XIAOHE) {
        grouped("nihc")
        awaitPreedit("ni hc")
        moveCursor(2)
        awaitClient("cursor after the first two keys") { it.cursor == 2 }
        press("BackSpace")
        awaitClient("the deleted first-syllable key") { it.cursor == 1 }
        grouped("i")
        awaitPreedit("ni hc")
        assertEquals("Insertion should remain in the first syllable", 2, clientPreeditCached.cursor)
        moveCursor(5)
        awaitClient("cursor at the complete composition end") { it.cursor == 5 }
        assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
    }

    @Test
    fun rawCommitKeepsItsExistingVisibleGroupFormat() = withIme(XIAOHE) {
        grouped("ni")
        awaitPreedit("ni")
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            press("Return")
            assertEquals("BN IO", commit.await())
        }
    }

    private fun withIme(
        uniqueName: String,
        completePinyin: Boolean = false,
        body: suspend FcitxAPI.() -> Unit,
    ) = runBlocking {
        val clientName = "Shuangpin18PreeditTest-$uniqueName"
        val connection = FcitxDaemon.connect(clientName)
        try {
            withTimeout(READY_TIMEOUT_MS) {
                connection.runOnReady {
                    val originalEnabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
                    val app = InstrumentationRegistry.getInstrumentation().targetContext
                        .createDeviceProtectedStorageContext()
                    val root = app.getExternalFilesDir(null) ?: app.filesDir
                    // Restore complete files, including comments and the absence of a per-IM file.
                    val originalFiles = listOf(
                        File(root, "config/profile"),
                        File(root, "config/conf/pinyin/$uniqueName.conf"),
                    ).associateWith { it.takeIf(File::exists)?.readBytes() }
                    val originalConfig = getImConfig(uniqueName)["cfg"].deepCopy()
                    val names = (listOf("keyboard-us") + originalEnabled + uniqueName).distinct()
                    assertTrue("The installed APK does not expose $uniqueName",
                        availableIme().any { it.uniqueName == uniqueName })
                    try {
                        if (names != originalEnabled) setEnabledIme(names.toTypedArray())
                        activate(TEST_CONTEXT_UID, "shuangpin-preedit-device-test")
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(uniqueName)
                        assertEquals(uniqueName, currentIme().uniqueName)
                        val config = originalConfig.deepCopy().apply {
                            getOrCreate("PinyinInPreedit").value = if (completePinyin) "True" else "False"
                            getOrCreate("PreeditMode").value = "Composing pinyin"
                            getOrCreate("PreeditCursorPositionAtBeginning").value = "False"
                        }
                        setImConfig(uniqueName, config)
                        assertEquals("The complete-pinyin option was not applied",
                            if (completePinyin) "True" else "False",
                            getImConfig(uniqueName)["cfg"]["PinyinInPreedit"].value)
                        reset()
                        mode(true)
                        body()
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                withTimeout(EVENT_TIMEOUT_MS) {
                                    reset()
                                    setImConfig(uniqueName, originalConfig)
                                    if (names != originalEnabled) setEnabledIme(originalEnabled.toTypedArray())
                                    if (originalIme in originalEnabled) activateIme(originalIme)
                                    focus(false)
                                    deactivate(TEST_CONTEXT_UID)
                                }
                            } finally {
                                originalFiles.forEach { (file, bytes) ->
                                    if (bytes != null) file.writeBytes(bytes)
                                    else if (file.exists()) assertTrue("Could not remove ${file.name}", file.delete())
                                }
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
            if (chr == '\'') press("apostrophe")
            else {
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

    private suspend fun FcitxAPI.pickSyllable(text: String) {
        awaitPanel("the pending $text syllable") { it.tabs.any { action -> action.id >= 0 && action.text == text } }
        val action = inputPanelCached.tabs.single { it.id >= 0 && it.text == text }
        assertFalse("A confirmation is not a toggle", action.isCheckable)
        assertFalse("A pending syllable is not already confirmed", action.isChecked)
        nextPanel("the panel after confirming $text") { triggerCandidateListTabAction(action.id) }
    }

    private suspend fun FcitxAPI.nextPanel(description: String, action: suspend FcitxAPI.() -> Unit) = coroutineScope {
        val before = inputPanelCached
        val updated = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(EVENT_TIMEOUT_MS) {
                eventFlow.filterIsInstance<FcitxEvent.InputPanelEvent>().first { it.data !== before }
            }
        }
        action()
        updated.await()
        awaitPanel(description) { it !== before }
    }

    private suspend fun FcitxAPI.completeWithWord(word: String, expected: String) {
        val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == word }
        assertTrue("Remaining candidate '$word' is missing", index >= 0)
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            assertTrue("Remaining candidate selection was rejected", select(index))
            assertEquals(expected, commit.await())
        }
    }

    private suspend fun FcitxAPI.awaitPreedit(expected: String) =
        awaitPreedit("'$expected'") { it == expected }

    private suspend fun FcitxAPI.awaitPreedit(description: String, predicate: (String) -> Boolean) =
        awaitClient(description) { predicate(it.toString()) }

    private suspend fun FcitxAPI.awaitClient(
        description: String,
        predicate: (org.fxboomk.fcitx5.android.core.FormattedText) -> Boolean,
    ) {
        val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
            while (!predicate(clientPreeditCached)) delay(10)
            true
        }
        assertTrue("Expected $description, observed '${clientPreeditCached}' at cursor ${clientPreeditCached.cursor}",
            matched == true)
        report("scheme=${currentIme().uniqueName}; preedit=${clientPreeditCached}; " +
            "cursor=${clientPreeditCached.cursor}; expected=$description")
    }

    private suspend fun FcitxAPI.awaitPanel(
        description: String,
        predicate: (FcitxEvent.InputPanelEvent.Data) -> Boolean,
    ) {
        val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
            while (!predicate(inputPanelCached)) delay(10)
            true
        }
        assertTrue("Expected $description, observed tabs ${inputPanelCached.tabs.toList()}", matched == true)
        report("panel=$description; checked=${inputPanelCached.tabs.filter { it.isChecked }.map { it.text }}")
    }

    private fun RawConfig.deepCopy(): RawConfig = copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\n[Shuangpin18PreeditTest] $message\n")
        })
    }

    private companion object {
        const val XIAOHE = "shuangpin-xiaohe"
        const val TEST_CONTEXT_UID = -180021
        const val READY_TIMEOUT_MS = 30_000L
        const val EVENT_TIMEOUT_MS = 10_000L
        const val CANDIDATE_LIMIT = 256
        val GROUPS = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd",
            "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")
    }
}
