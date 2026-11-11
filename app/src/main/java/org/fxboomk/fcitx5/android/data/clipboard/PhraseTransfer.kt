/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A text-only format independent of clipboard row IDs, sources, and media permissions. */
object PhraseTransfer {
    private val json = Json { prettyPrint = true }

    @Serializable
    private data class PhraseFile(val version: Int, val phrases: List<String>)

    fun encode(phrases: List<String>): String =
        json.encodeToString(PhraseFile(version = 1, phrases = validate(phrases)))

    fun decode(content: String, fileName: String = ""): List<String> {
        val source = content.removePrefix("\uFEFF")
        val isJson = fileName.endsWith(".json", ignoreCase = true) ||
            (!fileName.endsWith(".txt", ignoreCase = true) &&
                (source.trimStart().startsWith("{") || source.trimStart().startsWith("[")))
        val phrases = if (isJson) {
            val file = json.decodeFromString<PhraseFile>(source)
            require(file.version == 1) { "Unsupported phrase file version" }
            file.phrases
        } else {
            source.lineSequence().filter { it.isNotBlank() }.toList()
        }
        return validate(phrases)
    }

    internal fun validate(phrases: List<String>): List<String> {
        require(phrases.all { text ->
            text.isNotBlank() && !text.startsWith("content://", ignoreCase = true) &&
                !text.startsWith("file://", ignoreCase = true)
        }) { "Phrase files must contain non-empty text entries" }
        return phrases.distinct()
    }
}
