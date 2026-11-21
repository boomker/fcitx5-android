/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import java.util.Locale
import org.fxboomk.fcitx5.android.R

internal fun isCandidateHan(codePoint: Int): Boolean =
    Character.isIdeographic(codePoint) || cjkExtension(codePoint) != null

// Unicode 17 block ranges. Android's own Unicode tables can be older.
private fun cjkExtension(codePoint: Int): String? = when (codePoint) {
    in 0x3400..0x4DBF -> "A"
    in 0x20000..0x2A6DF -> "B"
    in 0x2A700..0x2B73F -> "C"
    in 0x2B740..0x2B81F -> "D"
    in 0x2B820..0x2CEAF -> "E"
    in 0x2CEB0..0x2EBEF -> "F"
    in 0x30000..0x3134F -> "G"
    in 0x31350..0x323AF -> "H"
    in 0x2EBF0..0x2EE5F -> "I"
    in 0x323B0..0x3347F -> "J"
    else -> null
}

internal fun candidateUnicodeDescription(context: Context, character: String): String {
    val codePoint = character.codePointAt(0)
    val extension = cjkExtension(codePoint)
    val block = when {
        extension != null -> context.getString(R.string.candidate_card_unicode_extension, extension)
        codePoint in 0x4E00..0x9FFF -> context.getString(R.string.candidate_card_unicode_cjk)
        codePoint in 0xF900..0xFAFF -> context.getString(R.string.candidate_card_unicode_compatibility)
        codePoint in 0x2F800..0x2FA1F -> context.getString(R.string.candidate_card_unicode_compatibility_supplement)
        else -> Character.UnicodeBlock.of(codePoint)?.toString()?.replace('_', ' ')
            ?: context.getString(R.string.candidate_card_unicode_unknown)
    }
    val point = "U+" + codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
    return context.getString(R.string.candidate_card_unicode, block, point)
}
