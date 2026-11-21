/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.fxboomk.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateCharacterCardDataTest {
    @Test
    fun shippedPinyinDataResolvesPolyphonicWordsAndUnlistedPhrases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = mapOf(
            "银行" to "yín háng",
            "行走" to "xíng zǒu",
            "重庆" to "chóng qìng",
            "重量" to "zhòng liàng",
            "五笔" to "wǔ bǐ",
            "我在银行上班" to "wǒ zài yín háng shàng bān",
        )
        for ((candidate, pinyin) in expected) {
            val card = CandidateCardContent.load(context, candidate)
            assertEquals(candidate, pinyin, card.phraseReading)
            assertEquals(pinyin.split(' '), card.characters.map { it.reading })
        }
    }

    @Test
    fun unicodeDescriptionsCoverEveryCjkBlockIncludingSupplementaryBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ranges = listOf(
            "A" to 0x3400..0x4DBF,
            "B" to 0x20000..0x2A6DF,
            "C" to 0x2A700..0x2B73F,
            "D" to 0x2B740..0x2B81F,
            "E" to 0x2B820..0x2CEAF,
            "F" to 0x2CEB0..0x2EBEF,
            "G" to 0x30000..0x3134F,
            "H" to 0x31350..0x323AF,
            "I" to 0x2EBF0..0x2EE5F,
            "J" to 0x323B0..0x3347F,
        ).map { (extension, range) ->
            range to context.getString(R.string.candidate_card_unicode_extension, extension)
        } + listOf(
            0x4E00..0x9FFF to context.getString(R.string.candidate_card_unicode_cjk),
            0xF900..0xFAFF to context.getString(R.string.candidate_card_unicode_compatibility),
            0x2F800..0x2FA1F to context.getString(R.string.candidate_card_unicode_compatibility_supplement),
        )
        for ((range, block) in ranges) {
            for (codePoint in listOf(range.first, range.last)) {
                val character = String(Character.toChars(codePoint))
                val expected = context.getString(
                    R.string.candidate_card_unicode, block, String.format(Locale.ROOT, "U+%04X", codePoint)
                )
                assertEquals(expected, candidateUnicodeDescription(context, character))
            }
        }
    }

    @Test(timeout = 5_000)
    fun looksUpCommonCharactersAndTableBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((character, strokes) in listOf("中" to 4, "华" to 6, "五" to 4, "拼" to 9, "㐀" to 5, "𳑹" to 15)) {
            assertEquals(character, strokes, CandidateCharacterCardData.load(context, character)?.strokeCount)
        }
        assertNull(CandidateCharacterCardData.load(context, "㏿"))
        assertNull(CandidateCharacterCardData.load(context, "䷀"))
        assertNull(CandidateCharacterCardData.load(context, String(Character.toChars(0x40000))))
    }
}
