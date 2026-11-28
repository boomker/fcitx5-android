/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.pinyin

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import java.io.File

object CustomShuangpinSchemeStore {
    const val UNIQUE_NAME = "shuangpin-custom"

    /**
     * 自定义双拼输入法条目配置内容。
     *
     * 与安装包内置的 `usr/share/fcitx5/inputmethod/shuangpin-custom.conf` 保持一致，
     * 供用户改用自定义 id 时写入用户级条目目录（条目名取自文件名）。
     */
    private const val ENTRY_CONF = """[InputMethod]
Name=Custom Shuangpin
Icon=fcitx-shuangpin
Label=双
LangCode=zh_CN
Addon=pinyin
Configurable=True
"""

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

    fun displayName(context: Context): String = localizedCustomShuangpinName(
        load().name,
        context.getString(R.string.custom_shuangpin)
    )

    /** 当前方案使用的输入法 uniqueName（由方案 id 派生）。 */
    fun currentUniqueName(): String = uniqueNameFor(load().id)

    /** 由方案 id（`shuangpin-` 之后的后缀）得到输入法 uniqueName。 */
    fun uniqueNameFor(id: String): String =
        ShuangpinLayoutKeys.uniqueNameForSuffix(id.trim().lowercase())

    /** 判断某个输入法 uniqueName 是否就是当前的自定义双拼方案。 */
    fun isCustomScheme(uniqueName: String): Boolean = uniqueName == currentUniqueName()

    /** 自定义双拼方案的输入法条目，但不是当前正在使用的那个（改名后残留的旧条目）。 */
    fun isStaleCustomEntry(uniqueName: String): Boolean =
        ShuangpinLayoutKeys.isCustomSchemeUniqueName(uniqueName) && !isCustomScheme(uniqueName)

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
        require(ShuangpinLayoutKeys.isValidCustomSuffix(scheme.id)) {
            "Invalid custom shuangpin scheme id"
        }

        val jsonFile = UserConfigFiles.customShuangpinSchemeJson()
        val spDatFile = UserConfigFiles.customShuangpinSpDat()
        cachedScheme = null
        writeScheme(jsonFile, spDatFile, scheme)
    }

    /**
     * 重命名自定义方案，并迁移全部关联数据。
     *
     * 迁移内容包括：方案名称与 id（JSON、sp.dat）、用户级输入法条目配置
     * （`<data>/inputmethod/shuangpin-<id>.conf`）、该输入法的专属配置
     * （`config/pinyin/shuangpin-<id>.conf`），以及所有键盘布局文件中
     * `shuangpin-<旧 id>` → `shuangpin-<新 id>` 的顶层键——布局高度覆写保存在
     * 键内的 `__meta__` 中，因此随键一并迁移。
     */
    fun rename(newName: String, newId: String): Result<Unit> = runCatching {
        val name = newName.trim()
        require(CustomShuangpinSchemeCodec.isValidName(name)) {
            "Invalid custom shuangpin scheme name"
        }
        val id = newId.trim().lowercase()
        require(ShuangpinLayoutKeys.isValidCustomSuffix(id)) {
            "Invalid custom shuangpin scheme id"
        }

        val previous = load()
        if (previous.name == name && previous.id == id) return@runCatching

        save(previous.copy(name = name, id = id)).getOrThrow()

        val oldUniqueName = uniqueNameFor(previous.id)
        val newUniqueName = uniqueNameFor(id)
        if (oldUniqueName != newUniqueName) {
            renameImConfig(oldUniqueName, newUniqueName)
            renameLayoutKeys(oldUniqueName, newUniqueName)
        }
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
        syncEntryConf(scheme)
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

    /** 用户级输入法条目配置：`<data>/inputmethod/<uniqueName>.conf`。 */
    private fun entryConfFile(uniqueName: String): File? =
        UserConfigFiles.inputMethodConfDir()?.let { File(it, "$uniqueName.conf") }

    /**
     * 同步用户级输入法条目配置。
     *
     * 默认后缀 `custom` 直接使用安装包内置的
     * `usr/share/fcitx5/inputmethod/shuangpin-custom.conf`，因此只在自定义后缀时
     * 写入用户级配置，并清除其他自定义双拼条目配置（改名后残留的旧条目）。
     */
    private fun syncEntryConf(scheme: CustomShuangpinScheme) {
        val uniqueName = uniqueNameFor(scheme.id)
        if (scheme.id != ShuangpinLayoutKeys.DEFAULT_CUSTOM_SUFFIX) {
            entryConfFile(uniqueName)?.let { confFile ->
                confFile.parentFile?.mkdirs()
                writeBytes(confFile, ENTRY_CONF.toByteArray(Charsets.UTF_8))
            }
        }
        val dir = UserConfigFiles.inputMethodConfDir() ?: return
        dir.listFiles()?.forEach { file ->
            val entryName = file.name.removeSuffix(".conf")
            if (entryName == uniqueName) return@forEach
            if (ShuangpinLayoutKeys.isCustomSchemeUniqueName(entryName)) file.delete()
        }
    }

    private fun renameImConfig(oldUniqueName: String, newUniqueName: String) {
        val oldFile = UserConfigFiles.pinyinImConfig(oldUniqueName)
        if (!oldFile.isFile) return
        val newFile = UserConfigFiles.pinyinImConfig(newUniqueName)
        newFile.parentFile?.mkdirs()
        if (!oldFile.renameTo(newFile)) {
            newFile.writeBytes(oldFile.readBytes())
            oldFile.delete()
        }
    }

    private fun renameLayoutKeys(oldUniqueName: String, newUniqueName: String) {
        UserConfigFiles.listTextKeyboardLayoutProfiles().forEach { profile ->
            val file = UserConfigFiles.textKeyboardLayoutJson(profile) ?: return@forEach
            renameLayoutKey(file, oldUniqueName, newUniqueName)
        }
    }

    /** 把布局文件中的顶层键 `oldKey` 重命名为 `newKey`，其余内容原样保留。 */
    private fun renameLayoutKey(file: File, oldKey: String, newKey: String) {
        if (!file.isFile) return
        val root = runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject
        }.getOrNull() ?: return
        if (oldKey !in root || newKey in root) return
        val renamed = JsonObject(
            root.map { (key, value) -> if (key == oldKey) newKey to value else key to value }.toMap()
        )
        writeBytes(file, json.encodeToString(renamed).toByteArray(Charsets.UTF_8))
    }

    private fun sanitize(scheme: CustomShuangpinScheme): CustomShuangpinScheme {
        val name = scheme.name.trim().takeIf(CustomShuangpinSchemeCodec::isValidName)
            ?: CustomShuangpinSchemeCodec.DEFAULT_NAME
        val id = scheme.id.trim().lowercase()
            .takeIf(ShuangpinLayoutKeys::isValidCustomSuffix)
            ?: ShuangpinLayoutKeys.DEFAULT_CUSTOM_SUFFIX
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
        return scheme.copy(name = name, zeroInitial = zeroInitial, syllables = syllables, id = id)
    }

    private fun readJson(file: File): CustomShuangpinScheme? = runCatching {
        json.decodeFromString<CustomShuangpinScheme>(file.readText())
    }.getOrNull()
}

internal fun localizedCustomShuangpinName(name: String, localizedDefaultName: String): String =
    name.takeIf { it.isNotBlank() && it != CustomShuangpinSchemeCodec.DEFAULT_NAME }
        ?: localizedDefaultName
