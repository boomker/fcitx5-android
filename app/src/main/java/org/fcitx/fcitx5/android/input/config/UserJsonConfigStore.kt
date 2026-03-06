/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File

object UserJsonConfigStore {

    data class JsonSnapshot<T>(
        val value: T,
        val lastModified: Long,
        val file: File
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

    fun readFontsetPathMapSnapshot(): Result<JsonSnapshot<Map<String, List<String>>>?> = runCatching {
        val file = UserConfigFiles.fontsetJson()
            ?.takeIf { it.exists() }
            ?: return@runCatching null
        val content = cleanJson(file.readText(), stripLineComments = true)
        val json = JSONObject(content)
        val parsed = json.keys().asSequence().associateWith { key ->
            json.optString(key)
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
        val json = JSONObject()
        pathMap.forEach { (key, values) ->
            if (values.isNotEmpty()) {
                json.put(key, values.joinToString(","))
            }
        }
        file.writeText(json.toString(2) + "\n")
        file
    }
}
