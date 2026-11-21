/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateDetailInputContextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wubiLookupReturnsFullCharacterAndPhraseCodesWithoutChangingInput() = withEngine {
        val before = inputPanelCached
        assertEquals(listOf("wqiy", "wqiy"), getWubiCodes("你").toList())
        assertEquals(listOf("vbg", "vbg"), getWubiCodes("好").toList())
        assertEquals(listOf("wqvb", "wqiy", "vbg"), getWubiCodes("你好").toList())
        val card = CandidateCardContent.load(context, "你好", wubiCodes = getWubiCodes("你好").toList())
        assertEquals("wqvb", card.wubiCode)
        assertEquals(listOf("wqiy", "vbg"), card.characters.map { it.wubiCode })
        assertEquals(before, inputPanelCached)
    }

    @Test
    fun wubiLookupKeepsUnknownSupplementaryCharactersAligned() = withEngine {
        val codes = getWubiCodes("𳑹你好").toList()
        assertEquals(4, codes.size)
        assertEquals("", codes[1])
        assertEquals(listOf("wqiy", "vbg"), codes.drop(2))
        assertTrue(getWubiCodes("").isEmpty())
    }

    @Test
    fun actualXiaohePreeditConstrainsHenXingAndIsRestoredAfterwards() = withEngine {
        val ime = "shuangpin-xiaohe"
        val originalEnabled = enabledIme().map { it.uniqueName }
        val originalIme = currentIme().uniqueName
        val configFile = File(context.applicationInfo.dataDir, "config/conf/pinyin/$ime.conf")
        val profile = File(context.applicationInfo.dataDir, "config/profile")
        val files = listOf(configFile, profile).associateWith { it.takeIf(File::exists)?.readBytes() }
        val originalConfig = getImConfig(ime)["cfg"].deepCopy()
        try {
            if (ime !in originalEnabled) setEnabledIme((originalEnabled + ime).toTypedArray())
            activate(-262111, "candidate-details-context-test")
            setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
            focus()
            activateIme(ime)
            setImConfig(ime, originalConfig.deepCopy().apply {
                getOrCreate("PinyinInPreedit").value = "True"
                getOrCreate("PreeditMode").value = "No"
                getOrCreate("Prediction").value = "False"
                getOrCreate("PartialSpLength").value = "0"
            })
            reset()
            sendKey(KeySym(0x0200E100), KeyStates.Virtual)
            sendKey(KeySym(0x0200E100), KeyStates.Virtual, up = true)
            for (key in "hfxk") {
                sendKey(key, states = KeyStates.Virtual.states)
                sendKey(key, states = KeyStates.Virtual.states, up = true)
            }
            val matched = withTimeoutOrNull(10_000) {
                while (inputPanelCached.preedit.toString().trim() != "hen xing") delay(10)
                true
            }
            assertTrue("Expected hen xing, panel=${inputPanelCached.preedit}, client=$clientPreeditCached, " +
                "candidates=${getCandidates(0, 12).map { it.text }}, ime=${currentIme().uniqueName}", matched == true)
            val card = CandidateCardContent.load(context, "很行", inputPanelCached.preedit.toString())
            assertEquals("hěn xíng", card.phraseReading)
            assertEquals("xíng", card.characters.last().reading)
        } finally {
            withContext(NonCancellable) {
                try {
                    reset()
                    setImConfig(ime, originalConfig)
                    setEnabledIme(originalEnabled.toTypedArray())
                    if (originalIme in originalEnabled) activateIme(originalIme)
                    focus(false)
                    deactivate(-262111)
                } finally {
                    files.forEach { (file, bytes) ->
                        if (bytes != null) file.writeBytes(bytes) else if (file.exists()) {
                            assertTrue("Could not restore ${file.name}", file.delete())
                        }
                    }
                }
            }
        }
    }

    private fun withEngine(body: suspend FcitxAPI.() -> Unit) = runBlocking {
        val name = "candidate-details-native-test-${System.nanoTime()}"
        val connection = FcitxDaemon.connect(name)
        try {
            withTimeout(30_000) { connection.runOnReady { body() } }
        } finally {
            FcitxDaemon.disconnect(name)
        }
    }

    private fun RawConfig.deepCopy(): RawConfig =
        copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())
}
