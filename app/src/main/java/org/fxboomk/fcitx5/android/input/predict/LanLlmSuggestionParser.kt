package org.fxboomk.fcitx5.android.input.predict

object LanLlmSuggestionParser {
    private const val THINK_START = "<think>"
    private const val THINK_END = "</think>"
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
        }

        if (looksStructuredPayload(normalized)) return emptyList()

        return normalized
            .lineSequence()
            .map { sanitizeSuggestion(it, typedPrefix) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
            .toList()
    }

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
        addCandidate(extractOpenAiMessageContent(raw))
        extractOpenAiMessageContent(raw)?.let { nested ->
            addCandidate(extractFencePayload(nested))
        }
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
        var text = stripInvisibleAndControl(candidate).trim()
        if (text.isBlank()) return ""
        text = stripThinkTagsOrNull(text) ?: return ""
        text = text.removePrefix("-").trimStart()
        text = text.removePrefix("•").trimStart()
        text = text.removePrefix("**").removeSuffix("**")
        text = text.removePrefix("`").removeSuffix("`")
        text = text
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trim()
        text = text.trim { it.isWhitespace() || it in "\"'`,:[]{}" }
        text = text.replace(Regex("^\\d+[.)、:]\\s*"), "")
        if (text.startsWith(typedPrefix)) {
            text = text.removePrefix(typedPrefix).trimStart()
        }
        if (text.startsWith("\"") && text.endsWith("\"") && text.length > 1) {
            text = text.substring(1, text.lastIndex)
        }
        val metadataMarkers = listOf(
            "BeforeCursor", "AppPackage", "InputMethod",
            "光标前文本", "当前应用", "输入法",
            "你好", "我是", "Qwen", "收到", "当前系统状态", "suggestions"
        )
        if (metadataMarkers.any { text.startsWith(it) || it in text }) return ""
        if (looksLikeProtocolOrControl(text)) return ""
        if (text == typedPrefix.trim()) return ""
        if (text.length > 20) return ""
        if (text.count { it in "，。！？；：\n" } >= 2) return ""
        return text.trim()
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
        slice.forEach { ch ->
            if (out.size >= 4) return@forEach
            if (!inString) {
                when (ch) {
                    '"' -> {
                        inString = true
                        current.clear()
                    }
                    ']' -> return out.distinct()
                }
            } else {
                when {
                    escaped -> {
                        current.append(ch)
                        escaped = false
                    }
                    ch == '\\' -> escaped = true
                    ch == '"' -> {
                        val sanitized = sanitizeSuggestion(current.toString(), typedPrefix)
                        if (sanitized.isNotBlank()) out.add(sanitized)
                        inString = false
                    }
                    else -> current.append(ch)
                }
            }
        }
        return out.distinct()
    }

    private fun looksLikeProtocolOrControl(text: String): Boolean {
        val s = stripInvisibleAndControl(text).trim()
        if (s.isEmpty()) return true
        if (s.startsWith("__METRICS__")) return true
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
