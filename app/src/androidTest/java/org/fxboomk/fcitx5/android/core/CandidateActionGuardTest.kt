/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction
import org.fxboomk.fcitx5.android.input.keyboard.NumLockState
import org.fxboomk.fcitx5.android.input.keyboard.selectCandidateForDigit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the shipped pinyin engine and checked JNI calls in a sensitive test context. */
class CandidateActionGuardTest {
    @Test
    fun softDigitActionsSelectTheVisibleEmojiHeadRatherThanItsFoldedAlternative() = withPinyin {
        digitActionFactories.forEach { actionFor ->
            val choice = foldedChoice("kaishi", "▶️", "🔛")
            assertTrue("The folded head must have a numeric selection key", choice.index in 0..8)
            val digit = '1' + choice.index
            expectCommit(choice.head.text) {
                assertTrue(selectCandidateForDigit(actionFor(digit)))
            }
            assertTrue("Selecting the visible emoji must finish composition", isEmpty())
        }
    }

    @Test
    fun foldedAlternativesDoNotConsumeTheFollowingVisibleCandidateNumber() = withPinyin {
        digitActionFactories.forEach { actionFor ->
            val choice = foldedChoice("kaishi", "▶️", "🔛")
            val index = choice.index + 1
            assertTrue("The following visible candidate must have a numeric selection key", index in 0..8)
            val following = getCandidates(index, 1).single()
            assertFalse("The folded alternative must not occupy a visible slot", following.text == choice.action.text)
            expectCommit(following.text) {
                assertTrue(selectCandidateForDigit(actionFor('1' + index)))
            }
        }
    }

    @Test
    fun zeroSelectsTheTenthVisibleNativeCandidateForEverySoftDigitRepresentation() = withPinyin {
        digitActionFactories.forEach { actionFor ->
            typePinyin("shi")
            val candidates = getCandidates(0, 10)
            assertEquals("The single-syllable fixture must expose ten candidates", 10, candidates.size)
            expectCommit(candidates[9].text) {
                assertTrue(selectCandidateForDigit(actionFor('0')))
            }
            // Prediction candidates may remain after a complete commit. Only
            // the two preedits describe whether the input syllable is unfinished.
            withTimeout(EVENT_TIMEOUT_MS) {
                while (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                    delay(10)
                }
            }
            assertTrue("Selecting a complete syllable must finish composition",
                clientPreeditCached.isEmpty() && inputPanelCached.preedit.isEmpty())
        }
    }

    @Test
    fun softDigitsWithoutNativeCandidatesKeepTheirNormalInputPath() = withPinyin {
        reset()
        assertTrue(getCandidates(0, 1).isEmpty())
        assertNoCommit {
            digitActionFactories.forEach { actionFor ->
                assertFalse("No native candidate means the caller must input the digit normally",
                    selectCandidateForDigit(actionFor('3')))
            }
        }
    }

    @Test
    fun modifiedDigitsAndKeyReleasesCannotSelectANativeCandidate() = withPinyin {
        foldedChoice("kaishi", "▶️", "🔛")
        val before = getCandidates(0, 10).toList()
        val modifiers = listOf(KeyState.Shift, KeyState.Ctrl, KeyState.Alt, KeyState.Meta)
        assertNoCommit {
            modifiers.forEach { modifier ->
                val states = KeyStates(KeyState.Virtual, modifier)
                assertFalse(selectCandidateForDigit(KeyAction.FcitxKeyAction("2", states = states)))
                assertFalse(selectCandidateForDigit(KeyAction.SymAction(
                    KeySym(FcitxKeyMapping.FcitxKey_KP_2),
                    KeyStates(KeyState.Virtual, KeyState.NumLock, modifier)
                )))
            }
            assertFalse(selectCandidateForDigit(KeyAction.FcitxKeyAction("2", up = true)))
        }
        assertEquals("Rejected numeric actions must preserve the displayed list", before, getCandidates(0, 10).toList())
        assertFalse("Rejected numeric actions must preserve composition", isEmpty())
    }

    @Test
    fun supplementaryActionTextPassesTheRealNativeGuardAndCommits() = withPinyin {
        val choice = foldedChoice("kaishi", "▶️", "🔛")
        assertTrue(Character.isSupplementaryCodePoint(choice.action.text.codePointAt(0)))
        expectCommit("🔛") {
            assertTrue(triggerCandidateActionIfCurrent(
                choice.index, choice.head.text, choice.action.id,
                choice.action.text, choice.action.icon
            ))
        }
        assertTrue("Selecting the complete emoji should finish composition", isEmpty())
    }

