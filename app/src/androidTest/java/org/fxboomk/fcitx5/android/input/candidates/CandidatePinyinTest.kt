/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePinyinTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allCharacterReadingsAreReturnedWithoutChangingTheFirstReading() {
        for ((character, expected) in mapOf(
            "行" to listOf("háng", "xíng"),
            "重" to listOf("zhòng", "chóng"),
            "好" to listOf("hǎo", "hào"),
        )) {
            assertEquals(character, expected, CandidatePinyin.allReadings(context, character))
            assertEquals(character, expected.first(), CandidatePinyin.firstReading(context, character))
        }
        assertTrue(CandidatePinyin.allReadings(context, "").isEmpty())
        assertTrue(CandidatePinyin.allReadings(context, "A").isEmpty())
    }

    @Test
    fun completePreeditSelectsTheCorrespondingPolyphonicReading() {
        assertEquals(listOf("hěn", "xíng"), readings("很行", "hen xing"))
        assertEquals(listOf("hěn", "háng"), readings("很行", "hen hang"))
    }

    @Test
    fun preeditOverridesAnIncompatibleDictionaryReadingAndKeepsAMatchingTone() {
        assertEquals(listOf("yín", "háng"), readings("银行", null))
        assertEquals(listOf("yín", "xíng"), readings("银行", "yin xing"))
        assertEquals(listOf("yín", "háng"), readings("银行", "yin hang"))
        assertEquals(listOf("chóng", "qìng"), readings("重庆", "chong qing"))
        assertEquals(listOf("zhòng", "liàng"), readings("重量", "zhong liang"))
    }

    @Test
    fun rawAbbreviatedIncompleteOrMisalignedPreeditCannotChangeTheWord() {
        val baseline = readings("银行", null)
        for (preedit in listOf("yb hh", "y x", "yin x", "yin", "", "hello world",
            "ni xing", "xing yin", "yin xing!", "yin2 xing2")) {
            assertEquals(preedit, baseline, readings("银行", preedit))
        }
        assertEquals(readings("很行", null), readings("很行", "hf xk"))
    }

    @Test
    fun whitespaceApostrophesAndSelectedPrefixesKeepSyllablesAligned() {
        val expected = listOf("hěn", "xíng")
        for (preedit in listOf(" HEN  XING ", "hen'xing", "hen’xing",
            "我hen xing", "我 hen xing", "𠀀hen xing", "hen xing shi")) {
            assertEquals(preedit, expected, readings("很行", preedit))
        }
    }

    @Test
    fun umlautSpellingsRemainDistinctFromPlainU() {
        for (preedit in listOf("lü xing", "lv xing", "LU: XING", "lǜ xíng")) {
            assertEquals(preedit, listOf("lǜ", "xíng"), readings("绿行", preedit))
        }
        assertEquals(listOf("lù", "xíng"), readings("绿行", "lu xing"))
        assertEquals(listOf("lǚ", "xíng"), readings("旅行", "lv xing"))
    }

    @Test
    fun mixedCandidatesAndSingleCharactersKeepTheirUnconstrainedReading() {
        assertEquals(readings("银A行", null), readings("银A行", "yin xing"))
        assertEquals(readings("行", null), readings("行", "xing"))
    }

    private fun readings(candidate: String, preedit: String?): List<String?> =
        CandidatePinyin.readings(
            context,
            candidate.codePoints().toArray().map { String(Character.toChars(it)) },
            preedit,
        )
}
