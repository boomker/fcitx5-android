/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.util.SparseIntArray
import java.text.Normalizer
import java.util.Locale

internal object CandidatePinyin {
    private const val MAX_PHRASE_LENGTH = 10 // Upper bound in the generated phrase table.
    private const val CHARACTER_ASSET = "usr/share/fcitx5/pinyinhelper/py_table.mb"
    private val phrases = CandidateCardTable("candidate-card/phrase-pinyin.tsv")

    // Indices from Fcitx's pinyinlookup.cpp. Row format: UTF-8 length, character,
    // reading count, then (initial, final, tone) bytes for each reading.
    private val initials = " b c ch d f g h j k l m n ng p q r s sh t w x y z zh".split(' ')
    private val finals = " a ai an ang ao e ei en eng er i ia ian iang iao ie in ing iong iu m n ng o ong ou u ua uai uan uang ue ueng ui un uo ü üan üe ün".split(' ')
    private val toneMarks = charArrayOf('\u0000', '\u0304', '\u0301', '\u030c', '\u0300')

    @Volatile
    private var characterTable: CharacterTable? = null

    fun firstReading(context: Context, character: String): String? =
        allReadings(context, character).firstOrNull()

    fun allReadings(context: Context, character: String): List<String> {
        if (character.isEmpty()) return emptyList()
        val table = characterTable ?: synchronized(this) {
            characterTable ?: CharacterTable(context.assets.open(CHARACTER_ASSET).use { it.readBytes() })
                .also { characterTable = it }
        }
        return table.allReadings(character.codePointAt(0))
    }

    /** Resolve whole words first, then use the longest known phrase at each position. */
    fun readings(
        context: Context,
        characters: List<String>,
        preedit: String? = null,
    ): List<String?> {
        val result = arrayOfNulls<String>(characters.size)
        var start = 0
        while (start < characters.size) {
            if (!isCandidateHan(characters[start].codePointAt(0))) {
                start++
                continue
            }
            var runEnd = start + 1
            while (runEnd < characters.size && isCandidateHan(characters[runEnd].codePointAt(0))) runEnd++
            while (start < runEnd) {
                var end = minOf(start + MAX_PHRASE_LENGTH, runEnd)
                var readings: List<String>? = null
                while (end > start + 1) {
                    val phrase = characters.subList(start, end).joinToString("")
                    val match = phrases.lookup(context, phrase)
                    if (match != null) {
                        readings = match.split(' ')
                        break
                    }
                    end--
                }
                if (readings == null) {
                    result[start] = firstReading(context, characters[start])
                    start++
                } else {
                    readings.forEachIndexed { index, reading -> result[start + index] = reading }
                    start = end
                }
            }
        }
        if (characters.size > 1 && preedit != null) {
            constrainToPreedit(context, characters, result, preedit)
        }
        return result.asList()
    }

    private fun constrainToPreedit(
        context: Context,
        characters: List<String>,
        readings: Array<String?>,
        preedit: String,
    ) {
        if (characters.any { !isCandidateHan(it.codePointAt(0)) }) return
        // A selected sentence precedes the remaining syllables in Fcitx's preedit.
        var offset = 0
        while (offset < preedit.length) {
            val codePoint = preedit.codePointAt(offset)
            if (!Character.isWhitespace(codePoint) && !isCandidateHan(codePoint)) break
            offset += Character.charCount(codePoint)
        }
        val syllables = preedit.substring(offset).trim()
            .split(Regex("[\\s'’]+"))
            .filter { it.isNotEmpty() }
            .map(::withoutTone)
        if (syllables.size < characters.size || syllables.any { syllable ->
            syllable.isEmpty() || syllable.any { it !in 'a'..'z' && it != 'ü' }
        }) return

        // Validate the entire prefix before changing anything: initials, raw shuangpin
        // keys and an unrelated composition must not partially override a word's reading.
        val constrained = characters.mapIndexed { index, character ->
            val syllable = syllables[index]
            readings[index]?.takeIf { withoutTone(it) == syllable }
                ?: allReadings(context, character).firstOrNull { withoutTone(it) == syllable }
                ?: return
        }
        constrained.forEachIndexed { index, reading -> readings[index] = reading }
    }

    private fun withoutTone(reading: String): String {
        val normalized = reading.lowercase(Locale.ROOT).replace("u:", "ü").replace('v', 'ü')
        val unmarked = Normalizer.normalize(normalized, Normalizer.Form.NFD)
            .filterNot { it == '\u0304' || it == '\u0301' || it == '\u030c' || it == '\u0300' }
        return Normalizer.normalize(unmarked, Normalizer.Form.NFC)
    }

    private class CharacterTable(private val bytes: ByteArray) {
        private val positions = SparseIntArray()

        init {
            var offset = 0
            while (offset < bytes.size) {
                val size = bytes[offset++].toInt() and 0xff
                require(size in 1..4 && offset + size < bytes.size)
                val codePoint = bytes.decodeToString(offset, offset + size).codePointAt(0)
                offset += size
                val end = offset + 1 + (bytes[offset].toInt() and 0xff) * 3
                require(end <= bytes.size)
                positions.append(codePoint, offset)
                offset = end
            }
        }

        fun allReadings(codePoint: Int): List<String> {
            val offset = positions.get(codePoint, -1)
            if (offset < 0) return emptyList()
            val count = bytes[offset].toInt() and 0xff
            val readings = mutableListOf<String>()
            for (index in 0 until count) {
                val start = offset + 1 + index * 3
                val initial = initials.getOrNull(bytes[start].toInt() and 0xff) ?: continue
                val final = finals.getOrNull(bytes[start + 1].toInt() and 0xff) ?: continue
                if (initial.isEmpty() && final.isEmpty()) continue
                readings += initial + withTone(final, bytes[start + 2].toInt() and 0xff)
            }
            return readings.distinct()
        }
    }

    private fun withTone(final: String, tone: Int): String {
        if (tone !in 1..4 || final.isEmpty()) return final
        val vowel = when {
            'a' in final -> final.indexOf('a')
            'e' in final -> final.indexOf('e')
            final == "ou" -> 0
            else -> final.indexOfLast { it in "iouü" }.coerceAtLeast(0)
        }
        val marked = final.substring(0, vowel + 1) + toneMarks[tone] + final.substring(vowel + 1)
        return Normalizer.normalize(marked, Normalizer.Form.NFC)
    }
}