    @Test
    fun supplementaryHeadAndActionBothRoundTripThroughTheGuard() = withPinyin {
        val choice = foldedChoice("kaixin", "😄", "😆")
        assertTrue(Character.isSupplementaryCodePoint(choice.head.text.codePointAt(0)))
        assertTrue(Character.isSupplementaryCodePoint(choice.action.text.codePointAt(0)))
        expectCommit("😆") {
            assertTrue(triggerCandidateActionIfCurrent(
                choice.index, choice.head.text, choice.action.id,
                choice.action.text, choice.action.icon
            ))
        }
    }

    @Test
    fun wrongHeadActionTextIconAndIdCannotCommit() = withPinyin {
        val choice = foldedChoice("kaishi", "▶️", "🔛")
        val displayedWord = getCandidates(0, 1).single().text
        // Use the displayed text, including any currently enabled traditional conversion.
        assertNotNull(getCandidateActionsIfCurrent(0, displayedWord))
        assertNull(getCandidateActionsIfCurrent(choice.index, "👩‍💻"))
        assertNoCommit {
            assertFalse(triggerCandidateActionIfCurrent(
                choice.index, "👩‍💻", choice.action.id,
                choice.action.text, choice.action.icon
            ))
            assertFalse(triggerCandidateActionIfCurrent(
                choice.index, choice.head.text, choice.action.id,
                "🈳", choice.action.icon
            ))
            assertFalse(triggerCandidateActionIfCurrent(
                choice.index, choice.head.text, choice.action.id,
                choice.action.text, "different-symbol-icon"
            ))
            assertFalse(triggerCandidateActionIfCurrent(
                choice.index, choice.head.text, Int.MIN_VALUE,
                choice.action.text, choice.action.icon
            ))
        }
        assertFalse("Refused actions must preserve composition", isEmpty())
        assertNotNull(getCandidateActionsIfCurrent(choice.index, choice.head.text))
    }

    @Test
    fun pageChangeChecksTheCurrentPageHeadRatherThanTheGlobalBulkIndex() = withPinyin {
        val choice = foldedChoice("kaishi", "▶️", "🔛")
        assertTrue("The fixture must have a second page",
            getCandidates(0, CANDIDATE_LIMIT).size > MAX_PAGE_SIZE)
        assertTrue("The head must be a page-local action index", choice.index < MIN_PAGE_SIZE)
        offsetCandidatePage(1)
        try {
            // The bulk getter still sees the original global entry. The action
            // getter must instead resolve this index on the new current page.
            assertEquals(choice.head.text, getCandidates(choice.index, 1).single().text)
            assertNull(getCandidateActionsIfCurrent(choice.index, choice.head.text))
            assertNoCommit {
                assertFalse(triggerCandidateActionIfCurrent(
                    choice.index, choice.head.text, choice.action.id,
                    choice.action.text, choice.action.icon
                ))
            }
            assertFalse(isEmpty())
        } finally {
            offsetCandidatePage(-1)
        }
        assertNotNull(getCandidateActionsIfCurrent(choice.index, choice.head.text))
        expectCommit("🔛") {
            assertTrue(triggerCandidateActionIfCurrent(
                choice.index, choice.head.text, choice.action.id,
                choice.action.text, choice.action.icon
            ))
        }
    }

    private data class FoldedChoice(
        val index: Int,
        val head: CandidateWord,
        val action: CandidateAction
    )

    private suspend fun FcitxAPI.typePinyin(input: String) {
        reset()
        input.forEach { key ->
            sendKey(key.toString(), states = KeyStates.Virtual.states)
            sendKey(key.toString(), states = KeyStates.Virtual.states, up = true)
        }
    }

