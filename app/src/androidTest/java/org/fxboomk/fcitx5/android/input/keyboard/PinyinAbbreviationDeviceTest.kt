/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.keyboard

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the packaged native engine through JNI, restoring the saved configuration. */
class PinyinAbbreviationDeviceTest {
    @Test
    fun fullPinyinThresholdsAndSingleKeyException() = withIme("pinyin") {
        configureMatching("pinyin")
        for (minimum in 0..4) {
            setOptions("pinyin", "PartialSpLength" to minimum.toString())
            assertEquals(minimum.toString(), getImConfig("pinyin")["cfg"]["PartialSpLength"].value)
            assertCandidate("n", "你", true)
            assertCandidate("nihao", "你好", true)
            assertCandidate("nh", "你好", minimum in 1..2)
            assertCandidate("wsm", "为什么", minimum in 1..3)
            assertCandidate("nhao", "你好", minimum > 0)
        }
        setOptions("pinyin", "PartialSpLength" to "4")
        type("nh")
        assertFalse(candidates().contains("你好"))
        reset()
        type("n")
        assertTrue("A single encoded letter must remain matchable", candidates().contains("你"))
        setOptions("pinyin", "PartialSpLength" to "0")
        type("nihaon")
        val prefix = candidates().indexOf("你好")
        assertTrue(prefix >= 0)
        assertTrue(select(prefix))
        assertTrue("A remaining single key must still have candidates", candidates().contains("你"))
    }

    @Test
    fun shuangpinThresholdsAndSingleKeyException() = withIme(XIAOHE) {
        assertEquals("0", getImConfig(XIAOHE)["cfg"]["PartialSpLength"].value)
        configureMatching(XIAOHE)
        for (minimum in 0..4) {
            setOptions(XIAOHE, "PartialSpLength" to minimum.toString())
            assertCandidate("n", "你", true)
            assertCandidate("nihc", "你好", true)
            assertCandidate("nh", "你好", minimum in 1..2)
            assertCandidate("wum", "为什么", minimum in 1..3)
        }
    }

    @Test
    fun fullPinyinDefaultRangeAndReload() = withIme("pinyin", fresh = true) {
        val initial = getImConfig("pinyin")
        assertEquals("2", initial["cfg"]["PartialSpLength"].value)
        val length = initial["desc"].findRecursively("PartialSpLength")
        assertNotNull("The full-pinyin setting must be visible", length)
        assertEquals("0", length!!["IntMin"].value)
        assertEquals("4", length["IntMax"].value)
        assertEquals("2", length["DefaultValue"].value)
        assertEquals(null, initial["cfg"]["Fuzzy"].findByName("PartialSp"))
        configureMatching("pinyin")
        assertCandidate("nh", "你好", true)
        for (minimum in 0..4) {
            setOptions("pinyin", "PartialSpLength" to minimum.toString())
            reloadConfig()
            assertEquals(minimum.toString(), getImConfig("pinyin")["cfg"]["PartialSpLength"].value)
            assertCandidate("nh", "你好", minimum in 1..2)
            assertCandidate("n", "你", true)
        }
    }

    @Test
    fun legacyFullPinyinThresholdKeepsTheDefaultEnabled() = withIme("pinyin") {
        val file = File(configRoot(), "config/conf/pinyin/pinyin.conf")
        file.parentFile!!.mkdirs()
        file.writeText("PartialSpLength=4\n[Fuzzy]\nPartialSp=False\n")
        reloadConfig()
        assertEquals("2", getImConfig("pinyin")["cfg"]["PartialSpLength"].value)
        setOptions("pinyin", "PartialSpLength" to "0")
        reloadConfig()
        assertEquals("0", getImConfig("pinyin")["cfg"]["PartialSpLength"].value)
    }

    @Test
    fun legacyShuangpinSwitchMigratesAndThenYieldsToTheThreshold() = withIme(XIAOHE) {
        val file = File(configRoot(), "config/conf/pinyin/$XIAOHE.conf")
        file.parentFile!!.mkdirs()
        for (legacy in listOf("", "[Fuzzy]\nPartialSp=False\n", "[Fuzzy]\nPartialSp=True\n")) {
            file.writeText("PartialSpLength=3\n" + legacy)
            reloadConfig()
            assertEquals(if (legacy.endsWith("True\n")) "3" else "0",
                getImConfig(XIAOHE)["cfg"]["PartialSpLength"].value)
            setOptions(XIAOHE, "PartialSpLength" to "4")
            reloadConfig()
            assertEquals("4", getImConfig(XIAOHE)["cfg"]["PartialSpLength"].value)
        }
    }

    @Test
    fun uvMappingDoesNotNeedCommonTypos() = runBlocking {
        for (name in listOf("pinyin", XIAOHE)) {
            withIme(name) {
                configureMatching(name)
                setOptions(name, "PartialSpLength" to "0", "Fuzzy/LowerCaseMatchLetter" to "False")
                for ((alias, canonical, word) in listOf(Triple("yv", "yu", "鱼"), Triple("jv", "ju", "句"))) {
                    setOptions(name, "Fuzzy/V_U" to "False")
                    assertCandidate(alias, word, false)
                    setOptions(name, "Fuzzy/V_U" to "True")
                    assertEquals("False", getImConfig(name)["cfg"]["Fuzzy"]["NG_GN"].value)
                    assertCandidate(alias, word, true)
                    assertCandidate(canonical, word, true)
                    if (name == XIAOHE) {
                        mode(true)
                        reset()
                        for (key in alias) {
                            val group = Shuangpin18Keys.groups.indexOfFirst { key in it }
                            val sym = KeySym(0x0200E000 + group)
                            sendKey(sym, KeyStates.Virtual)
                            sendKey(sym, KeyStates.Virtual, up = true)
                        }
                        assertTrue("18-key $alias must match $word", candidates().contains(word))
                        reset()
                        mode(false)
                    }
                }
            }
        }
    }

