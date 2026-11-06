/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import org.fxboomk.fcitx5.android.utils.appContext
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 用户导入的 JS 语音插件的宿主侧存储。
 *
 * 两处写入必须同步推进，否则会出现「设置里有、注册表里没有」的孤儿插件：
 * - 元信息进 SharedPreferences（一个插件一个键，便于单条增删）；
 * - 脚本正文先写入 `filesDir/js-voice/<id>.<sha256>.js`，再原子提交元信息中的摘要。
 * 提交失败或进程中断时，旧元信息仍指向完整的旧脚本；旧版 <id>.js 仍可读取。
 *
 * [list] 会在返回前剔除脚本文件已丢失的记录，因此任何一次读都是「能跑的那批」。
 */
internal object JsVoicePluginStore {

    private const val PREFS_NAME = "js_voice_imported_plugins"
    private const val KEY_PREFIX = "p:"
    private const val KEY_NAME_PREFIX = "n:"
    private const val KEY_TAGS_PREFIX = "t:"
    private const val KEY_DISABLED_BUILTINS = "disabled_builtin_ids"
    private const val KEY_MIGRATED_LEGACY_ASSISTANT = "migration:bundled_ai_assistant_v1"
    private const val DIR_NAME = "js-voice"
    private const val LEGACY_ASSISTANT_ID = "llm-assistant"
    private const val AI_TAG = "AI"

    internal data class LegacyAssistantMigrationState(
        val tags: Set<String>,
        val disabledBuiltInIds: Set<String>,
    )

    internal fun legacyAssistantMigrationState(
        currentTags: Set<String>?,
        disabledBuiltInIds: Set<String>,
        hasSupersededImport: Boolean,
        migrationCompleted: Boolean,
    ): LegacyAssistantMigrationState? {
        val hasLegacyAssistantTombstone = LEGACY_ASSISTANT_ID in disabledBuiltInIds
        val hasEmptyLegacyAssistantTags = currentTags?.isEmpty() == true
        val hasLegacyState = hasSupersededImport ||
            (hasLegacyAssistantTombstone && hasEmptyLegacyAssistantTags)
        if (migrationCompleted || !hasLegacyState) return null
        return LegacyAssistantMigrationState(
            tags = currentTags.orEmpty() + AI_TAG,
            disabledBuiltInIds = disabledBuiltInIds - LEGACY_ASSISTANT_ID,
        )
    }

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun directory(): File = File(appContext.filesDir, DIR_NAME).apply {
        if (!isDirectory) runCatching { mkdirs() }
    }

    /**
     * 脚本文件路径。id 已由 [JsVoicePluginMeta.isValidId] 限定为字母数字与 `._-` 且首字符为字母数字，
     * 所以不存在 `..` 之类的穿越写法。
     */
    @Synchronized
    fun scriptFile(id: String): File {
        require(JsVoicePluginMeta.isValidId(id))
        return File(directory(), fileName(id, recordOf(id)?.scriptDigest))
    }

    private fun fileName(id: String, digest: String?) =
        if (digest == null) "$id.js" else "$id.$digest.js"

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serialize preferences and scripts under the same lock as import/delete. */
    @Synchronized
    fun withSnapshot(write: (File) -> Unit) {
        list()
        write(directory())
    }

