/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context

internal data class CandidateCharacterCardData(
    val radical: String,
    val strokeCount: Int,
    val parts: List<String>,
    val strokeOrder: String,
) {
    companion object {
        private val table = CandidateCardTable("candidate-card/characters.tsv")

        fun load(context: Context, character: String): CandidateCharacterCardData? {
            val fields = table.lookup(context, character)?.split('\t') ?: return null
            if (fields.size != 4) return null
            return CandidateCharacterCardData(
                radical = fields[0],
                strokeCount = fields[1].toIntOrNull() ?: 0,
                parts = fields[2].split(' ').filter(String::isNotBlank),
                strokeOrder = fields[3].split(' ').mapNotNull(::strokeShape).joinToString(""),
            )
        }

        private fun strokeShape(code: String): Char? = when (code) {
            "H" -> '一'
            "T" -> '㇀'
            "S" -> '丨'
            "SG" -> '亅'
            "P" -> '丿'
            "D" -> '丶'
            "N" -> '㇏'
            "HZ" -> '㇕'
            "HP" -> '㇇'
            "HG" -> '㇖'
            "HZG" -> '㇆'
            "HZT" -> '㇊'
            "HZW" -> '㇍'
            "HZZ" -> '㇅'
            "HXG" -> '⺄'
            "HZWG" -> '㇈'
            "HPWG" -> '㇌'
            "HZZP" -> '㇋'
            "HZZZG" -> '㇡'
            "HZZZ" -> '㇎'
            "SZ" -> '㇗'
            "SW" -> '㇄'
            "ST" -> '㇙'
            "SZP" -> 'ㄣ'
            "SZZ" -> '㇞'
            "SWG" -> '乚'
            "SZWG" -> '㇉'
            "PZ" -> '㇜'
            "PD" -> '㇛'
            "WG" -> '㇁'
            "XG" -> '㇂'
            "BXG" -> '㇃'
            else -> null
        }
    }
}
