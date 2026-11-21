/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context

internal data class CandidateCardContent(
    val text: String,
    val phraseReading: String?,
    val characters: List<CharacterDetail>,
    val partReadings: Map<String, String>,
    val wubiCode: String? = null,
) {
    data class CharacterDetail(
        val character: String,
        val reading: String?,
        val shape: CandidateCharacterCardData?,
        val wubiCode: String? = null,
    )

    companion object {
        fun load(
            context: Context,
            text: String,
            pinyinPreedit: String? = null,
            wubiCodes: List<String> = emptyList(),
        ): CandidateCardContent {
            val characters = text.codePoints().toArray().map { String(Character.toChars(it)) }
            val readings = if (characters.size == 1) {
                listOf(CandidatePinyin.allReadings(context, text).takeIf { it.isNotEmpty() }?.joinToString(" / "))
            } else {
                CandidatePinyin.readings(context, characters, pinyinPreedit)
            }
            val details = characters.mapIndexedNotNull { index, character ->
                if (!isCandidateHan(character.codePointAt(0))) return@mapIndexedNotNull null
                CharacterDetail(
                    character,
                    readings[index],
                    CandidateCharacterCardData.load(context, character),
                    wubiCodes.getOrNull(index + 1),
                )
            }
            val phraseReading = if (details.isEmpty()) null else characters.mapIndexedNotNull { index, character ->
                when {
                    character.isBlank() -> null
                    isCandidateHan(character.codePointAt(0)) -> readings[index] ?: "—"
                    else -> character
                }
            }.joinToString(" ")
            val partReadings = details.flatMap { it.shape?.parts.orEmpty() }.distinct().mapNotNull { part ->
                CandidatePinyin.firstReading(context, part)?.let { part to it }
            }.toMap()
            return CandidateCardContent(text, phraseReading, details, partReadings, wubiCodes.firstOrNull())
        }
    }
}