    /** Called after preference XML validation, before any live restore target is changed. */
    fun validateBackup(root: File) {
        val scripts = File(root, DIR_NAME)
        val hasScripts = scripts.exists()
        if (hasScripts) require(scripts.isDirectory)
        scripts.listFiles()?.forEach {
            require(it.isFile && it.name.endsWith(".js") && it.length() <= JsVoicePluginMeta.MAX_SCRIPT_BYTES)
        }
        val metadata = File(root, "shared_prefs/$PREFS_NAME.xml")
        if (!metadata.isFile) return
        metadata.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "string") continue
                val key = parser.getAttributeValue(null, "name") ?: continue
                if (!key.startsWith(KEY_PREFIX)) continue
                val record = requireNotNull(JsVoicePluginRecord.decode(parser.nextText()))
                val id = key.removePrefix(KEY_PREFIX)
                require(JsVoicePluginMeta.isValidId(id) && record.meta.id == id)
                // Only the legacy format may omit scripts. A digest-bearing record must
                // never silently restore without the body referenced by that digest.
                if (!hasScripts && record.scriptDigest == null) continue
                val file = File(scripts, fileName(id, record.scriptDigest))
                require(file.isFile && file.length() <= JsVoicePluginMeta.MAX_SCRIPT_BYTES) { "Missing imported voice script: $id" }
                record.scriptDigest?.let { require(digest(file.readBytes()) == it) { "Corrupt imported voice script: $id" } }
            }
        }
    }

    /** 全部已导入插件；脚本文件缺失的记录会被清掉而不是留在列表里点了报错。 */
    @Synchronized
    fun list(): List<JsVoicePluginRecord> {
        val all = prefs().all
        val records = mutableListOf<JsVoicePluginRecord>()
        val stale = mutableListOf<String>()
        all.forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return@forEach
            val id = key.removePrefix(KEY_PREFIX)
            val record = JsVoicePluginRecord.decode(value)
            if (record == null || !JsVoicePluginMeta.isValidId(id) || record.meta.id != id || !scriptFile(id).isFile) {
                stale += key
                return@forEach
            }
            records += record
        }
        if (stale.isNotEmpty()) {
            prefs().edit().apply { stale.forEach { remove(it) } }.apply()
        }
        return records.sortedBy { it.importedAt }
    }

    @Synchronized
    fun recordOf(id: String): JsVoicePluginRecord? =
        prefs().getString(KEY_PREFIX + id, null)?.let(JsVoicePluginRecord::decode)

    fun importedIds(): Set<String> = list().map { it.meta.id }.toSet()

    @Synchronized
    fun disabledBuiltInIds(): Set<String> =
        prefs().getStringSet(KEY_DISABLED_BUILTINS, emptySet()).orEmpty().toSet()

    /** One-time migration from the former imported LLM Assistant entry to the bundled AI Assistant. */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun migrateLegacyAssistantState(hasSupersededImport: Boolean): Boolean {
        val preferences = prefs()
        val previousTags = preferences.getStringSet(KEY_TAGS_PREFIX + LEGACY_ASSISTANT_ID, null)?.toSet()
        val previousDisabled = disabledBuiltInIds()
        val previousMigrationCompleted = preferences.getBoolean(KEY_MIGRATED_LEGACY_ASSISTANT, false)
        val migrated = legacyAssistantMigrationState(
            currentTags = previousTags,
            disabledBuiltInIds = previousDisabled,
            hasSupersededImport = hasSupersededImport,
            migrationCompleted = previousMigrationCompleted,
        )
        if (migrated == null) {
            if (!previousMigrationCompleted && preferences.edit()
                    .putBoolean(KEY_MIGRATED_LEGACY_ASSISTANT, true)
                    .commit()
            ) return true
            return false
        }

        val editor = preferences.edit()
            .putStringSet(KEY_TAGS_PREFIX + LEGACY_ASSISTANT_ID, migrated.tags)
            .putBoolean(KEY_MIGRATED_LEGACY_ASSISTANT, true)
        if (migrated.disabledBuiltInIds.isEmpty()) {
            editor.remove(KEY_DISABLED_BUILTINS)
        } else {
            editor.putStringSet(KEY_DISABLED_BUILTINS, migrated.disabledBuiltInIds)
        }
        if (editor.commit()) return true

        preferences.edit().apply {
            if (previousTags == null) remove(KEY_TAGS_PREFIX + LEGACY_ASSISTANT_ID)
            else putStringSet(KEY_TAGS_PREFIX + LEGACY_ASSISTANT_ID, previousTags)
            if (previousDisabled.isEmpty()) remove(KEY_DISABLED_BUILTINS)
            else putStringSet(KEY_DISABLED_BUILTINS, previousDisabled)
            if (previousMigrationCompleted) putBoolean(KEY_MIGRATED_LEGACY_ASSISTANT, true)
            else remove(KEY_MIGRATED_LEGACY_ASSISTANT)
        }.commit()
        return false
    }

    @Synchronized
    fun displayName(id: String, fallback: String): String =
        prefs().getString(KEY_NAME_PREFIX + id, null)?.takeIf(String::isNotBlank) ?: fallback

    @Synchronized
    fun tags(id: String, defaults: Set<String>): Set<String> =
        prefs().getStringSet(KEY_TAGS_PREFIX + id, null)?.toSet() ?: defaults

    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun saveTags(id: String, tags: Set<String>): Boolean {
        if (!JsVoicePluginMeta.isValidId(id)) return false
        if (tags.any { it.isBlank() || it.length > 32 }) return false
        val preferences = prefs()
        val previousTags = preferences.getStringSet(KEY_TAGS_PREFIX + id, null)?.toSet()
        val editor = preferences.edit().putStringSet(KEY_TAGS_PREFIX + id, tags.toSet())
        if (editor.commit()) return true
        preferences.edit().apply {
            if (previousTags == null) remove(KEY_TAGS_PREFIX + id)
            else putStringSet(KEY_TAGS_PREFIX + id, previousTags)
        }.commit()
        return false
    }

    @Synchronized
    fun readScript(id: String): String? =
        scriptFile(id).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    /**
     * 保存（或覆盖）一个插件。
     *
     * 覆盖时**只替换脚本与元信息**，不动 [JsVoiceSettingsStore] 里已经存下的设置与密钥：
     * 那是按 provider id 分键的，同名插件升级后用户不必重新填一遍 key。
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun save(record: JsVoicePluginRecord, script: String): Boolean {
        val id = record.meta.id
        if (!JsVoicePluginMeta.isValidId(id)) return false
        val bytes = script.toByteArray(Charsets.UTF_8)
        if (bytes.size > JsVoicePluginMeta.MAX_SCRIPT_BYTES) return false
        val previous = prefs().getString(KEY_PREFIX + id, null)
        val disabledBefore = disabledBuiltInIds()
        val previousFile = scriptFile(id)
        val stored = record.copy(scriptDigest = digest(bytes))
        val file = File(directory(), fileName(id, stored.scriptDigest))
        return runCatching {
            val staged = File.createTempFile("import-", ".tmp", directory())
            try {
                FileOutputStream(staged).use { output -> output.write(bytes); output.fd.sync() }
                check(staged.renameTo(file)) { "Cannot publish imported voice script" }
            } finally {
                staged.delete()
            }
            val disabledAfter = disabledBefore - id
            val editor = prefs().edit().putString(KEY_PREFIX + id, stored.encode())
            if (disabledAfter.isEmpty()) {
                editor.remove(KEY_DISABLED_BUILTINS)
            } else if (disabledAfter != disabledBefore) {
                editor.putStringSet(KEY_DISABLED_BUILTINS, disabledAfter)
            }
            if (!editor.commit()) {
                // SharedPreferences changes memory even if its disk commit fails.
                prefs().edit().apply {
                    if (previous == null) remove(KEY_PREFIX + id)
                    else putString(KEY_PREFIX + id, previous)
                    if (disabledBefore.isEmpty()) remove(KEY_DISABLED_BUILTINS)
                    else putStringSet(KEY_DISABLED_BUILTINS, disabledBefore)
                }.commit()
                false
            } else {
                if (previousFile != file) previousFile.delete()
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Removing a bundled plugin writes a tombstone so app upgrades do not put it back.
     * Importing its ID later clears the tombstone and installs the user-managed script.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun uninstall(id: String, builtInIds: Set<String>): Boolean {
        require(JsVoicePluginMeta.isValidId(id))
        val pluginId = JsVoicePluginMeta.PROVIDER_ID_PREFIX + id
        val file = scriptFile(id)
        val key = KEY_PREFIX + id
        val previous = prefs().getString(key, null)
        val disabledBefore = disabledBuiltInIds()
        val disabledAfter = if (id in builtInIds) disabledBefore + id else disabledBefore
        val editor = prefs().edit().remove(key)
        if (disabledAfter.isEmpty()) {
            editor.remove(KEY_DISABLED_BUILTINS)
        } else if (disabledAfter != disabledBefore) {
            editor.putStringSet(KEY_DISABLED_BUILTINS, disabledAfter)
        }
        if (!editor.commit()) {
            prefs().edit().apply {
                if (previous == null) remove(key) else putString(key, previous)
                if (disabledBefore.isEmpty()) remove(KEY_DISABLED_BUILTINS)
                else putStringSet(KEY_DISABLED_BUILTINS, disabledBefore)
            }.commit()
            return false
        }
        JsVoiceHostPermissions.revoke(pluginId)
        JsAppPermissions.revoke(pluginId)
        file.delete()
        return true
    }
}
