/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validate the packaged pinyin engine while restoring the user's saved configuration. */
class PinyinAnnotationDeviceTest {
    @Test
    fun annotationChoicesExposeTheRareDefaultAndTranslatedDisplayNames() = runBlocking {
        for (name in listOf(PINYIN, XIAOHE)) {
            withIme(name) {
                val option = requireNotNull(getImConfig(name)["desc"].findRecursively("PinyinAnnotation"))
                assertEquals(RARE, option["DefaultValue"].value)
                assertEquals(listOf(NONE, RARE, ALL, AUTO), option["Enum"].subItems.orEmpty().map { it.value })
                val labels = option["EnumI18n"].subItems.orEmpty().map { it.value }
                Log.i("PinyinAnnotationTest", "$name annotation labels: $labels")
                assertEquals(listOf(NONE, RARE, ALL, AUTO)
                    .map { translate(it, TRANSLATION_DOMAIN) }, labels)
                if (labels[1] == "仅生僻字") {
                    assertEquals("所有候选", labels[2])
                    assertEquals("自动判定", labels[3])
                }
            }
        }
    }

    @Test
    fun legacyAllCandidatesValuePersistsAndAnnotatesAnOrdinaryPhrase() = withIme(PINYIN) {
        annotationMode(PINYIN, ALL)
        reloadConfig()
        assertEquals(ALL, getImConfig(PINYIN)["cfg"]["PinyinAnnotation"].value)
        val phrase = requireCandidate("nihao", "你好", ALL)
        assertTrue("All candidates lost the ordinary phrase's pinyin", phrase.comment.isNotEmpty())
        annotationMode(PINYIN, NONE)
        assertEquals("", requireCandidate("nihao", "你好", NONE).comment)
    }

    @Test
    fun automaticOnlyAnnotatesLaterMembersOfAnOrdinarySameTextGroup() = withIme(PINYIN) {
        annotationMode(PINYIN, AUTO)
        reloadConfig()
        assertEquals(AUTO, getImConfig(PINYIN)["cfg"]["PinyinAnnotation"].value)
        val (input, automatic) = listOf("d", "de", "di", "s", "shi", "c", "chang", "zhong", "n")
            .firstNotNullOfOrNull { input ->
                val choices = candidates(input)
                if (choices.groupingBy { it.text }.eachCount().any { it.value > 1 }) input to choices else null
            } ?: error("The packaged dictionary did not produce an ordinary same-text candidate group")
        val seen = mutableSetOf<String>()
        for (candidate in automatic) {
            val laterMember = !seen.add(candidate.text)
            assertEquals("Automatic annotation for $input/${candidate.text}: $automatic",
                laterMember, candidate.comment.isNotEmpty())
        }
        annotationMode(PINYIN, ALL)
        val all = candidates(input)
        assertEquals("Changing annotation mode reordered candidates", automatic.map { it.text }, all.map { it.text })
        val repeatedText = automatic.groupingBy { it.text }.eachCount().entries.first { it.value > 1 }.key
        assertTrue("All candidates must annotate the first member of a same-text group",
            all.first { it.text == repeatedText }.comment.isNotEmpty())
    }

    @Test
    fun rareCharactersUseThe8105CharacterTableIncludingSupplementaryCharacters() = withIme(PINYIN) {
        annotationMode(PINYIN, RARE)
        for ((input, word) in listOf("ni" to "你", "bang" to "𠳐")) {
            val candidate = candidates(input).firstOrNull { it.text == word }
            assertTrue("The packaged dictionary lacks normative character $input/$word", candidate != null)
            assertEquals("A character in the normative table was classified as rare: $word", "", candidate!!.comment)
        }
        val rare = candidates("pi").firstOrNull { it.text == "䫠" }
        assertTrue("The packaged dictionary lacks the outside-table character 䫠", rare != null)
        assertTrue("The outside-table character 䫠 lost its pinyin", rare!!.comment.isNotEmpty())
        assertEquals("Rare-only mode must not annotate ordinary phrases", "",
            candidates("nihao").first { it.text == "你好" }.comment)
    }

    @Test
    fun automaticPreservesStrokePinyinAndRareDoesNotAnnotateNormativeStrokeCharacters() = withIme(PINYIN) {
        val comments = mutableMapOf<String, String>()
        for (mode in listOf(ALL, AUTO, RARE, NONE)) {
            annotationMode(PINYIN, mode, stroke = true)
            val stroke = candidates("ppp").firstOrNull { it.text == "彡" }
            assertTrue("The packaged stroke dictionary lacks 彡", stroke != null)
            comments[mode] = stroke!!.comment
        }
        assertTrue("Automatic lost the original stroke pinyin", comments.getValue(AUTO).isNotEmpty())
        assertEquals(comments.getValue(ALL), comments.getValue(AUTO))
        assertEquals("The outside-table stroke character 彡 lost its pinyin",
            comments.getValue(ALL), comments.getValue(RARE))
        assertEquals("", comments.getValue(NONE))
        annotationMode(PINYIN, RARE, stroke = true)
        val normative = candidates("hhh").firstOrNull { it.text == "三" }
        assertTrue("The packaged stroke dictionary lacks 三", normative != null)
        assertEquals("A normative stroke character was classified as rare", "", normative!!.comment)
    }

