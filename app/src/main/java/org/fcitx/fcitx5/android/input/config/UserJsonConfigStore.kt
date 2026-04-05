/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object UserJsonConfigStore {

    data class JsonSnapshot<T>(
        val value: T,
        val lastModified: Long,
        val file: File?
    )

    @PublishedApi
    internal val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @PublishedApi
    internal fun cleanJson(content: String, stripLineComments: Boolean): String {
        if (!stripLineComments) return content
        return content.replace(Regex("//.*?\\n"), "")
    }

    inline fun <reified T> readJson(
        file: File?,
        stripLineComments: Boolean = false
    ): JsonSnapshot<T>? {
        if (file == null || !file.exists()) return null
        return try {
            val content = cleanJson(file.readText(), stripLineComments)
            val decoded = parser.decodeFromString<T>(content)
            JsonSnapshot(decoded, file.lastModified(), file)
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
    }

    /**
     * Read JSON from memory [JsonObject] instead of file.
     * This avoids disk I/O for temporary/preview data.
     *
     * @param json The in-memory JSON object
     * @param stripLineComments Whether to strip line comments (not applicable for in-memory JSON)
     * @return JsonSnapshot with a synthetic lastModified time
     */
    inline fun <reified T> readJson(
        json: JsonObject?,
        stripLineComments: Boolean = false
    ): JsonSnapshot<T>? {
        if (json == null) return null
        return try {
            val decoded = parser.decodeFromJsonElement<T>(json)
            JsonSnapshot(decoded, System.nanoTime(), null)
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
    }

    fun readFontsetPathMapSnapshot(): Result<JsonSnapshot<Map<String, List<String>>>?> = runCatching {
        val file = UserConfigFiles.fontsetJson()
            ?.takeIf { it.exists() }
            ?: return@runCatching null
        val content = cleanJson(file.readText(), stripLineComments = true)
        val json = parser.parseToJsonElement(content)
        val jsonObject = parser.decodeFromJsonElement<JsonObject>(json)
        val parsed = jsonObject.toMap().mapValues { (_, value) ->
            value.jsonPrimitive.content
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        JsonSnapshot(
            value = parsed,
            lastModified = file.lastModified(),
            file = file
        )
    }

    fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> = runCatching {
        val file = UserConfigFiles.fontsetJson()
            ?: error("Cannot resolve fontset.json path")
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            pathMap.forEach { (key, values) ->
                if (values.isNotEmpty()) {
                    put(key, JsonPrimitive(values.joinToString(",")))
                }
            }
        }
        file.writeText(parser.encodeToJsonElement(json).toString() + "\n")
        file
    }
}
