/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

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
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise scoped correction learning while restoring every touched user-data file. */
class ShuangpinCorrectionLearningTest {
    @Test
    fun repeatedCorrectionSelectionsPromoteGraduallyBehindALegalCandidate() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val initial = scene.candidates(CODE)
        val initialIndex = initial.indexOfFirst { it.identity() == correction }
        val leadingLegal = initial.first().identity()
        repeat(2) {
            scene.choose(CODE, correction)
            assertTrue("The first two choices gave the correction an early promotion",
                scene.assertAdaptive(CODE, correction) >= initialIndex)
        }

        scene.choose(CODE, correction)
        val thirdIndex = scene.assertAdaptive(CODE, correction)
        assertTrue("Three confirmations did not begin a bounded promotion", thirdIndex in 1 until initialIndex)
        repeat(2) { scene.choose(CODE, correction) }
        val fifthIndex = scene.assertAdaptive(CODE, correction)
        assertTrue("Five confirmations did not gradually improve the correction's rank", fifthIndex in 1 until thirdIndex)
        repeat(12) { scene.choose(CODE, correction) }
        val seventeenthIndex = scene.assertAdaptive(CODE, correction)
        assertTrue("Repeated confirmations stopped improving the correction's rank", seventeenthIndex <= fifthIndex)
        assertEquals("Correction learning displaced the leading legal candidate", leadingLegal,
            scene.candidates(CODE).first().identity())
    }

    @Test
    fun fiveMoreFrequentlySelectedWordsPushTheCorrectionOffTheFirstPage() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val alternatives = scene.candidates(CODE)
            .filter { candidate ->
                !candidate.isFallback && candidate.text.length == 2 &&
                    candidate.text.all { it in '㐀'..'鿿' }
            }
            .map { it.identity() }.distinctBy { it.text }.take(5)
        assertEquals("The test requires five distinct complete two-initial words", 5, alternatives.size)
        repeat(2) { scene.choose(CODE, correction) }
        for (word in alternatives) repeat(4) { scene.choose(CODE, word) }

        val candidates = scene.candidates(CODE)
        val correctionIndex = candidates.indexOfFirst { it.identity() == correction }
        assertTrue("The correction disappeared instead of being ranked: $candidates", correctionIndex >= 0)
        assertTrue("The correction was forced onto the first page: $candidates", correctionIndex >= 5)
        assertEquals("Selected complete words did not occupy the first page",
            alternatives.map { it.text }.toSet(), candidates.take(5).map { it.text }.toSet())
        assertFalse("A ranked correction retained the initial-placement hint", candidates[correctionIndex].isFallback)
        assertFalse("Adaptive demotion was mistaken for a manual override", candidates[correctionIndex].isDeferredFallback)
    }

    @Test
    fun learningIsIndependentForEachRawCodeAndShuangpinScheme() = withIsolatedLearning { scene ->
        val sameSchemeOtherCode = scene.initialCorrection(OTHER_CODE)
        scene.activate(MICROSOFT)
        val sameCodeOtherScheme = scene.initialCorrection()
        scene.activate(XIAOHE)
        val correction = scene.initialCorrection()
        repeat(3) { scene.choose(CODE, correction) }
        scene.assertAdaptive(CODE, correction)

        assertEquals("Learning fm leaked into another raw code", sameSchemeOtherCode,
            scene.initialCorrection(OTHER_CODE))
        scene.activate(MICROSOFT)
        assertEquals("Learning Xiaohe leaked into another shuangpin scheme", sameCodeOtherScheme,
            scene.initialCorrection())
    }

    @Test
    fun savedLearningSurvivesANativeEngineRestart() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        repeat(3) { scene.choose(CODE, correction) }
        val learnedIndex = scene.assertAdaptive(CODE, correction)
        scene.api { reset(); saveNonRimeState() }
        assertTrue("The scoped history was not saved", scene.historyFile.isFile && scene.historyFile.length() > 0)
        scene.restart()

        assertEquals("Restart lost the learned correction order", learnedIndex,
            scene.assertAdaptive(CODE, correction))
    }

    @Test
    fun forgettingALearnedCorrectionResetsOnlyItsCurrentScope() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val otherCorrection = scene.initialCorrection(OTHER_CODE)
        repeat(2) { scene.choose(OTHER_CODE, otherCorrection) }
        val otherIndex = scene.assertAdaptive(OTHER_CODE, otherCorrection)
        repeat(3) { scene.choose(CODE, correction) }
        val learned = scene.candidates(CODE)
        val index = learned.indexOfFirst { it.identity() == correction }
        assertTrue("The correction displaced the leading legal candidate", index > 0)
        assertFalse(learned[index].isFallback)
        scene.api {
            val actions = getCandidateActions(index)
            val forget = actions.singleOrNull { it.id == 0 }
            assertTrue("The learned correction lost its Forget candidate action: $actions", forget != null)
            triggerCandidateAction(index, forget!!.id)
        }

        assertEquals("Forgetting the correction did not restore its initial placement", correction,
            scene.initialCorrection())
        assertEquals("Forgetting fm erased another code's learning", otherIndex,
            scene.assertAdaptive(OTHER_CODE, otherCorrection))
    }

    @Test
    fun learnedResetForcesDefaultPlacementDespiteOtherLearnedWords() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val other = scene.candidates(CODE).first { candidate ->
            !candidate.isFallback && candidate.text.length == 2 && candidate.text.all { it in '㐀'..'鿿' }
        }.identity()
        repeat(4) { scene.choose(CODE, other) }
        repeat(3) { scene.choose(CODE, correction) }
        scene.resetCorrection(CODE, correction)

        assertEquals("Another learned word prevented the manual default override", correction,
            scene.initialCorrection())
        assertEquals("Resetting the correction erased the other word's choices", other.text,
            scene.candidates(CODE).first().text)
        scene.choose(CODE, other)
        assertEquals("Selecting a legal word cleared the forced-default override", correction,
            scene.initialCorrection())
    }

    @Test
    fun secondResetDefersTheCorrectionAndFurtherResetsAreIdempotent() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        repeat(3) { scene.choose(CODE, correction) }
        scene.resetCorrection(CODE, correction)
        assertEquals(correction, scene.initialCorrection())
        scene.resetCorrection(CODE, correction)
        val deferredIndex = scene.assertDeferred(CODE, correction)
        scene.resetCorrection(CODE, correction)
        assertEquals("Resetting an off-page correction changed its placement", deferredIndex,
            scene.assertDeferred(CODE, correction))
    }

    @Test
    fun resettingAFreshDefaultCorrectionDirectlyMovesItOffTheFirstPage() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        scene.resetCorrection(CODE, correction)
        scene.assertDeferred(CODE, correction)
        val ordinary = scene.candidates(CODE).first { candidate ->
            candidate.text.length == 2 && candidate.text.all { it in '㐀'..'鿿' }
        }.identity()
        scene.choose(CODE, ordinary)
        scene.assertDeferred(CODE, correction)
    }

    @Test
    fun bothManualResetStagesSurviveSavingAndRestartingTheEngine() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        repeat(3) { scene.choose(CODE, correction) }
        scene.resetCorrection(CODE, correction)
        assertEquals(correction, scene.initialCorrection())
        scene.api { reset(); saveNonRimeState() }
        assertTrue("The forced-default override was not saved",
            scene.placementFile.isFile && scene.placementFile.length() > 0)
        scene.restart()
        assertEquals("Restart lost the manual default placement", correction, scene.initialCorrection())

        scene.resetCorrection(CODE, correction)
        scene.assertDeferred(CODE, correction)
        scene.api { reset(); saveNonRimeState() }
        assertTrue("The off-page override was not saved",
            scene.placementFile.isFile && scene.placementFile.length() > 0)
        scene.restart()
        scene.assertDeferred(CODE, correction)
    }

    @Test
    fun manualResetStagesAreIsolatedByRawCodeAndShuangpinScheme() = withIsolatedLearning { scene ->
        val otherCode = scene.initialCorrection(OTHER_CODE)
        scene.activate(MICROSOFT)
        val otherScheme = scene.initialCorrection()
        scene.activate(XIAOHE)
        val correction = scene.initialCorrection()
        repeat(3) { scene.choose(CODE, correction) }
        scene.resetCorrection(CODE, correction)
        assertEquals(correction, scene.initialCorrection())
        assertEquals(otherCode, scene.initialCorrection(OTHER_CODE))
        scene.activate(MICROSOFT)
        assertEquals(otherScheme, scene.initialCorrection())
        scene.activate(XIAOHE)

        scene.resetCorrection(CODE, correction)
        scene.assertDeferred(CODE, correction)
        assertEquals("The off-page override affected another raw code", otherCode,
            scene.initialCorrection(OTHER_CODE))
        scene.activate(MICROSOFT)
        assertEquals("The off-page override affected another scheme", otherScheme,
            scene.initialCorrection())
        scene.activate(XIAOHE)
        scene.assertDeferred(CODE, correction)
    }

    @Test
    fun choosingADeferredCorrectionFromTheBulkListRestoresAdaptiveRanking() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        scene.resetCorrection(CODE, correction)
        val deferredIndex = scene.assertDeferred(CODE, correction)
        // choose() searches the complete native list, including candidates after page one.
        scene.choose(CODE, correction)
        val firstIndex = scene.assertAdaptive(CODE, correction)
        assertTrue("An explicit later-page selection did not clear the placement override", firstIndex < deferredIndex)
        repeat(2) { scene.choose(CODE, correction) }
        assertTrue("Further confirmations did not resume gradual learning",
            scene.assertAdaptive(CODE, correction) < firstIndex)
    }

    @Test
    fun resettingAnAlreadyOffPageLearnedCorrectionMovesItAfterEveryLegalCandidate() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val ranked = scene.rankCorrectionBehindOrdinaryWords(correction, 5)
        val learnedIndex = ranked.indexOfFirst { it.identity() == correction }
        assertTrue("The premise requires an adaptively off-page correction", learnedIndex >= 5)
        assertFalse(ranked[learnedIndex].isFallback)
        assertFalse(ranked[learnedIndex].isDeferredFallback)
        val legalOrder = ranked.legalCandidatesExcluding(correction).map { it.identity() }

        // No visibility argument: the native first-page boundary determines the branch.
        scene.resetCorrection(CODE, correction)
        scene.assertLast(CODE, correction, legalOrder)
        scene.api { reset(); saveNonRimeState() }
        scene.restart()
        scene.assertLast(CODE, correction)
        val legalOrderAfterRestart = scene.candidates(CODE).legalCandidatesExcluding(correction).map { it.identity() }
        scene.resetCorrection(CODE, correction)
        scene.assertLast(CODE, correction, legalOrderAfterRestart)

        scene.choose(CODE, correction)
        val adaptive = scene.candidates(CODE)
        val adaptiveIndex = adaptive.indexOfFirst { it.identity() == correction }
        assertTrue("Explicit selection removed the correction", adaptiveIndex >= 0)
        assertFalse("Explicit selection restored the default-placement override", adaptive[adaptiveIndex].isFallback)
        assertFalse("Explicit selection retained the tail-placement override", adaptive[adaptiveIndex].isDeferredFallback)
        assertTrue("Explicit selection left the correction locked after all legal candidates",
            adaptiveIndex < adaptive.lastIndex)
    }

    @Test
    fun explicitNotVisibleContextDefersALearnedCorrectionAtNativeIndexFour() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val ranked = scene.rankCorrectionBehindOrdinaryWords(correction, 4)
        val learnedIndex = ranked.indexOfFirst { it.identity() == correction }
        assertEquals("The narrow-viewport premise requires native index four", 4, learnedIndex)
        assertFalse(ranked[learnedIndex].isFallback)
        assertFalse(ranked[learnedIndex].isDeferredFallback)

        scene.resetCorrection(CODE, correction, isOnFirstPage = false)
        scene.assertLast(CODE, correction,
            ranked.legalCandidatesExcluding(correction).map { it.identity() })
    }

    @Test
    fun explicitVisibleContextReturnsANativeOffPageLearnedCorrectionToDefault() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val ranked = scene.rankCorrectionBehindOrdinaryWords(correction, 5)
        val learnedIndex = ranked.indexOfFirst { it.identity() == correction }
        assertTrue("The wide-viewport premise requires native index at least five", learnedIndex >= 5)
        assertFalse(ranked[learnedIndex].isFallback)
        assertFalse(ranked[learnedIndex].isDeferredFallback)

        scene.resetCorrection(CODE, correction, isOnFirstPage = true)
        assertEquals("An explicitly visible correction did not return to default placement", correction,
            scene.initialCorrection())
    }

    @Test
    fun reliableContextRetainsUniqueHiddenAlternativesAndTheirSelectionsLearn() = withIsolatedLearning { scene ->
        val frequencyChoice = scene.initialCorrection()
        scene.setContextRetention(true)
        val contextual = scene.contextualCandidates(MERCHANT)
        val group = assertDefaultCorrectionGroup(contextual)
        assertTrue("The real model did not retain different context and frequency choices: $contextual",
            group.size >= 2)
        assertTrue("The static-frequency fallback disappeared", group.any { it.identity() == frequencyChoice })
        assertTrue("The real model lost 商 -> 店 context coverage", group.any { it.text == "店" })
        val contextualChoice = group.single { it.isFallback }.identity()
        val alternative = group.first { it.isDeferredFallback }.identity()
        val identities = group.map { it.identity() }.toSet()

        // The complete-list item consumes fm, not just its first initial.
        repeat(2) {
            scene.chooseCurrent(alternative)
            val learned = scene.contextualCandidates(MERCHANT)
            val mainIndex = learned.indexOfFirst { it.identity() == contextualChoice }
            val retained = learned.filter { it.identity() in identities }
            assertEquals("A hidden selection removed a useful correction route", identities,
                retained.map { it.identity() }.toSet())
            assertBoundedUniqueCorrectionGroup(retained)
            assertTrue("A correction displaced the leading legal candidate", mainIndex > 0)
            assertFalse("The hidden selection did not create a same-code learning record",
                learned[mainIndex].isFallback)
            assertFalse("The reliable context lost its primary correction", learned[mainIndex].isDeferredFallback)
            assertTrue("The first two hidden choices overrode reliable context",
                learned.single { it.identity() == alternative }.isDeferredFallback)
        }
    }

    @Test
    fun manualDefaultAndDeferredPlacementApplyToEveryContextAlternativeAcrossRestart() = withIsolatedLearning { scene ->
        scene.setContextRetention(true)
        val original = scene.contextualCandidates(MERCHANT)
        val learnedChoice = assertDefaultCorrectionGroup(original).single { it.isFallback }.identity()
        scene.chooseCurrent(learnedChoice)
        scene.contextualCandidates(MERCHANT)
        scene.resetCurrentCorrection(learnedChoice, isOnFirstPage = true)
        assertDefaultCorrectionGroup(scene.currentCandidates())

        // Switch to a context whose strongest continuation is the frequency choice.
        // A different main word must not bypass the group's manual placement.
        assertDefaultCorrectionGroup(scene.contextualCandidates(QUALITY))
        scene.api { reset(); saveNonRimeState() }
        scene.restart()
        val restoredDefault = scene.contextualCandidates(MERCHANT)
        val defaultChoice = assertDefaultCorrectionGroup(restoredDefault).single { it.isFallback }.identity()
        scene.resetCurrentCorrection(defaultChoice, isOnFirstPage = true)
        assertDeferredCorrectionGroup(scene.currentCandidates())
        assertDeferredCorrectionGroup(scene.contextualCandidates(QUALITY))

        scene.api { reset(); saveNonRimeState() }
        scene.restart()
        val restoredDeferred = scene.contextualCandidates(MERCHANT)
        assertTrue("The context-specific alternative disappeared after restart",
            assertDeferredCorrectionGroup(restoredDeferred).size >= 2)
    }

    @Test
    fun tailResetAppliesToTheWholeContextGroupAndAnAlternativeCanResumeLearning() = withIsolatedLearning { scene ->
        scene.setContextRetention(true)
        val correction = assertDefaultCorrectionGroup(scene.contextualCandidates(MERCHANT))
            .single { it.isFallback }.identity()
        scene.chooseCurrent(correction)
        val ranked = scene.rankCorrectionBehindOrdinaryWords(correction, 5, prefix = MERCHANT)
        val mainIndex = ranked.indexOfFirst { it.identity() == correction }
        assertTrue("The premise requires the learned main correction off-page", mainIndex >= 5)
        assertFalse(ranked[mainIndex].isDeferredFallback)
        val legalOrder = ranked.legalCandidatesExcluding(correction).map { it.identity() }

        scene.resetCurrentCorrection(correction, isOnFirstPage = false)
        assertCorrectionGroupAfterLegalCandidates(scene.currentCandidates(), legalOrder)
        assertCorrectionGroupAfterLegalCandidates(scene.contextualCandidates(QUALITY))
        scene.api { reset(); saveNonRimeState() }
        scene.restart()
        val restored = scene.contextualCandidates(MERCHANT)
        val group = assertCorrectionGroupAfterLegalCandidates(restored)
        assertTrue("Restart lost the distinct frequency/context alternatives", group.size >= 2)

        val selected = group.last().identity()
        scene.chooseCurrent(selected)
        val adaptive = scene.contextualCandidates(MERCHANT)
        val selectedIndex = adaptive.indexOfFirst { it.identity() == selected }
        assertTrue("Selecting a tail alternative removed it", selectedIndex >= 0)
        val identities = group.map { it.identity() }.toSet()
        val adaptiveGroup = adaptive.withIndex().filter { it.value.identity() in identities }
        assertEquals("An alternative selection removed another group member", identities,
            adaptiveGroup.map { it.value.identity() }.toSet())
        assertBoundedUniqueCorrectionGroup(adaptiveGroup.map { it.value })
        val main = adaptiveGroup.filter { !it.value.isDeferredFallback }
        assertEquals("A tail alternative did not restore one adaptive group primary", 1, main.size)
        assertFalse("A tail alternative retained the default override", main.single().value.isFallback)
        assertTrue("A tail alternative displaced the leading legal candidate", main.single().index > 0)
        assertTrue("Explicit choice left the main word locked behind all legal candidates",
            main.single().index < adaptive.indexOfLast { !it.isDeferredFallback })
    }

    @Test
    fun aSelectedPrefixWithNativeTyposRetainsOneBoundedCorrectionGroupAndCommitsTheWholeSentence() = withIsolatedLearning { scene ->
        scene.setNativeCorrection()
        val suffix = scene.selectedPrefixCandidates(MERCHANT)
        val group = assertDefaultCorrectionGroup(suffix)
        assertTrue("The selected prefix lost its context-specific alternative", group.size >= 2)
        assertTrue("Selected 商 no longer supplies 店 as an fm correction", group.any { it.text == "店" })
        for (candidate in group) {
            assertEquals("Native typo decoding duplicated a complete correction character",
                1, suffix.count { it.text == candidate.text })
        }
        val selected = group.last().identity()
        scene.chooseCurrent(selected, committedText = MERCHANT.text + selected.text)
    }

    @Test
    fun canceledHighlightsAndPartialSelectionsDoNotLearn() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        val before = scene.candidates(CODE)
        val index = before.indexOfFirst { it.identity() == correction }
        scene.api {
            assertTrue(setCandidateCursor(index))
            reset()
        }
        assertEquals("Highlighting a canceled correction created a learning record", correction,
            scene.initialCorrection())

        val partial = scene.candidates(CODE).withIndex().firstOrNull { (_, candidate) ->
            !candidate.isFallback && !candidate.isDeferredFallback &&
                candidate.text.length == 1 && candidate.text[0] in '㐀'..'鿿'
        }
        assertTrue("The test requires an ordinary first-initial partial candidate", partial != null)
        scene.api {
            assertTrue(select(partial!!.index))
            awaitCode { it.isNotEmpty() }
            assertFalse("The partial candidate unexpectedly completed the whole pair", isEmpty())
            reset()
        }
        assertEquals("Canceling an incomplete selection created a learning record", correction,
            scene.initialCorrection())
    }

    @Test
    fun disabledLearningDoesNotRecordCorrectionSelections() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        scene.setLearning(false)
        repeat(3) { scene.choose(CODE, correction) }
        scene.setLearning(true)
        assertEquals("Selections while Learning=False created a scoped record", correction,
            scene.initialCorrection())
    }

    @Test
    fun sensitiveInputDoesNotRecordCorrectionSelections() = withIsolatedLearning { scene ->
        val correction = scene.initialCorrection()
        scene.setSensitive(true)
        repeat(3) { scene.choose(CODE, correction) }
        scene.setSensitive(false)
        assertEquals("Sensitive input created a scoped record", correction, scene.initialCorrection())
    }

    private fun withIsolatedLearning(body: suspend (Scene) -> Unit) = runBlocking {
        val clientName = "ShuangpinCorrectionLearningTest"
        val connection = FcitxDaemon.connect(clientName)
        var originals: Map<File, ByteArray?>? = null
        var stoppedForIsolation = false
        try {
            withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
            val app = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
            val root = app.getExternalFilesDir(null) ?: app.filesDir
            val scene = Scene(connection, root)
            // Stop first so the snapshot includes the user's latest in-memory
            // learning and every file created by the normal shutdown save.
            FcitxDaemon.stopFcitx()
            stoppedForIsolation = true
            val configDirectory = File(root, "config/conf/pinyin")
            val files = (listOf(
                File(root, "config/profile"),
                File(root, "config/conf/pinyin.conf"),
                File(configDirectory, "pinyin.conf"),
                File(configDirectory, "$XIAOHE.conf"),
                File(configDirectory, "$MICROSOFT.conf"),
            ) + configDirectory.listFiles().orEmpty().filter { it.isFile } + scene.learningFiles).distinct()
            originals = files.associateWith { file -> file.takeIf { it.isFile }?.readBytes() }
            scene.learningFiles.forEach { file ->
                if (file.exists()) assertTrue("Could not isolate $file", file.delete())
            }
            FcitxDaemon.startFcitx()
            stoppedForIsolation = false
            scene.activate(XIAOHE)
            body(scene)
        } finally {
            withContext(NonCancellable) {
                try {
                    val savedFiles = originals
                    if (savedFiles != null) {
                        try {
                            if (!stoppedForIsolation) {
                                withTimeout(READY_TIMEOUT_MS) {
                                    connection.runOnReady {
                                        reset()
                                        focus(false)
                                        deactivate(TEST_CONTEXT_UID)
                                    }
                                }
                            }
                        } finally {
                            if (!stoppedForIsolation) FcitxDaemon.stopFcitx()
                            try {
                                savedFiles.forEach { (file, bytes) ->
                                    if (bytes != null) {
                                        file.parentFile!!.mkdirs()
                                        file.writeBytes(bytes)
                                        assertArrayEquals("Could not restore $file", bytes, file.readBytes())
                                    } else if (file.exists()) {
                                        assertTrue("Could not remove test-created $file", file.delete())
                                    }
                                }
                            } finally {
                                FcitxDaemon.startFcitx()
                                withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                            }
                        }
                    } else if (stoppedForIsolation) {
                        // Reading a backup can fail; the user's original files
                        // are still untouched and the daemon must be restarted.
                        FcitxDaemon.startFcitx()
                        withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                    }
                } finally {
                    FcitxDaemon.disconnect(clientName)
                }
            }
        }
    }

    private class Scene(private val connection: FcitxConnection, root: File) {
        val historyFile = File(root, "data/pinyin/shuangpin.history")
        val placementFile = File(root, "data/pinyin/shuangpin-placement.conf")
        val learningFiles = listOf(historyFile, placementFile,
            File(root, "data/pinyin/user.history"), File(root, "data/pinyin/user.dict"))
        private var activeScheme = XIAOHE
        private var learning = true
        private var sensitive = false
        private var keepCurrentContext = false

        suspend fun <T> api(block: suspend FcitxAPI.() -> T): T =
            withTimeout(READY_TIMEOUT_MS) { connection.runOnReady(block) }

        suspend fun activate(scheme: String) {
            activeScheme = scheme
            api {
                assertTrue("The installed APK lacks $scheme", availableIme().any { it.uniqueName == scheme })
                val enabled = enabledIme().map { it.uniqueName }
                val names = (listOf("keyboard-us") + enabled + SCHEMES).distinct()
                if (names != enabled) setEnabledIme(names.toTypedArray())
                activate(TEST_CONTEXT_UID, "shuangpin-correction-learning-test")
                setCapFlags(capabilities(sensitive))
                focus()
                activateIme(scheme)
                setImConfig(scheme, getImConfig(scheme)["cfg"].deepCopy().apply {
                    getOrCreate("PinyinInPreedit").value = "False"
                    getOrCreate("PreeditMode").value = "Composing pinyin"
                    getOrCreate("PreeditCursorPositionAtBeginning").value = "False"
                    getOrCreate("PageSize").value = "5"
                    getOrCreate("Prediction").value = "False"
                    getOrCreate("KeepCurrentContext").value = if (keepCurrentContext) "True" else "False"
                    for (option in listOf("CloudPinyinEnabled", "SpellEnabled", "SymbolsEnabled",
                        "ChaiziEnabled", "StrokeCandidateEnabled")) {
                        getOrCreate(option).value = "False"
                    }
                    getOrCreate("Learning").value = if (learning) "True" else "False"
                    getOrCreate("PartialSpLength").value = "2"
                    getOrCreate("Fuzzy").apply {
                        subItems?.forEach { option ->
                            if (option.value == "True" || option.value == "False") option.value = "False"
                        }
                        getOrCreate("Correction").value = "None"
                    }
                })
                reset()
                val mode = KeySym(0x0200E100)
                sendKey(mode, KeyStates.Virtual)
                sendKey(mode, KeyStates.Virtual, up = true)
            }
        }

        suspend fun candidates(code: String): List<CandidateWord> = api {
            reset()
            typeCode(code)
            allCandidates()
        }

        suspend fun contextualCandidates(prefix: ContextPrefix, code: String = CODE): List<CandidateWord> = api {
            reset()
            typeCode(prefix.code)
            val choices = allCandidates()
            val index = choices.indexOfFirst { it.text == prefix.text }
            assertTrue("The real dictionary lacks context prefix $prefix: $choices", index >= 0)
            selectAndAssertCommit(index, choices[index].identity())
            // reset() here would erase the context initPredict() just retained.
            typeCode(code)
            allCandidates()
        }

        suspend fun currentCandidates(): List<CandidateWord> = api { allCandidates() }

        suspend fun chooseCurrent(candidate: CandidateIdentity, committedText: String = candidate.text) = api {
            val choices = allCandidates()
            val index = choices.indexOfFirst { it.identity() == candidate }
            assertTrue("The complete current list lost $candidate: $choices", index >= 0)
            selectAndAssertCommit(index, candidate, committedText)
        }

        suspend fun selectedPrefixCandidates(prefix: ContextPrefix): List<CandidateWord> = api {
            reset()
            typeCode(prefix.code + CODE)
            val choices = allCandidates()
            val index = choices.indexOfFirst { it.text == prefix.text }
            assertTrue("The decoder lacks the partial context prefix $prefix: $choices", index >= 0)
            coroutineScope {
                val committed = async(start = CoroutineStart.UNDISPATCHED) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first()
                }
                try {
                    assertTrue("Selecting the prefix failed", select(index))
                    awaitCode { it.contains(prefix.text) && it.filter { char -> char in 'a'..'z' } == CODE }
                    assertFalse("Selecting a prefix unexpectedly committed the sentence", committed.isCompleted)
                    assertFalse("Selecting a prefix consumed the pending fm pair", isEmpty())
                } finally {
                    committed.cancel()
                }
            }
            allCandidates()
        }

        suspend fun resetCurrentCorrection(candidate: CandidateIdentity, isOnFirstPage: Boolean) = api {
            val choices = allCandidates()
            val index = choices.indexOfFirst { it.identity() == candidate }
            assertTrue("The current correction vanished before reset: $choices", index >= 0)
            val action = getCandidateActions(index).singleOrNull { it.id == 0 }
            assertTrue("The current correction lacks its reset action", action != null)
            triggerCandidateAction(index, action!!.id, isOnFirstPage)
        }

        suspend fun initialCorrection(code: String = CODE): CandidateIdentity {
            val candidates = candidates(code)
            val corrections = candidates.withIndex().filter { it.value.isFallback }
            assertEquals("Expected one default-placement correction for $activeScheme/$code: $candidates", 1, corrections.size)
            val correction = corrections.single()
            assertEquals("The default correction was not at the native first-page tail",
                minOf(4, candidates.lastIndex), correction.index)
            assertFalse("A default correction was also marked off-page", correction.value.isDeferredFallback)
            return correction.value.identity()
        }

        suspend fun resetCorrection(
            code: String,
            correction: CandidateIdentity,
            isOnFirstPage: Boolean? = null,
        ) {
            val candidates = candidates(code)
            val index = candidates.indexOfFirst { it.identity() == correction }
            assertTrue("The complete list lost the correction before reset: $candidates", index >= 0)
            api {
                val actions = getCandidateActions(index)
                val forget = actions.singleOrNull { it.id == 0 }
                assertTrue("The correction lost its reset-frequency action: $actions", forget != null)
                triggerCandidateAction(index, forget!!.id, isOnFirstPage)
            }
        }

        suspend fun rankCorrectionBehindOrdinaryWords(
            correction: CandidateIdentity,
            count: Int,
            prefix: ContextPrefix? = null,
        ): List<CandidateWord> {
            suspend fun choices() = if (prefix == null) candidates(CODE) else contextualCandidates(prefix)
            val baseline = choices()
            val alternatives = baseline.filter { candidate ->
                !candidate.isFallback && candidate.text.length == 2 &&
                    candidate.text.all { it in '㐀'..'鿿' } &&
                    baseline.count { it.text == candidate.text } == 1
            }.map { it.identity() }.take(count)
            assertEquals("The test requires distinct complete-input ordinary words", count, alternatives.size)
            repeat(2) {
                choices()
                chooseCurrent(correction)
            }
            for (alternative in alternatives) repeat(4) {
                choices()
                chooseCurrent(alternative)
            }
            return choices()
        }

        suspend fun assertLast(
            code: String,
            correction: CandidateIdentity,
            legalOrder: List<CandidateIdentity>? = null,
        ) {
            val index = assertDeferred(code, correction)
            val candidates = candidates(code)
            val legal = candidates.legalCandidatesExcluding(correction)
            assertTrue("The reset correction was not after every legal candidate", index >= legal.size)
            assertTrue("Another correction alternative bypassed the tail override",
                candidates.drop(legal.size).all { it.isDeferredFallback })
            if (legalOrder != null) {
                assertEquals("Resetting the correction reordered legal candidates", legalOrder,
                    legal.map { it.identity() })
            }
        }

        suspend fun assertDeferred(code: String, correction: CandidateIdentity): Int {
            val candidates = candidates(code)
            val index = candidates.indexOfFirst { it.identity() == correction }
            assertTrue("The off-page correction was removed from the complete list: $candidates", index >= 0)
            assertTrue("The test requires five ordinary candidates before the deferred correction", candidates.size >= 6)
            assertTrue("The manual reset left the correction on the first native page: $candidates", index >= 5)
            assertFalse("An off-page correction retained the default-tail hint", candidates[index].isFallback)
            assertTrue("The off-page correction lost its viewport-exclusion flag", candidates[index].isDeferredFallback)
            assertTrue("A reset correction group exposed a default-tail hint", candidates.none { it.isFallback })
            val group = candidates.filter { it.isDeferredFallback }
            assertTrue("The correction group exceeded its bounded size", group.size in 1..3)
            assertEquals("Correction alternatives contain duplicate characters", group.size,
                group.map { it.text }.distinct().size)
            return index
        }

        suspend fun assertAdaptive(code: String, correction: CandidateIdentity): Int {
            val candidates = candidates(code)
            val index = candidates.indexOfFirst { it.identity() == correction }
            assertTrue("The learned correction disappeared: $candidates", index >= 0)
            assertTrue("The correction displaced the leading legal candidate: $candidates", index > 0)
            assertFalse("A learned correction retained its initial-placement hint", candidates[index].isFallback)
            assertFalse("A learned correction retained a manual off-page override", candidates[index].isDeferredFallback)
            return index
        }

        suspend fun choose(code: String, candidate: CandidateIdentity) {
            val candidates = candidates(code)
            val index = candidates.indexOfFirst { it.identity() == candidate }
            assertTrue("$activeScheme/$code lost $candidate: $candidates", index >= 0)
            api { selectAndAssertCommit(index, candidate) }
        }

        suspend fun setLearning(enabled: Boolean) {
            learning = enabled
            api {
                reset()
                setImConfig(activeScheme, getImConfig(activeScheme)["cfg"].deepCopy().apply {
                    getOrCreate("Learning").value = if (enabled) "True" else "False"
                })
            }
        }

        suspend fun setContextRetention(enabled: Boolean) {
            keepCurrentContext = enabled
            api {
                reset()
                setImConfig(activeScheme, getImConfig(activeScheme)["cfg"].deepCopy().apply {
                    getOrCreate("KeepCurrentContext").value = if (enabled) "True" else "False"
                })
            }
        }

        suspend fun setNativeCorrection() = api {
            reset()
            setImConfig(activeScheme, getImConfig(activeScheme)["cfg"].deepCopy().apply {
                getOrCreate("Fuzzy").getOrCreate("Correction").value = "QWERTY"
            })
            assertEquals("The native typo-decoder regression requires QWERTY correction", "QWERTY",
                getImConfig(activeScheme)["cfg"]["Fuzzy"]["Correction"].value)
        }

        suspend fun setSensitive(enabled: Boolean) {
            sensitive = enabled
            api { reset(); setCapFlags(capabilities(enabled)) }
        }

        suspend fun restart() {
            FcitxDaemon.restartFcitx()
            activate(activeScheme)
        }
    }

    private data class CandidateIdentity(val text: String, val comment: String)
    private data class ContextPrefix(val code: String, val text: String)

    private companion object {
        const val XIAOHE = "shuangpin-xiaohe"
        const val MICROSOFT = "shuangpin-ms"
        const val CODE = "fm"
        const val OTHER_CODE = "wm"
        val SCHEMES = listOf(XIAOHE, MICROSOFT)
        const val TEST_CONTEXT_UID = -260022
        const val READY_TIMEOUT_MS = 60_000L
        const val EVENT_TIMEOUT_MS = 10_000L
        const val CANDIDATE_LIMIT = 512
        val MERCHANT = ContextPrefix("uh", "商")
        val QUALITY = ContextPrefix("yz", "优")

        fun CandidateWord.identity() = CandidateIdentity(text, comment)

        fun List<CandidateWord>.legalCandidatesExcluding(primary: CandidateIdentity) = filterNot {
            it.isFallback || it.isDeferredFallback || it.identity() == primary
        }

        fun assertDefaultCorrectionGroup(candidates: List<CandidateWord>): List<CandidateWord> {
            val main = candidates.withIndex().filter { it.value.isFallback }
            assertEquals("Expected exactly one default homepage correction: $candidates", 1, main.size)
            assertFalse("The default main correction was marked deferred", main.single().value.isDeferredFallback)
            assertEquals("The default main correction did not retain the native tail hint", 4, main.single().index)
            val group = candidates.filter { it.isFallback || it.isDeferredFallback }
            assertBoundedUniqueCorrectionGroup(group)
            val lastLegal = candidates.indexOfLast { !it.isFallback && !it.isDeferredFallback }
            candidates.withIndex().filter { it.value.isDeferredFallback }.forEach {
                assertTrue("An alternate correction appeared ahead of a legal candidate", it.index > lastLegal)
            }
            return group
        }

        fun assertDeferredCorrectionGroup(candidates: List<CandidateWord>): List<CandidateWord> {
            assertTrue("A manually deferred group exposed a default main correction", candidates.none { it.isFallback })
            val group = candidates.filter { it.isDeferredFallback }
            assertBoundedUniqueCorrectionGroup(group)
            candidates.withIndex().filter { it.value.isDeferredFallback }.forEach {
                assertTrue("A manually deferred correction remained on the native first page", it.index >= 5)
            }
            return group
        }

        fun assertCorrectionGroupAfterLegalCandidates(
            candidates: List<CandidateWord>,
            legalOrder: List<CandidateIdentity>? = null,
        ): List<CandidateWord> {
            val group = assertDeferredCorrectionGroup(candidates)
            val legal = candidates.filterNot { it.isDeferredFallback }
            assertTrue("A context/frequency alternative escaped the group's tail placement",
                candidates.drop(legal.size).all { it.isDeferredFallback })
            if (legalOrder != null) {
                assertEquals("Resetting the correction group reordered legal candidates", legalOrder,
                    legal.map { it.identity() })
            }
            return group
        }

        fun assertBoundedUniqueCorrectionGroup(group: List<CandidateWord>) {
            assertTrue("The correction group was empty or unbounded: $group", group.size in 1..3)
            assertEquals("Correction routes duplicated a character", group.size, group.map { it.text }.distinct().size)
            assertTrue("A correction lost its single character or pinyin annotation", group.all {
                it.text.length == 1 && it.text[0] in '㐀'..'鿿' && it.comment.startsWith("(") && it.comment.endsWith(")")
            })
        }

        fun RawConfig.deepCopy(): RawConfig = copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

        fun capabilities(sensitive: Boolean) = if (sensitive) {
            CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive)
        } else {
            CapabilityFlags(CapabilityFlag.Preedit)
        }

        suspend fun FcitxAPI.awaitCode(predicate: (String) -> Boolean) {
            val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
                while (!predicate(clientPreeditCached.toString())) delay(10)
                true
            }
            assertTrue("Unexpected composing code: '$clientPreeditCached'", matched == true)
        }

        suspend fun FcitxAPI.typeCode(code: String) {
            for (chr in code) {
                sendKey(chr, states = KeyStates.Virtual.states)
                sendKey(chr, states = KeyStates.Virtual.states, up = true)
            }
            awaitCode { it.replace(" ", "") == code }
        }

        suspend fun FcitxAPI.allCandidates(): List<CandidateWord> {
            val candidates = mutableListOf<CandidateWord>()
            do {
                val page = getCandidates(candidates.size, CANDIDATE_LIMIT)
                candidates.addAll(page)
            } while (page.size == CANDIDATE_LIMIT)
            return candidates
        }

        suspend fun FcitxAPI.selectAndAssertCommit(
            index: Int,
            candidate: CandidateIdentity,
            committedText: String = candidate.text,
        ) {
            coroutineScope {
                val committed = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(EVENT_TIMEOUT_MS) {
                        eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                    }
                }
                assertTrue("Selecting $candidate failed", select(index))
                assertEquals("Selection did not commit the entire expected word", committedText, committed.await())
            }
            // The exact commit and native panel state are the completion contract:
            // the shared UI-preedit cache is not a per-selection synchronization barrier.
            val nativeEmpty = isEmpty()
            assertTrue("A complete selection left an unfinished input panel (UI preedit: '$clientPreeditCached')",
                nativeEmpty)
        }
    }
}
