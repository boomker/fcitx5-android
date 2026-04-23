package org.fxboomk.fcitx5.android.input.predict

object LanLlmSuggestionParser {
    private data class SuggestionLengthPolicy(
        val softLimit: Int,
        val hardLimit: Int,
    )

    private const val THINK_START = "<think>"
    private const val THINK_END = "</think>"
    private const val MAX_OVER_LIMIT_SUGGESTIONS = 1
    private val dialogueRoleRegex = Regex("""(?:\[\s*(?:对方|我)\s*])""")
    private val hanRegex = Regex("\\p{IsHan}")
    private val latinLetterRegex = Regex("[A-Za-z]")
    private val jsonFieldNoiseRegex = Regex(
        """^(?:id|object|model|role|index|created|finish_reason|system_fingerprint|response)\s*"?\s*:\s*"?[A-Za-z0-9._:-]{2,}"?$""",
        RegexOption.IGNORE_CASE
    )
    private val structuredTextRegex = Regex(
        """"type"\s*:\s*"text"[\s\S]*?"text"\s*:\s*"((?:\\.|[^"\\])*)""""
    )
    private val queryWideRegex = Regex(
        """(?i)\bquery\s*[:=]\s*(?:\"([^\"]{1,512})\"|'([^']{1,512})'|“([^”]{1,512})”|「([^」]{1,512})」|『([^』]{1,512})』|([^\s,;]{1,128}))"""
    )

    fun parse(raw: String, typedPrefix: String): List<String> {
        if (raw.isBlank()) return emptyList()

        val normalized = normalize(raw)
        candidatePayloads(normalized).forEach { payload ->
            val extracted = extractJsonCandidate(payload)
            val fromQuoted = extractQuotedSuggestions(extracted, typedPrefix)
            if (fromQuoted.isNotEmpty()) return fromQuoted
            if (looksLikeSuggestionsPayload(extracted)) return emptyList()

            val fromPlainText = extractPlainSuggestions(extracted, typedPrefix)
            if (fromPlainText.isNotEmpty()) return fromPlainText
        }

        if (looksStructuredPayload(normalized)) return emptyList()

        return normalized
            .let { extractPlainSuggestions(it, typedPrefix) }
    }

    fun parseJsonSuggestions(raw: String, typedPrefix: String): List<String> {
        if (raw.isBlank()) return emptyList()

        val normalized = normalize(raw)
        candidatePayloads(normalized).forEach { payload ->
            val extracted = extractJsonCandidate(payload)
            val suggestions = extractQuotedSuggestions(extracted, typedPrefix)
            if (suggestions.isNotEmpty()) return suggestions
            if (looksLikeSuggestionsPayload(extracted)) return emptyList()
        }

        return parse(normalized, typedPrefix)
    }

    private fun extractPlainSuggestions(raw: String, typedPrefix: String): List<String> =
        finalizeSuggestions(
            raw.lineSequence()
                .map { sanitizeSuggestion(it, typedPrefix) }
                .filter { it.isNotBlank() }
                .toList(),
            typedPrefix,
        )

    private fun normalize(raw: String): String = raw
        .replace(Regex("￾stats:.*$", RegexOption.DOT_MATCHES_ALL), "")
        .let { stripInvisibleAndControl(it) }
        .trim()

    private fun candidatePayloads(raw: String): List<String> = buildList {
        fun addCandidate(value: String?) {
            val candidate = value?.trim().orEmpty()
            if (candidate.isNotBlank() && candidate !in this) add(candidate)
        }

        addCandidate(extractFencePayload(raw))
        val structuredText = extractStructuredText(raw)
        val structuredPostThink = extractPostThinkContent(structuredText)
        addCandidate(structuredPostThink)
        if (structuredPostThink == null) addCandidate(structuredText)
        val openAiContent = extractOpenAiMessageContent(raw)
        val openAiPostThink = extractPostThinkContent(openAiContent)
        addCandidate(openAiPostThink)
        if (openAiPostThink == null) addCandidate(openAiContent)
        openAiContent?.let { nested ->
            addCandidate(extractFencePayload(nested))
        }
        addCandidate(extractPostThinkContent(raw))
        addCandidate(raw)
    }

    private fun extractFencePayload(raw: String): String? {
        val fenceStart = raw.indexOf("```")
        if (fenceStart < 0) return null
        val fenceEnd = raw.indexOf("```", fenceStart + 3)
        if (fenceEnd <= fenceStart) return null
        return raw.substring(fenceStart + 3, fenceEnd)
            .removePrefix("json")
            .removePrefix("JSON")
            .trim()
    }

