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

/** Verify ordinary 26-key input uses the same ordered confirmations as 18-key. */
class Shuangpin26PreeditTest {
    @Test
    fun compactInitialsRejectUnrequestedRetroflexWhileKeepingCommonWords() = withIme {
        setImConfig(XIAOHE, getImConfig(XIAOHE)["cfg"].deepCopy().apply {
            getOrCreate("PartialSpLength").value = "3"
        })
        setCompact(true)
        for ((code, expected, present) in listOf(
            Triple("nuvid", "南十字船队", false),
            Triple("wum", "为什么", true),
        )) {
            reset()
            for (chr in code) {
                val index = Shuangpin18Keys.groups.indexOfFirst { chr in it }
                assertTrue(index >= 0)
                val key = KeySym(0x0200E000 + index)
                sendKey(key, KeyStates.Virtual)
                sendKey(key, KeyStates.Virtual, up = true)
            }
            awaitPanel("the grouped composition") { clientPreeditCached.isNotEmpty() }
            assertEquals("Unexpected compact abbreviation for $code", present,
                getCandidates(0, CANDIDATE_LIMIT).any { it.text == expected })
        }
    }

    @Test
    fun invalidPairOffersOneFirstPageCorrectionWithoutChangingItsCode() = withIme(isolateCorrection = true) {
        setImConfig(XIAOHE, getImConfig(XIAOHE)["cfg"].deepCopy().apply {
            getOrCreate("Fuzzy").getOrCreate("Correction").value = "None"
        })
        assertEquals("None", getImConfig(XIAOHE)["cfg"]["Fuzzy"]["Correction"].value)
        reset()
        typeCode("fm")
        awaitCode("fm")
        val candidates = getCandidates(0, CANDIDATE_LIMIT)
        val fallbacks = candidates.withIndex().filter { it.value.isFallback }
        assertEquals("An invalid pair must have exactly one marked correction", 1, fallbacks.size)
        val fallback = fallbacks.single()
        assertTrue("The correction was placed after the native first page", fallback.index <= 4)
        assertEquals("The correction must be one character", 1,
            Character.codePointCount(fallback.value.text, 0, fallback.value.text.length))
        awaitCode("fm")
        coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(EVENT_TIMEOUT_MS) {
                    eventFlow.filterIsInstance<FcitxEvent.CommitStringEvent>().first().data.text
                }
            }
            assertTrue("The correction could not be selected", select(fallback.index))
            assertEquals(fallback.value.text, commit.await())
        }
        awaitCode("")
        assertTrue("Selecting the correction left part of the invalid pair", isEmpty())
    }

    @Test
    fun validPairsDoNotCreateFallbackCorrections() = withIme {
        for (code in listOf("ni", "ma")) {
            reset()
            typeCode(code)
            awaitCode(code)
            val candidates = getCandidates(0, CANDIDATE_LIMIT)
            assertTrue("A valid pair lost all candidates", candidates.isNotEmpty())
            assertFalse("A valid pair was marked as a correction", candidates.any { it.isFallback })
        }
    }

    @Test
    fun compactGroupedInputDoesNotCreateThe26KeyFallback() = withIme {
        for (code in listOf("ni", "fm")) {
            reset()
            setCompact(true)
            for (chr in code) {
                val index = Shuangpin18Keys.groups.indexOfFirst { chr in it }
                assertTrue(index >= 0)
                val key = KeySym(0x0200E000 + index)
                sendKey(key, KeyStates.Virtual)
                sendKey(key, KeyStates.Virtual, up = true)
            }
            awaitPanel("the grouped client preedit") { clientPreeditCached.isNotEmpty() }
            val candidates = getCandidates(0, CANDIDATE_LIMIT)
            if (code == "ni") assertTrue("The valid grouped pair lost all candidates", candidates.isNotEmpty())
            assertFalse("The 26-key fallback leaked into the compact layout", candidates.any { it.isFallback })
        }
    }

    @Test
    fun syllablesAdvanceInOrderWithoutChangingTheTypedCode() = withIme {
        typeCode("nihc")
        awaitCode("nihc")
        awaitPanel("only the ordinary ni syllable") {
            it.tabs.filter { action -> action.id >= 0 }.map { action -> action.text } == listOf("ni")
        }
        pickSyllable("ni")
        awaitCode("nihc")
        awaitPanel("the next hao syllable") {
            it.tabs.any { action -> action.text == "hao" } &&
                it.tabs.none { action -> action.text == "ni" }
        }
        pickSyllable("hao")
        awaitCode("nihc")
        awaitPanel("both pairs confirmed") { it.tabs.none { action -> action.id >= 0 } }
        assertEquals("你好", getCandidates(0, CANDIDATE_LIMIT).first().text)
    }

    @Test
    fun backspaceUndoesEachConfirmationBeforeDeletingKeys() = withIme {
        typeCode("nihc")
        pickSyllable("ni")
        pickSyllable("hao")

        nextPanel { press("BackSpace") }
        awaitCode("nihc")
        awaitPanel("the last confirmation undone") { it.tabs.any { action -> action.text == "hao" } }
        nextPanel { press("BackSpace") }
        awaitCode("nihc")
        awaitPanel("the first confirmation undone") { it.tabs.any { action -> action.text == "ni" } }
        nextPanel { press("BackSpace") }
        awaitCode("nih")
    }

    @Test
    fun explicitApostrophesSurviveConfirmationAndUndo() = withIme {
        typeCode("ni'hc")
        pickSyllable("ni")
        awaitCode("ni'hc")
        pickSyllable("hao")
        awaitCode("ni'hc")
        awaitPanel("both separated syllables confirmed") { it.tabs.none { action -> action.id >= 0 } }
        nextPanel { press("BackSpace") }
        awaitCode("ni'hc")
        awaitPanel("the pair after the apostrophe offered again") {
            it.tabs.any { action -> action.text == "hao" } &&
                it.tabs.none { action -> action.text == "ni" }
        }
    }

    @Test
    fun newlyTypedKeysAreDeletedBeforeUndoingEarlierConfirmations() = withIme {
        typeCode("nihc")
        pickSyllable("ni")
        pickSyllable("hao")
        typeCode("ni")
        awaitCode("nihcni")
        nextPanel { press("BackSpace") }
        awaitCode("nihcn")
        nextPanel { press("BackSpace") }
        awaitCode("nihc")
        awaitPanel("the earlier confirmations kept") { it.tabs.none { action -> action.id >= 0 } }
        nextPanel { press("BackSpace") }
        awaitCode("nihc")
        awaitPanel("hao offered again after deleting the new keys") {
            it.tabs.any { action -> action.text == "hao" }
        }
    }

    @Test
    fun fullPinyinKeepsItsCheckableSyllableFilter() = withIme("pinyin") {
        typeCode("nihao")
        awaitPanel("the legacy ni filter") {
            it.tabs.any { action -> action.id >= 0 && action.text == "ni" && action.isCheckable }
        }
        val action = inputPanelCached.tabs.single { it.id >= 0 && it.text == "ni" }
        assertFalse(action.isChecked)
        nextPanel { triggerCandidateListTabAction(action.id) }
        awaitPanel("the checked ni filter") {
            it.tabs.any { current -> current.id == action.id && current.isChecked }
        }
        nextPanel { triggerCandidateListTabAction(action.id) }
        awaitPanel("the unchecked ni filter") {
            it.tabs.any { current -> current.id == action.id && !current.isChecked }
        }
    }

    private fun withIme(
        uniqueName: String = XIAOHE,
        isolateCorrection: Boolean = false,
        body: suspend FcitxAPI.() -> Unit,
    ) = runBlocking {
        val clientName = "Shuangpin26PreeditTest-$uniqueName"
        val connection = FcitxDaemon.connect(clientName)
        val app = InstrumentationRegistry.getInstrumentation().targetContext
            .createDeviceProtectedStorageContext()
        val root = app.getExternalFilesDir(null) ?: app.filesDir
        var originalCorrectionFiles: Map<File, ByteArray?>? = null
        try {
            if (isolateCorrection) {
                withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                FcitxDaemon.stopFcitx()
                try {
                    val files = listOf(
                        File(root, "data/pinyin/shuangpin.history"),
                        File(root, "data/pinyin/shuangpin-placement.conf"),
                    )
                    originalCorrectionFiles = files.associateWith { it.takeIf(File::isFile)?.readBytes() }
                    files.forEach { file ->
                        if (file.exists()) assertTrue("Could not isolate ${file.name}", file.delete())
                    }
                } finally {
                    FcitxDaemon.startFcitx()
                }
            }
            withTimeout(READY_TIMEOUT_MS) {
                connection.runOnReady {
                    val originalEnabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
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
                        activate(TEST_CONTEXT_UID, "shuangpin26-preedit-device-test")
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(uniqueName)
                        assertEquals(uniqueName, currentIme().uniqueName)
                        setImConfig(uniqueName, originalConfig.deepCopy().apply {
                            getOrCreate("PinyinInPreedit").value = "False"
                            getOrCreate("PreeditMode").value = "Composing pinyin"
                            getOrCreate("PreeditCursorPositionAtBeginning").value = "False"
                            getOrCreate("PageSize").value = "5"
                            // isEmpty() includes prediction candidates as well as the composition.
                            getOrCreate("Prediction").value = "False"
                            getOrCreate("PartialSpLength").value = "0"
                            getOrCreate("Fuzzy").apply {
                                subItems?.forEach { option ->
                                    if (option.value == "True" || option.value == "False") {
                                        option.value = "False"
                                    }
                                }
                                getOrCreate("Correction").value = "QWERTY"
                            }
                        })
                        reset()
                        if (uniqueName != "pinyin") {
                            setCompact(false)
                        }
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
            withContext(NonCancellable) {
                try {
                    originalCorrectionFiles?.let { files ->
                        FcitxDaemon.stopFcitx()
                        try {
                            files.forEach { (file, bytes) ->
                                if (bytes != null) file.writeBytes(bytes)
                                else if (file.exists()) assertTrue("Could not remove ${file.name}", file.delete())
                            }
                        } finally {
                            FcitxDaemon.startFcitx()
                        }
                        withTimeout(READY_TIMEOUT_MS) { connection.runOnReady { assertTrue(isReady) } }
                    }
                } finally {
                    FcitxDaemon.disconnect(clientName)
                }
            }
        }
    }

    private suspend fun FcitxAPI.typeCode(input: String) {
        for (chr in input) press(if (chr == '\'') "apostrophe" else chr.toString())
    }

    private suspend fun FcitxAPI.press(key: String) {
        sendKey(key, states = KeyStates.Virtual.states)
        sendKey(key, states = KeyStates.Virtual.states, up = true)
    }

    private suspend fun FcitxAPI.setCompact(compact: Boolean) {
        val mode = KeySym(if (compact) 0x0200E101 else 0x0200E100)
        sendKey(mode, KeyStates.Virtual)
        sendKey(mode, KeyStates.Virtual, up = true)
    }

    private suspend fun FcitxAPI.pickSyllable(text: String) {
        awaitPanel("the pending $text syllable") { it.tabs.any { action -> action.id >= 0 && action.text == text } }
        val action = inputPanelCached.tabs.single { it.id >= 0 && it.text == text }
        assertFalse("A confirmation must not toggle a filter", action.isCheckable)
        assertFalse(action.isChecked)
        nextPanel { triggerCandidateListTabAction(action.id) }
    }

    private suspend fun FcitxAPI.nextPanel(action: suspend FcitxAPI.() -> Unit) = coroutineScope {
        val before = inputPanelCached
        val updated = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(EVENT_TIMEOUT_MS) {
                eventFlow.filterIsInstance<FcitxEvent.InputPanelEvent>().first { it.data !== before }
            }
        }
        action()
        updated.await()
    }

    private suspend fun FcitxAPI.awaitCode(expected: String) {
        val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
            while (clientPreeditCached.toString().replace(" ", "") != expected) delay(10)
            true
        }
        assertTrue("Expected code '$expected', observed '$clientPreeditCached'", matched == true)
    }

    private suspend fun FcitxAPI.awaitPanel(
        description: String,
        predicate: (FcitxEvent.InputPanelEvent.Data) -> Boolean,
    ) {
        val matched = withTimeoutOrNull(EVENT_TIMEOUT_MS) {
            while (!predicate(inputPanelCached)) delay(10)
            true
        }
        assertTrue("Expected $description, observed ${inputPanelCached.tabs.toList()}", matched == true)
    }

    private fun RawConfig.deepCopy(): RawConfig =
        copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

    private companion object {
        const val XIAOHE = "shuangpin-xiaohe"
        const val TEST_CONTEXT_UID = -260021
        const val READY_TIMEOUT_MS = 30_000L
        const val EVENT_TIMEOUT_MS = 10_000L
        const val CANDIDATE_LIMIT = 256
    }
}
