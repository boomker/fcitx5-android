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
    fun symbolFoldingCanBeSwitchedOffAndPersistsAcrossReloads() = withPinyin {
        val option = getImConfig(PINYIN)["desc"].findRecursively("FoldSymbolCandidates")
        assertNotNull("The symbol-folding switch must be visible", option)
        assertEquals("False", option!!["DefaultValue"].value)
        for (enabled in listOf(false, true, false)) {
            reset()
            val value = if (enabled) "True" else "False"
            setImConfig(PINYIN, RawConfig().apply {
                getOrCreate("FoldSymbolCandidates").value = value
            })
            reloadConfig()
            assertEquals(value, getImConfig(PINYIN)["cfg"]["FoldSymbolCandidates"].value)
            if (enabled) {
                foldedChoice("kaishi", "▶️", "🔛")
            } else {
                typePinyin("kaishi")
                val candidates = getCandidates(0, CANDIDATE_LIMIT)
                val headIndex = candidates.indexOfFirst { it.text == "▶️" }
                val alternativeIndex = candidates.indexOfFirst { it.text == "🔛" }
                assertTrue("The emoji head must remain visible", headIndex >= 0)
                assertTrue("Unfolded emoji must occupy their own visible slots",
                    alternativeIndex == headIndex + 1)
                assertFalse("Unfolded emoji must not display a folding marker",
                    candidates[headIndex].comment == "…")
                assertTrue("Unfolded emoji must not offer hidden alternatives",
                    getCandidateActionsIfCurrent(headIndex, candidates[headIndex].text)
                        .orEmpty().none { it.icon == FOLDED_SYMBOL_ICON })
                expectCommit("🔛") { assertTrue(select(alternativeIndex)) }
                assertTrue("Selecting an unfolded emoji must finish composition", isEmpty())
            }
        }
    }

    @Test
    fun expandedChaSymbolsFollowTheirSourcesAndCommitByNativeIdentity() = withPinyin {
        setImConfig(PINYIN, RawConfig().apply {
            getOrCreate("Prediction").value = "False"
        })
        for (symbol in listOf("❌", "❎", "🍵")) {
            typePinyin("cha")
            val nativeBefore = nativeCandidates()
            val expanded = expandedCandidates(firstHiddenIndex = 0)
            printCandidateSnapshot("cha native ($symbol)", nativeBefore)
            printCandidateSnapshot("cha expanded ($symbol)", expanded)
            val fork = expanded.indexOfFirst { it.text == "叉" }
            val tea = expanded.indexOfFirst { it.text == "茶" }
            assertTrue("Missing 叉 for cha", fork >= 0)
            assertTrue("Missing 茶 for cha", tea >= 0)
            assertTrue("叉 must retain both following emoji entries", fork + 2 < expanded.size)
            assertTrue("茶 must retain its following emoji entry", tea + 1 < expanded.size)
            assertEquals("The first 叉 symbol must follow its source", "❌", expanded[fork + 1].text)
            assertEquals("The second 叉 symbol must follow its head", "❎", expanded[fork + 2].text)
            assertEquals("Tea must stay next to its own source", "🍵", expanded[tea + 1].text)
            listOf(expanded[fork + 1], expanded[fork + 2], expanded[tea + 1]).forEach { candidate ->
                assertEquals("Attached symbols must use compact display comments", "", candidate.displayComment)
                assertEquals(candidate.text, candidate.textWithComment())
                assertTrue("The presentation must retain a real native index",
                    candidate.nativeIndex in nativeBefore.indices)
                val original = nativeBefore[candidate.nativeIndex]
                assertEquals("Source comments must remain intact for native guards", original.comment, candidate.comment)
                if (candidate.selectionActionId >= 0) {
                    assertEquals(original.text, candidate.actionSourceText)
                } else {
                    assertEquals(original.text, candidate.text)
                }
            }
            assertEquals("Expansion must not reorder or rewrite native candidates", nativeBefore, nativeCandidates())
            val chosen = when (symbol) {
                "❌" -> expanded[fork + 1]
                "❎" -> expanded[fork + 2]
                else -> expanded[tea + 1]
            }
            expectCommit(symbol) { selectExpandedCandidate(chosen) }
            assertTrue("Selecting the complete symbol must finish composition", isEmpty())
        }
    }

    @Test
    fun expandedFoldedAlternativesKeepJniIdentityAndGuardedActions() = withPinyin {
        setImConfig(PINYIN, RawConfig().apply {
            getOrCreate("Prediction").value = "False"
        })
        for ((input, headText, alternativeText) in listOf(
            Triple("kaishi", "▶️", "🔛"),
            Triple("kaixin", "😄", "😆"),
        )) {
            val choice = foldedChoice(input, headText, alternativeText)
            val nativeBefore = nativeCandidates()
            printCandidateSnapshot("$input native", nativeBefore)
            var chosen: CandidateWord? = null
            // In both cases the head is beyond the actual first page. The source
            // may still be on that page, but its expanded group is unfolded.
            for (firstHiddenIndex in listOf(choice.index, 0)) {
                val expanded = expandedCandidates(firstHiddenIndex)
                printCandidateSnapshot("$input expansion after $firstHiddenIndex", expanded)
                val headPosition = expanded.indexOfFirst {
                    it.nativeIndex == choice.index && it.selectionActionId < 0
                }
                assertTrue("Missing expanded head for $input", headPosition >= 0)
                assertTrue("Missing following expanded alternative for $input", headPosition + 1 < expanded.size)
                val head = expanded[headPosition]
                val alternative = expanded[headPosition + 1]
                assertEquals(alternativeText, alternative.text)
                assertEquals("The action item must retain its head's native index", choice.index, alternative.nativeIndex)
                assertEquals(choice.action.id, alternative.selectionActionId)
                assertEquals("The JNI source string must retain the filtered head", choice.head.text, alternative.actionSourceText)
                assertEquals("Native guard metadata must retain the original marker", choice.head.comment, alternative.comment)
                assertEquals("", head.displayComment)
                assertEquals("", alternative.displayComment)
                assertEquals(headText, head.textWithComment())
                assertEquals(alternativeText, alternative.textWithComment())
                assertEquals("Two-item pages must retain the entire unfolded group and native tail",
                    expanded, expandedCandidates(firstHiddenIndex, batchSize = 2))
                chosen = alternative
            }
            assertEquals("Projecting folded choices must preserve the original native list",
                nativeBefore, nativeCandidates())
            assertEquals("The original first-page head keeps its folding marker", "…", choice.head.comment)
            assertNotNull(getCandidateActionsIfCurrent(choice.index, choice.head.text))
            expectCommit(alternativeText) { selectExpandedCandidate(requireNotNull(chosen)) }
            assertTrue("The guarded projected action must finish composition", isEmpty())
        }
    }

    @Test
    fun viewportSnapshotGuardsMaterializeDeferredSymbolsAsRealNativeCandidates() = withPinyin {
        setImConfig(PINYIN, RawConfig().apply {
            getOrCreate("Prediction").value = "False"
        })
        typePinyin("kaishi")
        var failure: Throwable? = null
        try {
            assertNoCommit {
                val before = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                assertTrue("Symbols and folding must enable the native viewport policy", before.foldingEnabled)
                assertTrue("The JNI snapshot must carry a live revision", before.revision > 0)
                assertTrue("The snapshot must report its complete native size", before.total >= before.candidates.size)
                before.candidates.forEachIndexed { index, candidate -> assertEquals(index, candidate.nativeIndex) }
                val source = before.candidates.first { it.text == "开始" }
                val folded = before.candidates.first { it.text == "▶️" }
                assertEquals(-1, source.symbolSourceId)
                assertEquals(0, source.symbolCount)
                assertEquals(source.nativeIndex, folded.symbolSourceId)
                assertEquals(2, folded.symbolCount)
                assertEquals("…", folded.comment)

                assertFalse("A stale revision must not change the native presentation",
                    setCandidateViewportIfCurrent(before.revision - 1, 1, intArrayOf(source.nativeIndex), -1))
                assertFalse("Duplicate first-page IDs must be rejected",
                    setCandidateViewportIfCurrent(before.revision, 2,
                        intArrayOf(source.nativeIndex, source.nativeIndex), -1))
                for (badIndex in listOf(-1, before.total)) {
                    assertFalse("Out-of-range first-page ID $badIndex must be rejected",
                        setCandidateViewportIfCurrent(before.revision, 1, intArrayOf(badIndex), -1))
                }
                assertFalse("An ordinary source must not be declared the first-page symbol",
                    setCandidateViewportIfCurrent(before.revision, 1,
                        intArrayOf(source.nativeIndex), source.nativeIndex))
                val unchanged = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                assertEquals(before.revision, unchanged.revision)
                assertEquals(before.candidates.toList(), unchanged.candidates.toList())

                assertTrue("The source-only first page must be accepted",
                    setCandidateViewportIfCurrent(before.revision, 1, intArrayOf(source.nativeIndex), -1))
                var after = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                withTimeout(EVENT_TIMEOUT_MS) {
                    while (after.revision == before.revision) {
                        delay(10)
                        after = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                    }
                }
                assertTrue(after.foldingEnabled)
                assertEquals("开始", after.candidates.first().text)
                val choices = listOf("▶️", "🔛").map { text -> after.candidates.first { it.text == text } }
                assertEquals("The unfolded choices must own distinct real native IDs", 2,
                    choices.map { it.nativeIndex }.toSet().size)
                assertEquals("Deferred choices must remain contiguous",
                    choices.first().nativeIndex + 1, choices.last().nativeIndex)
                choices.forEach { candidate ->
                    assertTrue("The symbol must be outside the source-only page", candidate.nativeIndex > 0)
                    assertEquals(after.candidates.first().nativeIndex, candidate.symbolSourceId)
                    assertEquals(2, candidate.symbolCount)
                    assertEquals(-1, candidate.selectionActionId)
                    assertNull(candidate.actionSourceText)
                    assertFalse("A real deferred choice must have no fold marker", candidate.comment.contains("…"))
                    assertEquals(candidate.text, getCandidates(candidate.nativeIndex, 1).single().text)
                    assertTrue("A real deferred choice must have no folded-symbol action",
                        getCandidateActions(candidate.nativeIndex).none { it.icon == FOLDED_SYMBOL_ICON })
                }
                assertFalse("Applying the old snapshot again must be rejected",
                    setCandidateViewportIfCurrent(before.revision, 1, intArrayOf(source.nativeIndex), -1))
                assertEquals(after.revision, getCandidateViewportSnapshot(CANDIDATE_LIMIT).revision)
                assertFalse("Viewport changes must retain the unfinished composition", isEmpty())
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // The viewport capacity is runtime state of the input context and survives reset() and
            // deactivate(); every test in this class reuses TEST_CONTEXT_UID. Restore the automatic
            // policy (size 0, symbol index -2, as CandidatesView does) so later tests are not left
            // with a one-candidate first page, which defers the emoji group with a "(开始)" comment.
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                var lastSnapshot: CandidateViewportSnapshot? = null
                try {
                    val restored = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                        var accepted = false
                        while (!accepted) {
                            activate(TEST_CONTEXT_UID, "candidate-action-guard-test")
                            setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                            focus()
                            activateIme(PINYIN)
                            var snapshot = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                            if (snapshot.candidates.isEmpty() || isEmpty()) {
                                typePinyin("kaishi")
                                snapshot = getCandidateViewportSnapshot(CANDIDATE_LIMIT)
                            }
                            lastSnapshot = snapshot
                            accepted = setCandidateViewportIfCurrent(snapshot.revision, 0, intArrayOf(), -2)
                            if (!accepted) delay(10)
                        }
                        true
                    }
                    assertTrue("Automatic viewport restoration timed out after $EVENT_TIMEOUT_MS ms " +
                        "for context $TEST_CONTEXT_UID: $lastSnapshot", restored == true)
                } catch (error: Throwable) {
                    cleanupFailure = AssertionError(
                        "Failed to restore the automatic candidate viewport for context $TEST_CONTEXT_UID", error)
                } finally {
                    try {
                        reset()
                    } catch (error: Throwable) {
                        val resetFailure = AssertionError(
                            "Failed to reset candidate viewport test context $TEST_CONTEXT_UID", error)
                        cleanupFailure = cleanupFailure?.also { it.addSuppressed(resetFailure) } ?: resetFailure
                    }
                }
                cleanupFailure?.let { error ->
                    failure?.addSuppressed(error) ?: throw error
                }
            }
        }
    }

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
        }
    }

    private suspend fun FcitxAPI.nativeCandidates(): List<CandidateWord> {
        val candidates = mutableListOf<CandidateWord>()
        while (true) {
            val page = getCandidates(candidates.size, CANDIDATE_LIMIT)
            candidates.addAll(page)
            if (page.size < CANDIDATE_LIMIT) return candidates
        }
    }

    private fun printCandidateSnapshot(label: String, candidates: List<CandidateWord>) {
        val targets = setOf("叉", "茶", "❌", "❎", "🍵", "▶️", "🔛", "😄", "😆")
        val selected = candidates.mapIndexedNotNull { position, candidate ->
            if (candidate.text !in targets) return@mapIndexedNotNull null
            "#$position ${candidate.text}: nativeIndex=${candidate.nativeIndex}, " +
                "displayComment=${candidate.displayComment?.let { "[$it]" } ?: "null"}, " +
                "selectionActionId=${candidate.selectionActionId}, actionSourceText=${candidate.actionSourceText}"
        }
        println("CandidateActionGuardTest $label: ${selected.joinToString("; ")}")
    }

    private suspend fun FcitxAPI.expandedCandidates(
        firstHiddenIndex: Int,
        batchSize: Int = CANDIDATE_LIMIT,
    ): List<CandidateWord> {
        val candidates = mutableListOf<CandidateWord>()
        while (true) {
            val page = getExpandedCandidates(firstHiddenIndex + candidates.size, batchSize, firstHiddenIndex)
            candidates.addAll(page)
            if (page.size < batchSize) return candidates
        }
    }

    private suspend fun FcitxAPI.selectExpandedCandidate(candidate: CandidateWord) {
        assertTrue("Expanded selection must retain a native index", candidate.nativeIndex >= 0)
        if (candidate.selectionActionId >= 0) {
            assertTrue(triggerCandidateActionIfCurrent(
                candidate.nativeIndex, requireNotNull(candidate.actionSourceText),
                candidate.selectionActionId, candidate.text, FOLDED_SYMBOL_ICON
            ))
        } else {
            assertTrue(select(candidate.nativeIndex))
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
        }
        val candidates = getCandidates(0, CANDIDATE_LIMIT)
        val index = candidates.indexOfFirst { it.text == headText }
        assertTrue("Missing $headText for $input: " +
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
                    val originalFiles = listOf(
                        File(root, "config/profile"),
                        File(root, "config/conf/pinyin.conf"),
                        File(root, "config/conf/pinyin/$PINYIN.conf")
                    ).associateWith { it.takeIf(File::exists)?.readBytes() }
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
                        setImConfig(PINYIN, RawConfig().apply {
                            getOrCreate("SymbolsEnabled").value = "True"
                            getOrCreate("FoldSymbolCandidates").value = "True"
                        })
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
                                // Restore exact profile/configuration bytes, including absent files.
                                originalFiles.forEach { (file, bytes) ->
                                    if (bytes != null) file.writeBytes(bytes)
                                    else if (file.exists()) assertTrue(file.delete())
                                }
                                reloadConfig()
                            }
                        }
                    }
                }
            }
        } finally {
            FcitxDaemon.disconnect(CLIENT_NAME)
        }
    }

    private fun RawConfig.findRecursively(key: String): RawConfig? =
        if (name == key) this else subItems.orEmpty().firstNotNullOfOrNull { it.findRecursively(key) }

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