    private fun extractOpenAiMessageContent(raw: String): String? {
        val contentKey = raw.indexOf("\"content\"")
        if (contentKey < 0) return null
        var index = raw.indexOf(':', contentKey)
        if (index < 0) return null
        index++
        while (index < raw.length && raw[index].isWhitespace()) index++
        if (index >= raw.length || raw[index] != '"') return null
        index++

        val out = StringBuilder()
        while (index < raw.length) {
            when (val ch = raw[index++]) {
                '\\' -> {
                    if (index >= raw.length) break
                    when (val escaped = raw[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 <= raw.length) {
                                val hex = raw.substring(index, index + 4)
                                hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                                index += 4
                            }
                        }
                        else -> out.append(escaped)
                    }
                }
                '"' -> return out.toString().ifBlank { null }
                else -> out.append(ch)
            }
        }
        return out.toString().ifBlank { null }
    }

    private fun extractStructuredText(raw: String): String? {
        val parts = structuredTextRegex.findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map(::decodeJsonString)
            .filter { it.isNotBlank() }
            .toList()
        if (parts.isEmpty()) return null
        return parts.joinToString(separator = "")
    }

    private fun decodeJsonString(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            when (val ch = raw[index++]) {
                '\\' -> {
                    if (index >= raw.length) break
                    when (val escaped = raw[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 <= raw.length) {
                                val hex = raw.substring(index, index + 4)
                                hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                                index += 4
                            }
                        }
                        else -> out.append(escaped)
                    }
                }
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun extractPostThinkContent(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        val thinkEnd = text.lastIndexOf(THINK_END)
        if (thinkEnd >= 0) {
            return text.substring(thinkEnd + THINK_END.length).trim().ifBlank { null }
        }
        if (text.startsWith(THINK_START)) {
            return null
        }
        return text
    }

    private fun looksLikeSuggestionsPayload(raw: String): Boolean {
        val text = raw.trim()
        return (text.startsWith("{") || text.startsWith("[")) && (
            "suggestions" in text ||
            "candidates" in text ||
            "completions" in text
        )
    }

    private fun looksStructuredPayload(raw: String): Boolean {
        val text = raw.trim()
        return text.startsWith("{") || text.startsWith("[") || text.startsWith("```")
    }

    private fun sanitizeSuggestion(candidate: String, typedPrefix: String): String {
        val raw = stripInvisibleAndControl(candidate)
        if (raw.isBlank()) return ""
        val hadLeadingWhitespace = raw.firstOrNull()?.isWhitespace() == true
        var text = raw.trim()
        text = stripThinkTagsOrNull(text) ?: return ""
        if (dialogueRoleRegex.containsMatchIn(text)) return ""
        text = text.removePrefix("-").trimStart()
        text = text.removePrefix("•").trimStart()
        text = text.removePrefix("**").removeSuffix("**")
        text = text.removePrefix("`").removeSuffix("`")
        text = text
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trim()
        text = text.trim { it.isWhitespace() || it in "\"'`,:[]{}“”" }
        text = text.replace(Regex("^\\d+[.)、:]\\s*"), "")
        if (text.startsWith(typedPrefix)) {
            text = text.removePrefix(typedPrefix).trimStart()
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length > 1) {
            text = text.substring(1, text.lastIndex)
        }
        if (looksLikeProtocolOrControl(text)) return ""
        text = normalizeContinuationPrefix(
            text = text,
            typedPrefix = typedPrefix,
            hadLeadingWhitespace = hadLeadingWhitespace,
        )
        text = collapseToImeCandidate(text, typedPrefix)
        val metadataMarkers = listOf(
            "BeforeCursor", "AppPackage", "InputMethod",
            "光标前文本", "当前应用", "输入法",
            "你好", "我是", "Qwen", "收到", "当前系统状态", "suggestions"
        )
        if (metadataMarkers.any { text.startsWith(it) || it in text }) return ""
        if (looksLikeProtocolOrControl(text)) return ""
        if (shouldSuppressMostlyLatin(text, typedPrefix)) return ""
        if (text == typedPrefix.trim()) return ""
        return text.trimEnd()
    }

    private fun shouldSuppressMostlyLatin(text: String, typedPrefix: String): Boolean {
        if (text.isBlank()) return true
        if (hanRegex.containsMatchIn(text)) return false
        if (LanLlmLanguageDetector.prefersLatinSuggestions(typedPrefix)) return false
        val letters = latinLetterRegex.findAll(text).count()
        if (letters < 6) return false
        val ratio = letters.toFloat() / text.length.coerceAtLeast(1)
        return ratio >= 0.45f
    }

    private fun collapseToImeCandidate(text: String, typedPrefix: String): String {
        var candidate = text.trimEnd()
        if (candidate.isBlank()) return ""
        val policy = lengthPolicy(typedPrefix)
        val suspiciousSymbolRunStart = findSuspiciousSymbolRunStart(candidate)
        if (suspiciousSymbolRunStart >= 0) {
            candidate = candidate.substring(0, suspiciousSymbolRunStart).trimEnd()
        }

        if (candidate.length > policy.hardLimit) {
            candidate = candidate.take(policy.hardLimit).trimEnd()
        }

        return candidate
    }

    private fun extractJsonCandidate(raw: String): String {
        val firstBrace = raw.indexOf('{')
        if (firstBrace < 0) return raw
        val lastBrace = raw.lastIndexOf('}')
        return if (lastBrace > firstBrace) raw.substring(firstBrace, lastBrace + 1) else raw.substring(firstBrace)
    }

    private fun extractQuotedSuggestions(raw: String, typedPrefix: String): List<String> {
        val anchorStart = raw.indexOf("suggestions")
        if (anchorStart < 0) return emptyList()
        val arrayStart = raw.indexOf('[', anchorStart)
        if (arrayStart < 0) return emptyList()
        val slice = raw.substring(arrayStart + 1)

        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var escaped = false
        var closingQuote: Char? = null
        slice.forEach { ch ->
            if (out.size >= 8) return@forEach
            if (!inString) {
                when (ch) {
                    '"', '“', '”' -> {
                        inString = true
                        closingQuote = matchingClosingQuote(ch)
                        current.clear()
                    }
                    ']' -> return finalizeSuggestions(out, typedPrefix)
                }
            } else {
                when {
                    escaped -> {
                        current.append(ch)
                        escaped = false
                    }
                    ch == '\\' -> escaped = true
                    isMatchingClosingQuote(ch, closingQuote) -> {
                        val sanitized = sanitizeSuggestion(current.toString(), typedPrefix)
                        if (sanitized.isNotBlank()) out.add(sanitized)
                        inString = false
                        closingQuote = null
                    }
                    else -> current.append(ch)
                }
            }
        }
        return finalizeSuggestions(out, typedPrefix)
    }

    private fun matchingClosingQuote(openingQuote: Char): Char = when (openingQuote) {
        '“', '”' -> '”'
        else -> '"'
    }

    private fun isMatchingClosingQuote(char: Char, closingQuote: Char?): Boolean = when (closingQuote) {
        '"' -> char == '"' || char == '”'
        '”' -> char == '”' || char == '"'
        else -> false
    }

    private fun finalizeSuggestions(candidates: List<String>, typedPrefix: String): List<String> {
        val policy = lengthPolicy(typedPrefix)
        var overLimitCount = 0
        return candidates.asSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .distinct()
            .filter { candidate ->
                if (candidate.length <= policy.softLimit) {
                    true
                } else if (overLimitCount < MAX_OVER_LIMIT_SUGGESTIONS) {
                    overLimitCount += 1
                    true
                } else {
                    false
                }
            }
            .take(8)
            .toList()
    }

    private fun lengthPolicy(typedPrefix: String): SuggestionLengthPolicy =
        when (LanLlmLanguageDetector.detect(typedPrefix)) {
            LanLlmLanguage.Chinese -> SuggestionLengthPolicy(softLimit = 20, hardLimit = 120)
            LanLlmLanguage.English -> SuggestionLengthPolicy(softLimit = 30, hardLimit = 160)
        }

    private fun normalizeContinuationPrefix(
        text: String,
        typedPrefix: String,
        hadLeadingWhitespace: Boolean,
    ): String {
        if (text.isBlank()) return text
        return when (LanLlmLanguageDetector.detect(typedPrefix)) {
            LanLlmLanguage.English -> normalizeEnglishPrefix(text, typedPrefix, hadLeadingWhitespace)
            LanLlmLanguage.Chinese -> normalizeChinesePrefix(text, typedPrefix)
        }
    }

    private fun normalizeEnglishPrefix(
        text: String,
        typedPrefix: String,
        hadLeadingWhitespace: Boolean,
    ): String {
        val trimmed = text.trimStart()
        if (trimmed.isBlank()) return ""
        val needsLeadingSpace = shouldPrefixEnglishSpace(typedPrefix, trimmed)
        return when {
            needsLeadingSpace -> " $trimmed"
            hadLeadingWhitespace -> trimmed
            else -> text.trimEnd()
        }
    }

    private fun shouldPrefixEnglishSpace(typedPrefix: String, suggestion: String): Boolean {
        val prefix = typedPrefix
        if (prefix.isBlank()) return false
        if (prefix.lastOrNull()?.isWhitespace() == true) return false
        val prefixLast = prefix.lastOrNull() ?: return false
        val first = suggestion.firstOrNull() ?: return false
        if (!isAsciiWordLike(prefixLast) || !isAsciiWordLike(first)) return false
        if (prefixLast in "'-/_" || first in "'-/_") return false
        return true
    }

    private fun normalizeChinesePrefix(text: String, typedPrefix: String): String {
        val trimmedPrefix = typedPrefix.trimEnd()
        val prefixLast = trimmedPrefix.lastOrNull()
        val trimmedLeading = text.trimStart()
        if (trimmedLeading.isBlank()) return ""
        val suggestionFirst = trimmedLeading.firstOrNull() ?: return trimmedLeading
        val prefixEndsWithPunctuation = prefixLast != null && prefixLast in "，。！？；：,.!?;:"
        return if (prefixEndsWithPunctuation && suggestionFirst in "，。！？；：") {
            trimmedLeading.drop(1).trimStart()
        } else {
            trimmedLeading
        }
    }

    private fun findSuspiciousSymbolRunStart(text: String): Int {
        var index = 0
        while (index < text.length - 1) {
            if (!isSuspiciousSymbol(text[index])) {
                index += 1
                continue
            }
            val start = index
            var end = index + 1
            while (end < text.length && isSuspiciousSymbol(text[end])) {
                end += 1
            }
            if (end - start >= 2) {
                val run = text.substring(start, end)
                if (!isAllowedSymbolRun(run, text, start, end)) {
                    return start
                }
            }
            index = end
        }
        return -1
    }

    private fun isSuspiciousSymbol(char: Char): Boolean =
        !char.isLetterOrDigit() &&
            !char.isWhitespace() &&
            !isHanCharacter(char)

    private fun isAllowedSymbolRun(
        run: String,
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        if (run == "……") return true
        val allMathSymbols = run.all { it in "+-*/=<>%^~" }
        if (!allMathSymbols) return false
        val left = text.getOrNull(start - 1)
        val right = text.getOrNull(end)
        return isMathContextChar(left) && isMathContextChar(right)
    }

    private fun isMathContextChar(char: Char?): Boolean =
        char != null && (char.isDigit() || char in ".()")

    private fun isHanCharacter(char: Char): Boolean =
        char == 0x3007.toChar() ||
            char.code in 0x3400..0x4DBF ||
            char.code in 0x4E00..0x9FFF ||
            char.code in 0xF900..0xFAFF

    private fun isAsciiWordLike(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char.isDigit()

    private fun looksLikeProtocolOrControl(text: String): Boolean {
        val s = stripInvisibleAndControl(text).trim()
        if (s.isEmpty()) return true
        if (s.startsWith("__METRICS__")) return true
        if (jsonFieldNoiseRegex.matches(s)) return true
        if (s.contains("<MEM_RETRIEVAL>", ignoreCase = true) || s.contains("</MEM_RETRIEVAL>", ignoreCase = true)) return true
        if (s.contains("<NO_MEM>", ignoreCase = true)) return true
        if (s.contains("search query", ignoreCase = true)) return true
        if (s.contains("no longer", ignoreCase = true)) return true
        if (queryWideRegex.containsMatchIn(s)) return true
        return false
    }

    private fun stripThinkTagsOrNull(text: String): String? {
        if (text.isEmpty()) return text
        var out = text
        while (true) {
            val startIdx = out.indexOf(THINK_START)
            if (startIdx == -1) break
            val endIdx = out.indexOf(THINK_END, startIdx + THINK_START.length)
            if (endIdx == -1) return null
            out = out.removeRange(startIdx, endIdx + THINK_END.length)
        }
        if (out.contains(THINK_END)) {
            out = out.replace(THINK_END, "")
        }
        return out
    }

    private fun stripInvisibleAndControl(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch.isISOControl()) continue
            when (ch) {
                '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
                '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                '\u2060', '\u2061', '\u2062', '\u2063', '\u2064',
                '\u2066', '\u2067', '\u2068', '\u2069',
                '\uFEFF' -> continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }
}
