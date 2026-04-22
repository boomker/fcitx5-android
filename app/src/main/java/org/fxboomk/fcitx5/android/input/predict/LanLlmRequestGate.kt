package org.fxboomk.fcitx5.android.input.predict

internal object LanLlmRequestGate {
    private const val LONG_DIGIT_MIN_LENGTH = 6
    private const val LONG_DIGIT_MIN_INTERVAL_MS = 1_500L

    private val hanRegex = Regex("\\p{IsHan}")
    private val latinLetterRegex = Regex("[A-Za-z]")
    private val digitRegex = Regex("\\d")
    private val longDigitOnlyRegex = Regex("^\\d{$LONG_DIGIT_MIN_LENGTH,}$")

    fun shouldRequestPrediction(beforeCursor: String): Boolean {
        val text = beforeCursor.trim()
        if (text.isBlank()) return false
        return hanRegex.containsMatchIn(text) ||
            latinLetterRegex.containsMatchIn(text) ||
            digitRegex.containsMatchIn(text)
    }

    fun isPureLongDigitInput(beforeCursor: String): Boolean =
        longDigitOnlyRegex.matches(beforeCursor.trim())

    fun shouldThrottlePureLongDigitInput(
        beforeCursor: String,
        lastRequestAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (!isPureLongDigitInput(beforeCursor)) return false
        if (lastRequestAtMs <= 0L) return false
        return nowMs - lastRequestAtMs < LONG_DIGIT_MIN_INTERVAL_MS
    }
}
