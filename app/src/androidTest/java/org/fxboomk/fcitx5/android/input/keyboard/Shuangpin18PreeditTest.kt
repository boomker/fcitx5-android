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
import org.fxboomk.fcitx5.android.core.KeyState
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
        // Lock the completed pair so dictionary ranking cannot split it into
        // two initials while this test checks the next unfinished syllable.
        pickSyllable("ni")
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
            assertEquals("wp", commit.await())
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
    fun rawCommitUsesItsDecodedOrdinaryKeys() = withIme(XIAOHE) {
        grouped("ni")
        awaitPreedit("ni")
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            press("Return")
            assertEquals("ni", commit.await())
        }
    }

    @Test
    fun plainReturnCommitsTypedKeysWhen26KeyPreeditShowsFullPinyin() =
        withIme(XIAOHE, completePinyin = true) {
            mode(false)
            press("j")
            press("y")
            awaitPreedit("jun")
            assertTrue("Highlighting the first candidate failed", setCandidateCursor(0))
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                press("Return")
                assertEquals("jy", commit.await())
            }
            awaitPreedit("")
        }

    @Test
    fun plainReturnKeepsCompactLayoutBehaviorForOrdinaryKeyEvents() =
        withIme(XIAOHE, completePinyin = true) {
            for (key in listOf("Return", "KP_Enter")) {
                mode(true)
                press("j")
                press("y")
                awaitPreedit("jun")
                coroutineScope {
                    val commit = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(EVENT_TIMEOUT_MS) {
                            eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                        }
                    }
                    press(key)
                    assertEquals("jun", commit.await())
                }
                awaitPreedit("")
            }
        }

    @Test
    fun popupLowercaseStaysLiteralUntilEnterCommitsIt() {
        for (completePinyin in listOf(false, true)) {
            withIme(XIAOHE, completePinyin = completePinyin) {
                for (enter in listOf("Return", "KP_Enter")) {
                    val input = "iojy" + "abcdefghijklmnopqrstuvwxyz".repeat(3)
                    coroutineScope {
                        val commit = async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(EVENT_TIMEOUT_MS) {
                                eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                            }
                        }
                        input.forEachIndexed { index, letter ->
                            literal(letter.toString())
                            awaitPreedit(input.take(index + 1))
                            assertEquals(index + 1, clientPreeditCached.cursor)
                            assertTrue("Literal candidates unexpectedly have annotations",
                                getCandidates(0, CANDIDATE_LIMIT).all { it.comment.isEmpty() })
                            assertFalse("A popup letter committed before Enter", commit.isCompleted)
                        }
                        press(enter)
                        assertEquals(input, commit.await())
                    }
                    awaitPreedit("")
                    grouped("nihc")
                    awaitPreedit(if (completePinyin) "ni hao" else "ni hc")
                    assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
                    reset()
                }
            }
        }
    }

    @Test
    fun popupLowercaseCanBeEditedAndClearedWithoutLeakingItsMode() {
        for (completePinyin in listOf(false, true)) {
            withIme(XIAOHE, completePinyin = completePinyin) {
                "iojy".forEach { literal(it.toString()) }
                awaitPreedit("iojy")
                moveCursor(2)
                awaitClient("cursor inside the literal code") { it.cursor == 2 }
                press("BackSpace")
                awaitPreedit("ijy")
                assertEquals(1, clientPreeditCached.cursor)
                literal("v")
                awaitPreedit("ivjy")
                assertEquals(2, clientPreeditCached.cursor)
                moveCursor(4)
                awaitClient("cursor at the end of the literal code") { it.cursor == 4 }
                repeat(4) { press("BackSpace") }
                awaitPreedit("")
                grouped("nihc")
                awaitPreedit(if (completePinyin) "ni hao" else "ni hc")
                assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)

                reset()
                literal("v")
                awaitPreedit("v")
                reset()
                awaitPreedit("")
                grouped("nihc")
                awaitPreedit(if (completePinyin) "ni hao" else "ni hc")
                assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
            }
        }
    }

    @Test
    fun popupLowercasePreservesOrdinaryCompositionUntilExplicitCommit() =
        withIme(XIAOHE, completePinyin = true) {
            for (partiallySelected in listOf(false, true)) {
                reset()
                mode(true)
                val preserved = if (partiallySelected) {
                    "nihc".forEach { press(it.toString()) }
                    awaitPreedit("ni hao")
                    val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == "你" }
                    assertTrue("Partial candidate 你 is missing", index >= 0)
                    assertTrue("Partial selection was rejected", select(index))
                    awaitPreedit("你hao")
                    "你hc"
                } else {
                    press("j")
                    press("y")
                    awaitPreedit("jun")
                    "jy"
                }
                coroutineScope {
                    val commit = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(EVENT_TIMEOUT_MS) {
                            eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                        }
                    }
                    literal("i")
                    awaitPreedit(preserved + "i")
                    assertFalse("A popup letter committed the previous composition", commit.isCompleted)
                    assertTrue(getCandidates(0, CANDIDATE_LIMIT).all { it.comment.isEmpty() })
                    press("Return")
                    assertEquals(preserved + "i", commit.await())
                }
                awaitPreedit("")
                assertTrue("The old composition returned after explicit commit",
                    getCandidates(0, CANDIDATE_LIMIT).isEmpty())
            }
        }

    @Test
    fun popupLowercasePreservesGroupedCompositionAndItsCursor() = withIme(XIAOHE) {
        grouped("nihc")
        pickSyllable("ni")
        pickSyllable("hua")
        awaitPreedit("ni hx")
        moveCursor(2)
        awaitClient("cursor after the first grouped pair") { it.cursor == 2 }
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            literal("i")
            awaitPreedit("B Ii H X")
            assertEquals(4, clientPreeditCached.cursor)
            assertFalse("A popup letter committed the grouped candidate", commit.isCompleted)
            press("BackSpace")
            awaitPreedit("B I H X")
            assertEquals(3, clientPreeditCached.cursor)
            press("Return")
            assertEquals("B I H X", commit.await())
        }
    }

    @Test
    fun popupLowercasePreservesSelectedChineseAndAnInteriorCursor() =
        withIme(XIAOHE, completePinyin = true) {
            "nihc".forEach { press(it.toString()) }
            awaitPreedit("ni hao")
            val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == "你" }
            assertTrue("Partial candidate 你 is missing", index >= 0)
            assertTrue("Partial selection was rejected", select(index))
            awaitPreedit("你hao")
            moveCursor(1)
            awaitClient("cursor after the selected Chinese prefix") { it.cursor == 1 }
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                literal("i")
                awaitPreedit("你ihc")
                assertEquals(2, clientPreeditCached.cursor)
                assertFalse("A popup letter committed the selected Chinese prefix", commit.isCompleted)
                press("BackSpace")
                awaitPreedit("你hc")
                assertEquals(1, clientPreeditCached.cursor)
                press("Return")
                assertEquals("你hc", commit.await())
            }
        }

    @Test
    fun popupUppercasePreservesGroupedCompositionInsteadOfCommittingItsCandidate() =
        withIme(XIAOHE) {
            grouped("nihc")
            pickSyllable("ni")
            pickSyllable("hua")
            awaitPreedit("ni hx")
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                literal("W")
                awaitPreedit("B I H XW")
                assertEquals(8, clientPreeditCached.cursor)
                assertFalse("An uppercase popup committed the grouped candidate", commit.isCompleted)
                press("Return")
                assertEquals("B I H XW", commit.await())
            }
        }

    @Test
    fun popupLowercaseContinuesUppercaseEnglishUntilASymbolCommitsIt() =
        withIme(XIAOHE, completePinyin = true) {
            press("W")
            awaitPreedit("W")
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                "ill".forEach { literal(it.toString()) }
                awaitPreedit("Will")
                assertFalse("A popup letter committed the uppercase word", commit.isCompleted)
                val index = getCandidates(0, CANDIDATE_LIMIT).indexOfFirst { it.text == "Will" }
                assertTrue("The literal English candidate is missing", index >= 0)
                assertTrue("Highlighting the English candidate failed", setCandidateCursor(index))
                press("period")
                assertEquals("Will", commit.await())
            }
            awaitPreedit("")
        }

    @Test
    fun lowercaseYThenPopupOStaysLowercaseAndOffersEnglishCompletion() =
        withIme(XIAOHE, completePinyin = true) {
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                // Long-pressing `io` consumes the group press; only the chosen
                // popup letter is delivered as a literal input event.
                grouped("y")
                literal("o")
                awaitPreedit("yo")
                assertFalse("Selecting popup o committed the composition early", commit.isCompleted)

                val candidates = getCandidates(0, CANDIDATE_LIMIT)
                val completionIndex = candidates.indexOfFirst {
                    it.text.startsWith("yo") && it.text.length > 2
                }
                assertTrue("No lowercase English completion was offered for yo: ${candidates.map { it.text }}",
                    completionIndex >= 0)
                val completion = candidates[completionIndex].text
                assertTrue("Highlighting the English completion failed",
                    setCandidateCursor(completionIndex))
                awaitPreedit("yo")
                assertFalse("Highlighting an English completion committed it early", commit.isCompleted)
                assertTrue("Selecting the English completion failed", select(completionIndex))
                assertEquals(completion, commit.await())
            }
            awaitPreedit("")
        }

    @Test
    fun uppercaseEnglishLearnsChineseAsANextWordPrediction() =
        withIme(XIAOHE, allowLearning = true) {
            "DNA".forEach { literal(it.toString()) }
            awaitPreedit("DNA")
            val firstCompletions = getCandidates(0, CANDIDATE_LIMIT)
            assertFalse("A Chinese prediction appeared as an English completion",
                firstCompletions.any { it.text == "检测" })
            val firstDna = firstCompletions.indexOfFirst { it.text == "DNA" }
            assertTrue("The DNA candidate is missing", firstDna >= 0)
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>()
                            .first().data.text
                    }
                }
                assertTrue(select(firstDna))
                assertEquals("DNA", commit.await())
            }
            awaitPreedit("")

            // Use full-pinyin key events to select a regular Chinese candidate.
            mode(false)
            "jiance".forEach { press(it.toString()) }
            val chinese = getCandidates(0, CANDIDATE_LIMIT)
            val detection = chinese.indexOfFirst { it.text == "检测" }
            assertTrue("The Chinese candidate 检测 is missing: " + chinese.map { it.text },
                detection >= 0)
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>()
                            .first().data.text
                    }
                }
                assertTrue(select(detection))
                assertEquals("检测", commit.await())
            }

            mode(true)
            "DNA".forEach { literal(it.toString()) }
            awaitPreedit("DNA")
            val completions = getCandidates(0, CANDIDATE_LIMIT)
            assertFalse("The learned word leaked into English completions",
                completions.any { it.text == "检测" })
            val dna = completions.indexOfFirst { it.text == "DNA" }
            assertTrue("The DNA candidate is missing after learning", dna >= 0)
            coroutineScope {
                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>()
                            .first().data.text
                    }
                }
                assertTrue(select(dna))
                assertEquals("DNA", commit.await())
            }
            awaitPreedit("")
            assertTrue("检测 did not appear in the prediction list after DNA",
                getCandidates(0, CANDIDATE_LIMIT).any { it.text == "检测" })
        }

    @Test
    fun popupLettersInEitherCaseStartEnglishPredictionsFromTheirLiteralPrefix() =
        withIme(XIAOHE, completePinyin = true) {
            for (prefix in listOf("h", "he", "yo", "Yo", "W", "Wi")) {
                reset()
                mode(true)
                coroutineScope {
                    val commit = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(EVENT_TIMEOUT_MS) {
                            eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>()
                                .first().data.text
                        }
                    }
                    prefix.forEach { literal(it.toString()) }
                    awaitPreedit(prefix)
                    val candidates = getCandidates(0, CANDIDATE_LIMIT)
                    assertTrue("No English completion for popup prefix $prefix: ${candidates.map { it.text }}",
                        candidates.any { it.text.length > prefix.length && it.text.startsWith(prefix) })
                    assertFalse("Popup letters committed their prefix $prefix", commit.isCompleted)
                    commit.cancel()
                    reset()
                }
            }
            awaitPreedit("")
        }

    @Test
    fun uppercaseEnglishUsesActualGroupedKeysAndCompletionWithoutChangingIme() {
        for (completePinyin in listOf(false, true)) {
            withIme(XIAOHE, completePinyin = completePinyin) {
                // These use the same private grouped keysyms as the 18-key UI,
                // including e and o from the second position in their groups.
                for ((word, groups, exactPopup) in listOf(
                    Triple("Will", "ill", ""),
                    Triple("Hello", "ello", ""),
                    Triple("Will", "ll", "i"),
                    Triple("Will", "il", ""),
                )) {
                    reset()
                    mode(true)
                    press(word.first().toString())
                    awaitPreedit(word.first().toString())
                    coroutineScope {
                        val commit = async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(EVENT_TIMEOUT_MS) {
                                eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                            }
                        }
                        exactPopup.forEach { literal(it.toString()) }
                        grouped(groups)
                        val candidates = getCandidates(0, CANDIDATE_LIMIT)
                        val index = candidates.indexOfFirst { it.text == word }
                        assertTrue("The grouped English candidate $word is missing", index >= 0)
                        assertFalse("A grouped or popup letter committed the English prefix", commit.isCompleted)
                        assertEquals("Grouped English changed the active IM", XIAOHE, currentIme().uniqueName)
                        assertTrue("Highlighting $word failed", setCandidateCursor(index))
                        awaitPreedit(word.take(1 + exactPopup.length + groups.length))
                        assertTrue("Selecting $word failed", select(index))
                        assertEquals(word, commit.await())
                    }
                    awaitPreedit("")
                    grouped("nihc")
                    awaitPreedit(if (completePinyin) "ni hao" else "ni hc")
                    assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
                }
            }
        }
    }

    @Test
    fun repeatedCompactLayoutNotificationsKeepPopupLowercaseInPreedit() = withIme(XIAOHE) {
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            literal("i")
            awaitPreedit("i")
            mode(true)
            Shuangpin18Keys.configurationActions(GROUPS.sorted()).forEach { action ->
                sendKey(action.sym, action.states)
                sendKey(action.sym, action.states, up = true)
            }
            mode(true)
            awaitPreedit("i")
            assertTrue("Popup i did not offer an English prediction",
                getCandidates(0, CANDIDATE_LIMIT).any { it.text.startsWith("i", ignoreCase = true) })
            assertFalse("A repeated compact-layout notification committed the literal buffer", commit.isCompleted)
            literal("o")
            awaitPreedit("io")
            assertFalse("Continuing popup lowercase committed before Enter", commit.isCompleted)
            press("Return")
            assertEquals("io", commit.await())
        }
        awaitPreedit("")
    }

    @Test
    fun returnAndShiftReturnCommitTheExpectedEncodingForEachLayout() {
        for (completePinyin in listOf(false, true)) {
            withIme(XIAOHE, completePinyin = completePinyin) {
                for (compact in listOf(true, false)) {
                    mode(compact)
                    for (separated in listOf(false, true)) {
                        for (explicitSeparator in listOf(false, true)) {
                            val input = if (explicitSeparator) "ni'hc" else "nihc"
                            if (compact) grouped(input)
                            else input.forEach { press(if (it == '\'') "apostrophe" else it.toString()) }
                            val visible = "ni" + (if (explicitSeparator) "'" else "") +
                                (if (completePinyin) "hao" else "hc")
                            awaitPreedit("the composing code before Return") {
                                it.replace(" ", "") == visible
                            }
                            coroutineScope {
                                val commit = async(start = CoroutineStart.UNDISPATCHED) {
                                    withTimeout(EVENT_TIMEOUT_MS) {
                                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                                    }
                                }
                                press("Return", shift = separated)
                                val expected = when {
                                    separated && !explicitSeparator -> "ni " + visible.substring(2)
                                    separated -> visible
                                    compact -> visible.replace("'", "")
                                    else -> input
                                }
                                assertEquals(expected, commit.await())
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun returnCommitsTheCandidateHighlightedThroughJni() {
        for (completePinyin in listOf(false, true)) {
            withIme(XIAOHE, completePinyin = completePinyin) {
                for (separated in listOf(false, true)) {
                    grouped("nihc")
                    val initialPreedit = if (completePinyin) "ni hao" else "ni hc"
                    awaitPreedit(initialPreedit)
                    val candidates = getCandidates(0, CANDIDATE_LIMIT)
                    val index = candidates.indexOfFirst { it.text == "壁画" }
                    assertTrue("A non-first 壁画 candidate is required", index > 0)
                    assertFalse("A negative cursor index was accepted", setCandidateCursor(-1))
                    assertFalse("An out-of-range cursor index was accepted", setCandidateCursor(Int.MAX_VALUE))
                    awaitPreedit(initialPreedit)
                    assertEquals("Invalid cursor updates changed candidates", candidates.toList(),
                        getCandidates(0, CANDIDATE_LIMIT).toList())
                    assertTrue("Highlighting 壁画 through JNI failed", setCandidateCursor(index))
                    coroutineScope {
                        val commit = async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(EVENT_TIMEOUT_MS) {
                                eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                            }
                        }
                        press("Return", shift = separated)
                        val boundary = if (separated) " " else ""
                        val expected = "bi" + boundary + (if (completePinyin) "hua" else "hx")
                        assertEquals(expected, commit.await())
                    }
                    awaitPreedit("")
                }
            }
        }
    }

    private fun withIme(
        uniqueName: String,
        completePinyin: Boolean = false,
        allowLearning: Boolean = false,
        body: suspend FcitxAPI.() -> Unit,
    ) = runBlocking {
        val clientName = "Shuangpin18PreeditTest-$uniqueName"
        val connection = FcitxDaemon.connect(clientName)
        var isolatedLearningFiles: Map<File, ByteArray?>? = null
        try {
            if (allowLearning) {
                withTimeout(READY_TIMEOUT_MS) {
                    connection.runOnReady { assertTrue(isReady) }
                }
                val app = InstrumentationRegistry.getInstrumentation().targetContext
                    .createDeviceProtectedStorageContext()
                val root = app.getExternalFilesDir(null) ?: app.filesDir
                val historyFile = File(root, "data/pinyin/user.history")
                val customPhraseFile = File(root, "data/pinyin/customphrase")
                FcitxDaemon.stopFcitx()
                try {
                    isolatedLearningFiles = listOf(historyFile, customPhraseFile)
                        .associateWith { it.takeIf(File::isFile)?.readBytes() }
                    if (historyFile.exists()) {
                        assertTrue("Could not isolate ${historyFile.name}", historyFile.delete())
                    }
                    // Keep the Chinese candidate used by the learning test while
                    // removing user custom phrases that could make it an English
                    // completion for DNA before any learning takes place.
                    customPhraseFile.parentFile!!.mkdirs()
                    customPhraseFile.writeText("jiance,1=检测\n")
                } finally {
                    FcitxDaemon.startFcitx()
                }
            }
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
                        setCapFlags(
                            if (allowLearning) CapabilityFlags(CapabilityFlag.Preedit)
                            else CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive),
                        )
                        focus()
                        activateIme(uniqueName)
                        assertEquals(uniqueName, currentIme().uniqueName)
                        val config = originalConfig.deepCopy().apply {
                            getOrCreate("PinyinInPreedit").value = if (completePinyin) "True" else "False"
                            getOrCreate("PreeditMode").value = "Composing pinyin"
                            getOrCreate("PreeditCursorPositionAtBeginning").value = "False"
                            if (allowLearning) {
                                getOrCreate("Learning").value = "True"
                                getOrCreate("Prediction").value = "True"
                            }
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
            isolatedLearningFiles?.let { files ->
                FcitxDaemon.stopFcitx()
                try {
                    files.forEach { (file, bytes) ->
                        if (bytes != null) {
                            file.parentFile!!.mkdirs()
                            file.writeBytes(bytes)
                        } else if (file.exists()) {
                            assertTrue("Could not remove test-created ${file.name}", file.delete())
                        }
                    }
                } finally {
                    FcitxDaemon.startFcitx()
                }
            }
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

    private suspend fun FcitxAPI.literal(text: String) {
        val action = requireNotNull(Shuangpin18Keys.literalAction(text))
        sendKey(action.sym, action.states)
        sendKey(action.sym, action.states, up = true)
    }

    private suspend fun FcitxAPI.press(key: String, shift: Boolean = false) {
        val states = if (shift) KeyStates(KeyState.Virtual, KeyState.Shift) else KeyStates.Virtual
        sendKey(key, states = states.states)
        sendKey(key, states = states.states, up = true)
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
