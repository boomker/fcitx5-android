package org.fxboomk.fcitx5.android.input.predict

internal object LanLlmRequestGate {
    private const val LONG_DIGIT_MIN_LENGTH = 6
    private const val LONG_DIGIT_MIN_INTERVAL_MS = 1_500L

    fun shouldRequestPrediction(beforeCursor: String): Boolean {
        val text = beforeCursor.trim()
        if (text.isBlank()) return false
        return containsHanCharacter(text) || text.any { isAsciiLatinLetter(it) || it.isDigit() }
    }

    fun isPureLongDigitInput(beforeCursor: String): Boolean =
        beforeCursor.trim().let { text ->
            text.length >= LONG_DIGIT_MIN_LENGTH && text.all(Char::isDigit)
        }

    fun shouldThrottlePureLongDigitInput(
        beforeCursor: String,
        lastRequestAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isPureLongDigitInput(beforeCursor)) return false
        if (lastRequestAtMs <= 0L) return false
        return nowMs - lastRequestAtMs < LONG_DIGIT_MIN_INTERVAL_MS
    }

    // Avoid Unicode regex classes here because Android 9's regex engine rejects \p{IsHan}.
    private fun containsHanCharacter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (isHanCodePoint(codePoint)) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun isAsciiLatinLetter(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z'

    private fun isHanCodePoint(codePoint: Int): Boolean =
        codePoint == 0x3007 ||
            codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F ||
            codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0x2CEB0..0x2EBEF ||
            codePoint in 0x2F800..0x2FA1F ||
            codePoint in 0x30000..0x3134F ||
            codePoint in 0x31350..0x323AF
}