    @Test
    fun compactDefaultsApplyFromSettingsAndRespectUserChoice() = withIme(XIAOHE) {
        val layouts = AppPrefs.getInstance().internal.compactShuangpinInputMethods
        setOptions(XIAOHE, "CompactTypoDefaultApplied" to "False", "Fuzzy/NG_GN" to "True")
        layouts.setValue(layouts.getValue() + XIAOHE)
        assertEquals("False", getImConfig(XIAOHE)["cfg"]["Fuzzy"]["NG_GN"].value)
        setOptions(XIAOHE, "Fuzzy/NG_GN" to "True")
        mode(true)
        assertEquals("True", getImConfig(XIAOHE)["cfg"]["Fuzzy"]["NG_GN"].value)
        mode(false)
        mode(true)
        reloadConfig()
        assertEquals("True", getImConfig(XIAOHE)["cfg"]["Fuzzy"]["NG_GN"].value)
    }

    @Test
    fun compactDefaultsAlsoApplyBeforeTyping() = withIme(XIAOHE) {
        setOptions(XIAOHE, "CompactTypoDefaultApplied" to "False", "Fuzzy/NG_GN" to "True")
        mode(true)
        val config = getImConfig(XIAOHE)["cfg"]
        assertEquals("False", config["Fuzzy"]["NG_GN"].value)
        assertEquals("True", config["CompactTypoDefaultApplied"].value)
    }

    private suspend fun FcitxAPI.configureMatching(name: String) {
        setOptions(name, "Fuzzy/Correction" to "None", "Fuzzy/NG_GN" to "False",
            "SpellEnabled" to "False", "CloudPinyinEnabled" to "False", "Learning" to "False",
            "Prediction" to "False", "WordCandidateLimit" to "0", "LongWordLengthLimit" to "0")
    }

    private suspend fun FcitxAPI.setOptions(name: String, vararg options: Pair<String, String>) {
        reset()
        val config = RawConfig()
        options.forEach { (path, value) ->
            path.split('/').fold(config) { node, key -> node.getOrCreate(key) }.value = value
        }
        setImConfig(name, config)
    }

    private suspend fun FcitxAPI.assertCandidate(input: String, word: String, expected: Boolean) {
        reset()
        type(input)
        val words = candidates()
        assertEquals("$input -> $word; first candidates: "+words.take(12), expected, word in words)
    }

    private suspend fun FcitxAPI.candidates() = getCandidates(0, 512).map { it.text }
    private suspend fun FcitxAPI.type(input: String) { for (key in input) press(key.toString()) }
    private suspend fun FcitxAPI.press(key: String) {
        sendKey(key, states = KeyStates.Virtual.states)
        sendKey(key, states = KeyStates.Virtual.states, up = true)
    }
    private suspend fun FcitxAPI.mode(compact: Boolean) {
        val sym = KeySym(if (compact) 0x0200E101 else 0x0200E100)
        sendKey(sym, KeyStates.Virtual)
        sendKey(sym, KeyStates.Virtual, up = true)
    }

    private fun withIme(name: String, fresh: Boolean = false, body: suspend FcitxAPI.() -> Unit) = runBlocking {
        val client = "PinyinAbbreviationDeviceTest-$name"
        val connection = FcitxDaemon.connect(client)
        try {
            withTimeout(60_000) {
                connection.runOnReady {
                    val root = configRoot()
                    val originalFiles = listOf(File(root, "config/profile"), File(root, "config/conf/pinyin.conf"),
                        File(root, "config/conf/pinyin/$name.conf")).associateWith { it.takeIf(File::exists)?.readBytes() }
                    val layouts = AppPrefs.getInstance().internal.compactShuangpinInputMethods
                    val originalLayouts = layouts.getValue()
                    val enabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
                    try {
                        layouts.setValue(originalLayouts - name)
                        if (fresh) {
                            File(root, "config/conf/pinyin/$name.conf").let { if (it.exists()) assertTrue(it.delete()) }
                            reloadConfig()
                        }
                        setEnabledIme((listOf("keyboard-us") + enabled + name).distinct().toTypedArray())
                        activate(TEST_CONTEXT, "pinyin-abbreviation-test")
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(name)
                        reset()
                        body()
                    } finally {
                        withContext(NonCancellable) {
                            reset()
                            setEnabledIme(enabled.toTypedArray())
                            if (originalIme in enabled) activateIme(originalIme)
                            focus(false)
                            deactivate(TEST_CONTEXT)
                            layouts.setValue(originalLayouts)
                            originalFiles.forEach { (file, bytes) ->
                                if (bytes != null) file.writeBytes(bytes)
                                else if (file.exists()) assertTrue(file.delete())
                            }
                            reloadConfig()
                        }
                    }
                }
            }
        } finally { FcitxDaemon.disconnect(client) }
    }

    private fun RawConfig.findRecursively(key: String): RawConfig? =
        if (name == key) this else subItems.orEmpty().firstNotNullOfOrNull { it.findRecursively(key) }

    private fun configRoot(): File {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        return app.getExternalFilesDir(null) ?: app.filesDir
    }

    private companion object {
        const val XIAOHE = "shuangpin-xiaohe"
        const val TEST_CONTEXT = -181118
    }
}
