package org.fxboomk.fcitx5.android.input.predict

internal enum class LanLlmLanguage {
    Chinese,
    English,
}

internal object LanLlmLanguageDetector {
    private val hanRegex = Regex("\\p{IsHan}")
    private val latinLetterRegex = Regex("[A-Za-z]")

    fun detect(text: String): LanLlmLanguage {
        val normalized = text.trim()
        if (normalized.isBlank()) return LanLlmLanguage.Chinese
        val hanCount = hanRegex.findAll(normalized).count()
        val latinCount = latinLetterRegex.findAll(normalized).count()
        if (latinCount == 0) return LanLlmLanguage.Chinese
        if (hanCount == 0) return LanLlmLanguage.English
        return if (latinCount >= hanCount * 2) LanLlmLanguage.English else LanLlmLanguage.Chinese
    }

    fun prefersLatinSuggestions(text: String): Boolean = detect(text) == LanLlmLanguage.English
}