    private suspend fun FcitxAPI.foldedChoice(
        input: String,
        headText: String,
        actionText: String
    ): FoldedChoice {
        reset()
        input.forEach { key ->
            sendKey(key.toString(), states = KeyStates.Virtual.states)
            sendKey(key.toString(), states = KeyStates.Virtual.states, up = true)
        }
        val candidates = getCandidates(0, CANDIDATE_LIMIT)
        val index = candidates.indexOfFirst { it.text == headText }
        assertTrue("Missing $headText for $input; respect the current SymbolsEnabled setting: " +
            candidates.take(12).map { it.text }, index >= 0)
        val head = candidates[index]
        assertEquals("The front-page run must be folded", "…", head.comment)
        val actions = requireNotNull(getCandidateActionsIfCurrent(index, head.text))
        val action = actions.firstOrNull { it.text == actionText && it.icon == FOLDED_SYMBOL_ICON }
        assertNotNull("Missing folded alternative $actionText for $input", action)
        return FoldedChoice(index, head, requireNotNull(action))
    }

    private suspend fun FcitxAPI.expectCommit(
        expected: String,
        action: suspend FcitxAPI.() -> Unit
    ) = coroutineScope {
        val commit = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(EVENT_TIMEOUT_MS) {
                eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
            }
        }
        action()
        assertEquals("The real native commit differs", expected, commit.await())
    }

    private suspend fun FcitxAPI.assertNoCommit(action: suspend FcitxAPI.() -> Unit) = coroutineScope {
        val commits = Channel<String>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().collect {
                commits.send(it.data.text)
            }
        }
        try {
            action()
            assertNull("A rejected action emitted a commit",
                withTimeoutOrNull(NO_COMMIT_WINDOW_MS) { commits.receive() })
        } finally {
            collector.cancel()
            commits.close()
        }
    }

    private fun withPinyin(body: suspend FcitxAPI.() -> Unit) = runBlocking {
        val connection = FcitxDaemon.connect(CLIENT_NAME)
        try {
            withTimeout(READY_TIMEOUT_MS) {
                connection.runOnReady {
                    val originalEnabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
                    assertTrue("The installed APK does not expose full pinyin",
                        availableIme().any { it.uniqueName == PINYIN })
                    val storage = InstrumentationRegistry.getInstrumentation().targetContext
                        .createDeviceProtectedStorageContext()
                    val root = storage.getExternalFilesDir(null) ?: storage.filesDir
                    val profileFile = File(root, "config/profile")
                    val originalProfile = profileFile.takeIf(File::exists)?.readBytes()
                    val enabled = (listOf("keyboard-us") + originalEnabled + PINYIN).distinct()
                    var activated = false
                    try {
                        if (enabled != originalEnabled) setEnabledIme(enabled.toTypedArray())
                        activate(TEST_CONTEXT_UID, "candidate-action-guard-test")
                        activated = true
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(PINYIN)
                        assertEquals(PINYIN, currentIme().uniqueName)
                        reset()
                        body()
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                withTimeout(EVENT_TIMEOUT_MS) {
                                    try {
                                        if (activated) reset()
                                    } finally {
                                        try {
                                            if (enabled != originalEnabled) {
                                                setEnabledIme(originalEnabled.toTypedArray())
                                            }
                                            if (originalIme in originalEnabled) activateIme(originalIme)
                                        } finally {
                                            if (activated) {
                                                focus(false)
                                                deactivate(TEST_CONTEXT_UID)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                // Group activation can save profile defaults. Keep exact bytes,
                                // including the absence of a profile file before the test.
                                if (originalProfile != null) profileFile.writeBytes(originalProfile)
                                else if (profileFile.exists()) assertTrue(profileFile.delete())
                            }
                        }
                    }
                }
            }
        } finally {
            FcitxDaemon.disconnect(CLIENT_NAME)
        }
    }

    private companion object {
        val digitActionFactories: List<(Char) -> KeyAction> = listOf(
            { KeyAction.FcitxKeyAction(it.toString()) },
            { KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + (it - '0')), NumLockState) },
            { KeyAction.CommitAction(it.toString()) },
            { KeyAction.CommitAction("\u0000$it") },
        )

        const val CLIENT_NAME = "CandidateActionGuardTest"
        const val TEST_CONTEXT_UID = -180117
        const val PINYIN = "pinyin"
        const val FOLDED_SYMBOL_ICON = "folded-symbol"
        const val READY_TIMEOUT_MS = 30_000L
        const val EVENT_TIMEOUT_MS = 10_000L
        const val NO_COMMIT_WINDOW_MS = 250L
        const val CANDIDATE_LIMIT = 128
        const val MIN_PAGE_SIZE = 3
        const val MAX_PAGE_SIZE = 10
    }
}