    @Test
    fun automaticPreservesShuangpinCorrectionPinyin() = withIme(XIAOHE) {
        annotationMode(XIAOHE, ALL)
        val all = candidates("fm")
        val corrections = all.filter { it.isFallback || it.isDeferredFallback }
        assertTrue("The real xiaohe engine did not produce a correction group for fm", corrections.isNotEmpty())
        assertTrue("All candidates lost correction pinyin", corrections.all { it.comment.isNotEmpty() })

        annotationMode(XIAOHE, AUTO)
        val automatic = candidates("fm")
        assertEquals("Automatic changed the correction group", corrections.map { it.text },
            automatic.filter { it.isFallback || it.isDeferredFallback }.map { it.text })
        for (correction in corrections) {
            assertEquals("Automatic changed correction pinyin for ${correction.text}", correction.comment,
                automatic.first { it.text == correction.text }.comment)
        }

        annotationMode(XIAOHE, NONE)
        val hidden = candidates("fm").filter { it.isFallback || it.isDeferredFallback }
        assertEquals(corrections.map { it.text }, hidden.map { it.text })
        assertTrue("Disabled annotation left correction pinyin visible", hidden.all { it.comment.isEmpty() })
    }

    private suspend fun FcitxAPI.annotationMode(name: String, mode: String, stroke: Boolean = false) {
        reset()
        val config = getImConfig(name)["cfg"].deepCopy()
        config.getOrCreate("PinyinAnnotation").value = mode
        config.getOrCreate("StrokeCandidateEnabled").value = if (stroke) "True" else "False"
        setImConfig(name, config)
        assertEquals(mode, getImConfig(name)["cfg"]["PinyinAnnotation"].value)
    }

    private suspend fun FcitxAPI.candidates(input: String): List<CandidateWord> {
        reset()
        for (key in input) {
            sendKey(key, states = KeyStates.Virtual.states)
            sendKey(key, states = KeyStates.Virtual.states, up = true)
        }
        return getCandidates(0, CANDIDATE_LIMIT).toList()
    }

    /** Like `candidates(input).first { it.text == word }`, but a miss reports the engine state. */
    private suspend fun FcitxAPI.requireCandidate(input: String, word: String, mode: String): CandidateWord {
        val list = candidates(input)
        list.firstOrNull { it.text == word }?.let { return it }
        val message = "Missing '$word' for '$input' in annotation mode '$mode': " +
            engineStateDiagnostics(PINYIN, list)
        reportEngineState(message)
        throw AssertionError(message)
    }

    private fun withIme(name: String, body: suspend FcitxAPI.() -> Unit) = runBlocking {
        val client = "PinyinAnnotationDeviceTest-$name"
        val connection = FcitxDaemon.connect(client)
        try {
            withTimeout(60_000) {
                connection.runOnReady {
                    val root = configRoot()
                    val files = listOf(File(root, "config/profile"), File(root, "config/conf/pinyin.conf"),
                        File(root, "config/conf/pinyin/$name.conf"))
                    val originalFiles = files.associateWith { it.takeIf(File::exists)?.readBytes() }
                    val layouts = AppPrefs.getInstance().internal.compactShuangpinInputMethods
                    val originalLayouts = layouts.getValue()
                    val enabled = enabledIme().map { it.uniqueName }
                    val originalIme = currentIme().uniqueName
                    try {
                        layouts.setValue(originalLayouts - name)
                        assertTrue("The installed APK lacks $name", availableIme().any { it.uniqueName == name })
                        setEnabledIme((listOf("keyboard-us") + enabled + name).distinct().toTypedArray())
                        activate(TEST_CONTEXT, "pinyin-annotation-test")
                        setCapFlags(CapabilityFlags(CapabilityFlag.Preedit, CapabilityFlag.Sensitive))
                        focus()
                        activateIme(name)
                        val config = getImConfig(name)["cfg"].deepCopy().apply {
                            for (option in listOf("Learning", "Prediction", "KeepCurrentContext", "CloudPinyinEnabled",
                                "SpellEnabled", "SymbolsEnabled", "ChaiziEnabled", "StrokeCandidateEnabled")) {
                                getOrCreate(option).value = "False"
                            }
                            getOrCreate("ExtBEnabled").value = "True"
                            getOrCreate("WordCandidateLimit").value = "0"
                            getOrCreate("LongWordLengthLimit").value = "0"
                            getOrCreate("PartialSpLength").value = "2"
                            getOrCreate("Fuzzy").apply {
                                subItems?.forEach { option ->
                                    if (option.value == "True" || option.value == "False") option.value = "False"
                                }
                                getOrCreate("Correction").value = "None"
                            }
                        }
                        setImConfig(name, config)
                        reset()
                        sendKey(KeySym(0x0200E100), KeyStates.Virtual)
                        sendKey(KeySym(0x0200E100), KeyStates.Virtual, up = true)
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
                                if (bytes != null) {
                                    file.parentFile!!.mkdirs()
                                    file.writeBytes(bytes)
                                    assertArrayEquals("Failed to restore $file", bytes, file.readBytes())
                                } else if (file.exists()) {
                                    assertTrue("Failed to remove test-created $file", file.delete())
                                }
                            }
                            reloadConfig()
                        }
                    }
                }
            }
        } finally {
            FcitxDaemon.disconnect(client)
        }
    }

    private fun RawConfig.deepCopy(): RawConfig = copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

    private fun RawConfig.findRecursively(key: String): RawConfig? =
        if (name == key) this else subItems.orEmpty().firstNotNullOfOrNull { it.findRecursively(key) }

    private fun configRoot(): File {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
        return app.getExternalFilesDir(null) ?: app.filesDir
    }

    private companion object {
        const val PINYIN = "pinyin"
        const val XIAOHE = "shuangpin-xiaohe"
        const val NONE = "Do not show"
        const val RARE = "Only rare characters"
        const val ALL = "Always show"
        const val AUTO = "Automatic"
        const val TRANSLATION_DOMAIN = "fcitx5-chinese-addons"
        const val TEST_CONTEXT = -260119
        const val CANDIDATE_LIMIT = 4096
    }
}
