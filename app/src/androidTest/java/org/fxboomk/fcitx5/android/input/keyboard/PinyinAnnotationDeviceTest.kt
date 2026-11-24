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
import org.junit.Assert.assertFalse
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
                assertEquals(listOf(NONE, RARE, AUTO, ALL), option["Enum"].subItems.orEmpty().map { it.value })
                val labels = option["EnumI18n"].subItems.orEmpty().map { it.value }
                Log.i("PinyinAnnotationTest", "$name annotation labels: $labels")
                assertEquals(listOf(NONE, RARE, "Only polyphonic characters", "All single characters")
                    .map { translate(it, TRANSLATION_DOMAIN) }, labels)
                if (labels[1] == "仅生僻字") {
                    assertEquals("仅多音字", labels[2])
                    assertEquals("所有单字", labels[3])
                }
                val placement = requireNotNull(getImConfig(name)["desc"]
                    .findRecursively("PinyinAnnotationPlacement"))
                assertEquals(RIGHT, placement["DefaultValue"].value)
                assertEquals(listOf(TOP, RIGHT), placement["Enum"].subItems.orEmpty().map { it.value })
                val placementLabels = placement["EnumI18n"].subItems.orEmpty().map { it.value }
                assertEquals(listOf(TOP, RIGHT).map { translate(it, TRANSLATION_DOMAIN) }, placementLabels)
                if (placementLabels[0] == "顶部") assertEquals("右侧", placementLabels[1])
            }
        }
    }

    @Test
    fun placementPersistsAndReachesOrdinaryViewportAndExpandedCandidates() = withIme(PINYIN) {
        annotationMode(PINYIN, ALL)
        annotationPlacement(PINYIN, TOP)
        reloadConfig()
        assertEquals(TOP, getImConfig(PINYIN)["cfg"]["PinyinAnnotationPlacement"].value)
        val top = candidates("ni")
        val single = top.first { it.text == "你" }
        assertEquals("(nǐ)", single.comment)
        assertTrue(single.isPinyinAnnotationOnTop)
        assertTrue("Unannotated candidates acquired a top annotation",
            top.filter { it.comment.isEmpty() }.none { it.isPinyinAnnotationOnTop })
        val viewport = getCandidateViewportSnapshot(512).candidates.first { it.text == "你" }
        assertTrue("The viewport lost the top annotation metadata", viewport.isPinyinAnnotationOnTop)
        val expanded = getExpandedCandidates(0, 512, 0).first { it.text == "你" }
        assertTrue("Expanded candidates lost the top annotation metadata", expanded.isPinyinAnnotationOnTop)
        assertFalse(requireCandidate("nihao", "你好", ALL).isPinyinAnnotationOnTop)

        annotationPlacement(PINYIN, RIGHT)
        reloadConfig()
        assertEquals(RIGHT, getImConfig(PINYIN)["cfg"]["PinyinAnnotationPlacement"].value)
        val right = candidates("ni")
        assertEquals("Placement changes reordered candidates", top.map { it.text }, right.map { it.text })
        assertTrue("Right-side placement retained top metadata", right.none { it.isPinyinAnnotationOnTop })
        assertEquals("(nǐ)", right.first { it.text == "你" }.comment)
    }

    @Test
    fun placementMetadataIncludesStrokeAndCorrectionPinyin() = runBlocking {
        withIme(PINYIN) {
            annotationMode(PINYIN, ALL, stroke = true)
            annotationPlacement(PINYIN, TOP)
            val stroke = requireCandidate("ppp", "彡", ALL)
            assertTrue("Stroke pinyin lost top-placement metadata", stroke.isPinyinAnnotationOnTop)
            assertTrue(stroke.comment in setOf("(shān)", "(xiǎn)"))
        }
        withIme(XIAOHE) {
            annotationMode(XIAOHE, ALL)
            annotationPlacement(XIAOHE, TOP)
            val corrections = candidates("fm").filter { it.isFallback || it.isDeferredFallback }
            assertTrue(corrections.isNotEmpty())
            val singles = corrections.filter { it.text.codePointCount(0, it.text.length) == 1 }
            assertTrue(singles.isNotEmpty())
            assertTrue("Correction pinyin lost top-placement metadata", singles.all { it.isPinyinAnnotationOnTop })
            assertTrue("Correction phrases acquired top annotation metadata",
                corrections.filter { it.text.codePointCount(0, it.text.length) != 1 }
                    .none { it.isPinyinAnnotationOnTop })
            annotationMode(XIAOHE, NONE)
            assertTrue("Hidden correction pinyin retained top metadata",
                candidates("fm").none { it.isPinyinAnnotationOnTop })
        }
    }

    @Test
    fun legacyAllCandidatesValuePersistsAndOnlyAnnotatesSingleCharacters() = withIme(PINYIN) {
        annotationMode(PINYIN, ALL)
        reloadConfig()
        assertEquals(ALL, getImConfig(PINYIN)["cfg"]["PinyinAnnotation"].value)
        val phrase = requireCandidate("nihao", "你好", ALL)
        assertEquals("All single characters must not annotate phrases", "", phrase.comment)
        assertEquals("(nǐ)", requireCandidate("ni", "你", ALL).comment)
        annotationMode(PINYIN, NONE)
        assertEquals("", requireCandidate("nihao", "你好", NONE).comment)
        assertEquals("", requireCandidate("ni", "你", NONE).comment)
    }

    @Test
    fun allSingleCharactersUseOneMatchingReadingWithTone() = withIme(PINYIN) {
        annotationMode(PINYIN, ALL)
        for ((input, word, expected) in listOf(
            Triple("ni", "你", "(nǐ)"),
            Triple("lv", "吕", "(lǚ)"),
            Triple("xing", "行", "(xíng)"),
            Triple("hang", "行", "(háng)"),
            Triple("hao", "好", "(hǎo)"),
            Triple("bo", "薄", "(bó)"),
            Triple("de", "得", "(de)"),
            Triple("de", "的", "(de)"),
            Triple("zhong", "重", "(zhòng)"),
            Triple("chong", "重", "(chóng)"),
        )) {
            assertEquals("Wrong single-character reading for $input/$word", expected,
                requireCandidate(input, word, ALL).comment)
        }
        assertEquals("", requireCandidate("nihao", "你好", ALL).comment)
    }

    @Test
    fun legacyAutomaticValueOnlyAnnotatesTheOtherPolyphonicReading() = withIme(PINYIN) {
        annotationMode(PINYIN, AUTO)
        reloadConfig()
        assertEquals(AUTO, getImConfig(PINYIN)["cfg"]["PinyinAnnotation"].value)
        for ((input, expected) in listOf("xing" to "(háng)", "hang" to "(xíng)")) {
            val polyphonic = candidates(input)
            val matching = polyphonic.filter { it.text == "行" }
            assertTrue("The packaged dictionary lacks $input/行", matching.isNotEmpty())
            assertTrue("Only polyphonic characters must annotate every matching candidate",
                matching.all { it.comment == expected })
            annotationMode(PINYIN, ALL)
            assertEquals("Changing annotation mode reordered candidates for $input",
                polyphonic.map { it.text }, candidates(input).map { it.text })
            annotationMode(PINYIN, AUTO)
        }
        assertEquals("Monophonic characters must not have an annotation", "",
            requireCandidate("ni", "你", AUTO).comment)
        assertEquals("Only polyphonic characters must not annotate phrases", "",
            requireCandidate("nihao", "你好", AUTO).comment)
    }

    @Test
    fun polyphonicCharactersUseTheMostCommonOtherReadingIncludingToneDifferences() = withIme(PINYIN) {
        annotationMode(PINYIN, AUTO)
        for ((input, word, expected) in listOf(
            Triple("hao", "好", "(hào)"),
            Triple("de", "得", "(dé)"),
            Triple("de", "的", "(dì)"),
            Triple("le", "乐", "(yuè)"),
            Triple("zhong", "重", "(chóng)"),
            Triple("chong", "重", "(zhòng)"),
        )) {
            assertEquals("Wrong alternative reading for $input/$word", expected,
                requireCandidate(input, word, AUTO).comment)
        }
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
    fun strokeCandidatesRespectSingleCharacterAndPolyphonicModes() = withIme(PINYIN) {
        val comments = mutableMapOf<String, String>()
        for (mode in listOf(ALL, AUTO, RARE, NONE)) {
            annotationMode(PINYIN, mode, stroke = true)
            val stroke = candidates("ppp").firstOrNull { it.text == "彡" }
            assertTrue("The packaged stroke dictionary lacks 彡", stroke != null)
            comments[mode] = stroke!!.comment
        }
        for (mode in listOf(ALL, AUTO, RARE)) {
            assertTrue("Stroke annotation for 彡 must contain one reading in $mode",
                comments.getValue(mode) in setOf("(shān)", "(xiǎn)"))
        }
        assertEquals("The outside-table stroke character 彡 lost its pinyin",
            comments.getValue(ALL), comments.getValue(RARE))
        assertEquals("", comments.getValue(NONE))
        for (mode in listOf(RARE, AUTO, ALL)) {
            annotationMode(PINYIN, mode, stroke = true)
            val normative = requireCandidate("hhh", "三", mode)
            assertEquals("Wrong stroke annotation for monophonic 三 in $mode",
                if (mode == ALL) "(sān)" else "", normative.comment)
        }
    }

    @Test
    fun shuangpinCorrectionsRespectAnnotationModesWithoutChangingOrder() = withIme(XIAOHE) {
        annotationMode(XIAOHE, ALL)
        val all = candidates("fm")
        val corrections = all.filter { it.isFallback || it.isDeferredFallback }
        assertTrue("The real xiaohe engine did not produce a correction group for fm", corrections.isNotEmpty())
        val singleCorrections = corrections.filter { it.text.codePointCount(0, it.text.length) == 1 }
        assertTrue("The real xiaohe correction group contains no single characters", singleCorrections.isNotEmpty())
        for (correction in corrections) {
            if (correction.text.codePointCount(0, correction.text.length) == 1) {
                assertTrue("All single characters lost correction pinyin for ${correction.text}",
                    correction.comment.isNotEmpty())
                assertTrue("A correction must display only one reading: $correction",
                    correction.comment.none { it == '\'' || it.isWhitespace() })
            } else {
                assertEquals("All single characters annotated a correction phrase", "", correction.comment)
            }
        }

        annotationMode(XIAOHE, AUTO)
        val automatic = candidates("fm")
        val polyphonicCorrections = automatic.filter { it.isFallback || it.isDeferredFallback }
        assertEquals("Only polyphonic characters changed the correction group", corrections.map { it.text },
            polyphonicCorrections.map { it.text })
        for (correction in polyphonicCorrections) {
            if (correction.text.codePointCount(0, correction.text.length) != 1) {
                assertEquals("Only polyphonic characters annotated a correction phrase", "", correction.comment)
            }
            assertTrue("A polyphonic correction must display only one reading: $correction",
                correction.comment.none { it == '\'' || it.isWhitespace() })
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

    private suspend fun FcitxAPI.annotationPlacement(name: String, placement: String) {
        reset()
        val config = getImConfig(name)["cfg"].deepCopy()
        config.getOrCreate("PinyinAnnotationPlacement").value = placement
        setImConfig(name, config)
        assertEquals(placement, getImConfig(name)["cfg"]["PinyinAnnotationPlacement"].value)
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
        const val TOP = "Top"
        const val RIGHT = "Right"
        const val TRANSLATION_DOMAIN = "fcitx5-chinese-addons"
        const val TEST_CONTEXT = -260119
        const val CANDIDATE_LIMIT = 4096
    }
}
