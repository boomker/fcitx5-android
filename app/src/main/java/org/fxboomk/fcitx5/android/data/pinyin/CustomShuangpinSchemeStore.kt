/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.pinyin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import java.io.File

object CustomShuangpinSchemeStore {
    const val UNIQUE_NAME = "shuangpin-custom"

    private data class CachedScheme(val stamp: Long, val scheme: CustomShuangpinScheme)

    @Volatile
    private var cachedScheme: CachedScheme? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): CustomShuangpinScheme {
        val jsonFile = UserConfigFiles.customShuangpinSchemeJson()
        if (jsonFile.isFile) {
            val stamp = jsonFile.lastModified()
            cachedScheme?.takeIf { it.stamp == stamp }?.let { return it.scheme }
            readJson(jsonFile)?.let {
                val scheme = sanitize(it)
                cachedScheme = CachedScheme(stamp, scheme)
                return scheme
            }
        }

        val spDatFile = UserConfigFiles.customShuangpinSpDat()
        if (spDatFile.isFile) {
            return sanitize(
                runCatching {
                    CustomShuangpinSchemeCodec.parseSpDat(spDatFile.readText())
                }.getOrDefault(CustomShuangpinSchemeCodec.defaultScheme())
            )
        }

        return CustomShuangpinSchemeCodec.defaultScheme()
    }

    fun displayName(): String = load().name.ifBlank { CustomShuangpinSchemeCodec.DEFAULT_NAME }

    /**
     * Materialize the default scheme on first start, and migrate an existing
     * hand-written `sp.dat` from the old "自定义" option into the structured
     * JSON representation. The JSON file is the source of truth afterwards.
     */
    fun ensureInitialized() {
        val jsonFile = UserConfigFiles.customShuangpinSchemeJson()
        val spDatFile = UserConfigFiles.customShuangpinSpDat()

        if (jsonFile.isFile) {
            var scheme = (readJson(jsonFile)
                ?: runCatching {
                    CustomShuangpinSchemeCodec.parseSpDat(spDatFile.readText())
                }.getOrNull()
                ?: CustomShuangpinSchemeCodec.defaultScheme()).let(::sanitize)
            // Older app versions stored no syllable overrides in JSON, but the
            // existing sp.dat may already contain a [音节] section. Preserve
            // those entries when upgrading.
            if (scheme.syllables.isEmpty() && spDatFile.isFile) {
                val spScheme = runCatching {
                    CustomShuangpinSchemeCodec.parseSpDat(spDatFile.readText())
                }.getOrNull()
                if (spScheme != null && spScheme.syllables.isNotEmpty()) {
                    scheme = scheme.copy(syllables = spScheme.syllables)
                }
            }
            writeScheme(jsonFile, spDatFile, scheme)
            cachedScheme = null
            return
        }

        if (spDatFile.isFile) {
            // Preserve a hand-written sp.dat (including syllable overrides)
            // until the user edits and saves the scheme in the app.
            val scheme = sanitize(
                runCatching {
                    CustomShuangpinSchemeCodec.parseSpDat(spDatFile.readText())
                }.getOrDefault(CustomShuangpinSchemeCodec.defaultScheme())
            )
            writeJson(jsonFile, scheme)
            cachedScheme = null
            return
        }

        writeScheme(jsonFile, spDatFile, CustomShuangpinSchemeCodec.defaultScheme())
        cachedScheme = null
    }

    fun save(scheme: CustomShuangpinScheme): Result<Unit> = runCatching {
        require(CustomShuangpinSchemeCodec.isValidName(scheme.name)) {
            "Invalid custom shuangpin scheme name"
        }
        require(CustomShuangpinSchemeCodec.isValidZeroInitial(scheme.zeroInitial)) {
            "Invalid custom shuangpin zero-initial marker"
        }
        require(scheme.initials.values.all(CustomShuangpinSchemeCodec::isValidKey)) {
            "Invalid custom shuangpin initial key"
        }
        require(scheme.finals.values.all(CustomShuangpinSchemeCodec::isValidKey)) {
            "Invalid custom shuangpin final key"
        }
        require(scheme.syllables.all { (pinyin, keys) ->
            CustomShuangpinSchemeCodec.isValidSyllable(pinyin) &&
                keys.isNotEmpty() &&
                keys.all(CustomShuangpinSchemeCodec::isValidSyllableKeys)
        }) {
            "Invalid custom shuangpin syllable override"
        }

        val jsonFile = UserConfigFiles.customShuangpinSchemeJson()
        val spDatFile = UserConfigFiles.customShuangpinSpDat()
        cachedScheme = null
        writeScheme(jsonFile, spDatFile, scheme)
    }

    private fun writeScheme(
        jsonFile: File,
        spDatFile: File,
        scheme: CustomShuangpinScheme
    ) {
        jsonFile.parentFile?.mkdirs()
        spDatFile.parentFile?.mkdirs()

        writeJson(jsonFile, scheme)
        writeBytes(spDatFile, CustomShuangpinSchemeCodec.toSpDat(scheme).toByteArray(Charsets.UTF_8))
    }

    private fun writeJson(jsonFile: File, scheme: CustomShuangpinScheme) {
        jsonFile.parentFile?.mkdirs()
        writeBytes(jsonFile, json.encodeToString(scheme).toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    private fun sanitize(scheme: CustomShuangpinScheme): CustomShuangpinScheme {
        val name = scheme.name.trim().takeIf(CustomShuangpinSchemeCodec::isValidName)
            ?: CustomShuangpinSchemeCodec.DEFAULT_NAME
        val zeroInitial = scheme.zeroInitial.trim().lowercase()
            .takeIf(CustomShuangpinSchemeCodec::isValidZeroInitial) ?: "o"
        val syllables = scheme.syllables
            .mapKeys { it.key.trim().lowercase() }
            .mapValues { (_, keys) ->
                keys.map { it.trim().lowercase() }
                    .filter(CustomShuangpinSchemeCodec::isValidSyllableKeys)
                    .distinct()
            }
            .filterKeys(CustomShuangpinSchemeCodec::isValidSyllable)
            .filterValues { it.isNotEmpty() }
        return scheme.copy(name = name, zeroInitial = zeroInitial, syllables = syllables)
    }

    private fun readJson(file: File): CustomShuangpinScheme? = runCatching {
        json.decodeFromString<CustomShuangpinScheme>(file.readText())
    }.getOrNull()
}
