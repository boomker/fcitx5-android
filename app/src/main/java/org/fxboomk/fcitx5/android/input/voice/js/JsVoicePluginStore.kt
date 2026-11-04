/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

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
    private const val DIR_NAME = "js-voice"

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
            prefs().edit().apply { stale.forEach { remove(it) } }.commit()
        }
        return records.sortedBy { it.importedAt }
    }

    @Synchronized
    fun recordOf(id: String): JsVoicePluginRecord? =
        prefs().getString(KEY_PREFIX + id, null)?.let(JsVoicePluginRecord::decode)

    fun importedIds(): Set<String> = list().map { it.meta.id }.toSet()

    @Synchronized
    fun readScript(id: String): String? =
        scriptFile(id).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    /**
     * 保存（或覆盖）一个插件。
     *
     * 覆盖时**只替换脚本与元信息**，不动 [JsVoiceSettingsStore] 里已经存下的设置与密钥：
     * 那是按 provider id 分键的，同名插件升级后用户不必重新填一遍 key。
     */
    @Synchronized
    fun save(record: JsVoicePluginRecord, script: String): Boolean {
        val id = record.meta.id
        if (!JsVoicePluginMeta.isValidId(id)) return false
        val bytes = script.toByteArray(Charsets.UTF_8)
        if (bytes.size > JsVoicePluginMeta.MAX_SCRIPT_BYTES) return false
        val previous = prefs().getString(KEY_PREFIX + id, null)
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
            if (!prefs().edit().putString(KEY_PREFIX + id, stored.encode()).commit()) {
                // SharedPreferences changes memory even if its disk commit fails.
                prefs().edit().putString(KEY_PREFIX + id, previous).commit()
                false
            } else {
                if (previousFile != file) previousFile.delete()
                true
            }
        }.getOrDefault(false)
    }

    /** Settings and secrets survive removal so reinstalling the same plugin can reuse them. */
    @Synchronized
    fun delete(id: String) {
        JsVoiceHostPermissions.revoke(JsVoicePluginMeta.PROVIDER_ID_PREFIX + id)
        val file = scriptFile(id)
        val previous = prefs().getString(KEY_PREFIX + id, null)
        if (prefs().edit().remove(KEY_PREFIX + id).commit()) file.delete()
        else prefs().edit().putString(KEY_PREFIX + id, previous).commit()
    }
}
