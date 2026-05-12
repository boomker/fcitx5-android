package org.fxboomk.fcitx5.android.input.predict

internal enum class LlmLanguage {
    Chinese,
    English,
}

internal object LlmLanguageDetector {
    private val hanRegex = Regex("\\p{IsHan}")
    private val latinLetterRegex = Regex("[A-Za-z]")

    fun detect(text: String): LlmLanguage {
        val normalized = text.trim()
        if (normalized.isBlank()) return LlmLanguage.Chinese
        val hanCount = hanRegex.findAll(normalized).count()
        val latinCount = latinLetterRegex.findAll(normalized).count()
        if (latinCount == 0) return LlmLanguage.Chinese
        if (hanCount == 0) return LlmLanguage.English
        return if (latinCount >= hanCount * 2) LlmLanguage.English else LlmLanguage.Chinese
    }

    fun prefersLatinSuggestions(text: String): Boolean = detect(text) == LlmLanguage.English
}
