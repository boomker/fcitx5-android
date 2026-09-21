/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager

import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import java.io.File

/**
 * Reads the Rime schema list without activating an input method.
 *
 * The deployed default.yaml is preferred because it contains the effective
 * configuration. The user override and source default are fallbacks for a
 * deployment that has not produced that file yet.
 */
object RimeSchemaResolver {

    private val schemaEntryPattern = Regex("""^-\s*schema\s*:\s*(.+?)\s*$""")
    private val schemaListPattern = Regex("""^schema_list\s*:\s*(?:#.*)?$""")
    private val schemaNamePattern = Regex("""^\s*name\s*:\s*(.+?)\s*$""", RegexOption.MULTILINE)

    /**
     * Return display labels from the configured Rime schema list.
     *
     * @param rimeDataDir Rime data directory, or null to use the app's directory.
     */
    fun readLabels(rimeDataDir: File? = UserConfigFiles.rimeDataDir()): List<String> {
        val dataDir = rimeDataDir ?: return emptyList()
        val buildDir = File(dataDir, "build")
        val defaultFiles = listOf(
            File(buildDir, "default.yaml"),
            File(dataDir, "default.custom.yaml"),
            File(dataDir, "default.yaml")
        )
        val schemaIds = defaultFiles.asSequence()
            .filter(File::isFile)
            .mapNotNull { file -> runCatching { parseSchemaIds(file.readText()) }.getOrNull() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        return schemaIds.map { schemaId ->
            val schemaFile = File(buildDir, "$schemaId.schema.yaml")
            val name = schemaFile.takeIf(File::isFile)
                ?.let { file -> runCatching { parseSchemaName(file.readText()) }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            name ?: schemaId
        }.distinct()
    }

    /** Parse schema IDs under a schema_list node, preserving source order. */
    internal fun parseSchemaIds(yaml: String): List<String> {
        var schemaListIndent: Int? = null
        val ids = linkedSetOf<String>()

        yaml.lineSequence().forEach { rawLine ->
            val line = stripYamlComment(rawLine)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (schemaListIndent == null) {
                if (schemaListPattern.matches(trimmed)) {
                    schemaListIndent = indent
                }
                return@forEach
            }

            val listIndent = schemaListIndent
            if (indent <= listIndent) {
                schemaListIndent = if (schemaListPattern.matches(trimmed)) indent else null
                return@forEach
            }

            schemaEntryPattern.matchEntire(trimmed)?.groupValues?.get(1)
                ?.let(::unquoteYamlScalar)
                ?.takeIf { it.isNotEmpty() }
                ?.let(ids::add)
        }

        return ids.toList()
    }

    /** Parse the first schema name from a compiled schema YAML file. */
    internal fun parseSchemaName(yaml: String): String? =
        schemaNamePattern.find(yaml)
            ?.groupValues?.get(1)
            ?.let(::stripYamlComment)
            ?.let(::unquoteYamlScalar)
            ?.takeIf { it.isNotEmpty() }

    private fun unquoteYamlScalar(value: String): String =
        value.trim().let { scalar ->
            if (scalar.length >= 2 &&
                ((scalar.first() == '"' && scalar.last() == '"') ||
                    (scalar.first() == '\'' && scalar.last() == '\''))
            ) {
                scalar.substring(1, scalar.length - 1)
            } else {
                scalar
            }
        }.trim()

    private fun stripYamlComment(line: String): String {
        var quote: Char? = null
        line.forEachIndexed { index, character ->
            when {
                quote == character -> quote = null
                quote == null && (character == '\'' || character == '"') -> quote = character
                quote == null && character == '#' && (index == 0 || line[index - 1].isWhitespace()) ->
                    return line.substring(0, index).trimEnd()
            }
        }
        return line
    }
}
