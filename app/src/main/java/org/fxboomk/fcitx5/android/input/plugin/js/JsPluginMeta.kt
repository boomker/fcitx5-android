/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 插件在 `swan.plugin.register({...})` 里自报的元信息。
 *
 * 这是**外来代码的自述**，不是宿主保证：id/名称/域名/字段全都由脚本说了算，所以每一项都要在
 * [JsPluginImportPolicy] 里过一遍再落盘。宿主只做三件事——校验形状、把声明摆给用户确认、
 * 之后按声明执行（连接只允许声明的域名，设置页只渲染声明的字段）。
 */
internal data class JsPluginMeta(
    val id: String,
    val name: String,
    val version: String,
    /** 已归一化（小写、去掉末尾点）的域名；脚本声明的非法域名在这里已被丢弃。 */
    val hosts: List<String>,
    /** 脚本声明里被判为非法的域名，仅用于向用户解释「为什么域名列表比脚本里写的少」。 */
    val rejectedHosts: List<String>,
    val features: List<JsPluginFeature>,
    val apps: List<JsPluginApp> = emptyList(),
) {
    val voiceAsr: JsPluginFeature? get() = features.firstOrNull { it.type == FEATURE_VOICE_ASR }

    val sampleRate: Int get() = voiceAsr?.sampleRate ?: DEFAULT_SAMPLE_RATE

    val providerId: String get() = PROVIDER_ID_PREFIX + id

    /** 列表与设置页标题用；脚本没给 name 时退回 id。 */
    val displayName: String get() = name.ifBlank { id }

    companion object {
        const val FEATURE_VOICE_ASR = "voice.asr"
        const val PROVIDER_ID_PREFIX = "js:"
        const val DEFAULT_SAMPLE_RATE = 16000

        /**
         * 输入法程序的插件源码上限（256 KiB）。这既是 QuickJS 求值预算，也是「用户导入一个文件」的
         * 合理体积上限——超过它更可能是选错了文件（APK、压缩包），早点拒绝比求值崩掉好解释。
         */
        const val MAX_SCRIPT_BYTES = 256 * 1024

        /** 域名归一化后可接受的形状：允许 IP 与单段主机名（自建服务常是 http://192.168.x.x:port）。 */
        private val HOST_PATTERN = Regex("^[a-z0-9]([a-z0-9._-]*[a-z0-9])?$")

        /** 插件 id 会成为偏好键与注册表 id 的一段，限制字符集避免路径与键名歧义。 */
        private val ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

        /**
         * 域名归一化。返回 null 表示这个声明不可用（带端口、带协议、通配符等），
         * 调用方据此把它计入 rejectedHosts 而不是放行。
         */
        fun normalizeHost(raw: String): String? {
            val host = raw.trim().lowercase().trimEnd('.')
            if (host.isEmpty()) return null
            if (!HOST_PATTERN.matches(host)) return null
            return host
        }

        /**
         * 解析 `__meta()` 的返回值。
         *
         * 解析失败返回 null（不是空对象）：空对象会被误当成「一个 id 为空的插件」，
         * 而这里要区分「脚本没注册插件」与「返回的东西根本不是 JSON」。
         */
        fun parse(json: String): JsPluginMeta? {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val rawHosts = (obj["hosts"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
            val normalized = LinkedHashSet<String>()
            val rejected = mutableListOf<String>()
            rawHosts.forEach { raw ->
                val host = normalizeHost(raw)
                if (host == null) rejected += raw else normalized += host
            }
            val features = (obj["features"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.let { feature ->
                    val type = feature["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (type.isBlank()) return@let null
                    JsPluginFeature(
                        type = type,
                        id = feature["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        label = feature["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        sampleRate = feature["sampleRate"]?.jsonPrimitive?.intOrNull,
                    )
                }
            }
            return JsPluginMeta(
                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                hosts = normalized.toList(),
                rejectedHosts = rejected,
                features = features,
                apps = (obj["apps"] as? JsonArray).orEmpty().map { element ->
                    val app = element.jsonObject
                    val appId = app["id"]?.jsonPrimitive?.content.orEmpty()
                    require(isValidId(appId)) { "Invalid app ID" }
                    val permissions = (app["permissions"] as? JsonArray)
                        .orEmpty().map { it.jsonPrimitive.content }.toSet()
                    require(permissions.all { it in JsPluginApp.PERMISSIONS }) { "Unsupported app permission" }
                    JsPluginApp(
                        id = appId,
                        label = app["label"]?.jsonPrimitive?.content.orEmpty().ifBlank { appId },
                        permissions = permissions,
                    )
                }.also { require(it.map(JsPluginApp::id).distinct().size == it.size) },
            )
        }
    }
}

internal data class JsPluginFeature(
    /** `voice.asr` 是当前唯一被支持的 feature 类型。 */
    val type: String,
    val id: String,
    val label: String,
    val sampleRate: Int?,
)

/**
 * 一条已导入插件的持久化记录。
 *
 * 只存元信息与来源文件名，**脚本正文单独存文件**：元信息要去重、要列表、要判断冲突，
 * 混在同一个 JSON 里会让每次列表刷新都读一遍几十 KB 的脚本文本。
 */
internal data class JsPluginRecord(
    val meta: JsPluginMeta,
    /** 用户选择的文件名，只用于界面展示「来源」，不参与路径计算。 */
    val sourceName: String,
    val importedAt: Long,
    /** Content-addressed script; null keeps legacy <id>.js imports readable. */
    val scriptDigest: String? = null,
) {
    fun encode(): String = buildJsonObject {
        put("id", meta.id)
        put("name", meta.name)
        put("version", meta.version)
        put("apps", buildJsonArray { meta.apps.forEach { add(it.toJson()) } })
        put("source", sourceName)
        put("importedAt", importedAt)
        scriptDigest?.let { put("scriptDigest", it) }
        put(
            "hosts",
            buildJsonArray { meta.hosts.forEach { add(JsonPrimitive(it)) } }
        )
        put(
            "features",
            buildJsonArray {
                meta.features.forEach { feature ->
                    add(
                        buildJsonObject {
                            put("type", feature.type)
                            put("id", feature.id)
                            put("label", feature.label)
                            feature.sampleRate?.let { put("sampleRate", it) }
                        }
                    )
                }
            }
        )
    }.toString()

    companion object {
        fun decode(json: String): JsPluginRecord? {
            val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
            val meta = JsPluginMeta.parse(json) ?: return null
            val digest = obj["scriptDigest"]?.jsonPrimitive?.contentOrNull
            if (digest != null && !digest.matches(Regex("[0-9a-f]{64}"))) return null
            return JsPluginRecord(
                scriptDigest = digest,
                meta = meta,
                sourceName = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                importedAt = obj["importedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }
    }
}

internal data class JsPluginApp(
    val id: String,
    val label: String,
    val permissions: Set<String>,
) {
    val command: String get() = "js.$id"

    fun toJson() = buildJsonObject {
        put("id", id)
        put("label", label)
        put("permissions", buildJsonArray {
            permissions.sorted().forEach { add(JsonPrimitive(it)) }
        })
    }

    companion object {
        val PERMISSIONS = setOf(
            "editor.read_selection",
            "editor.read_text",
            "editor.composition",
            "editor.commit",
            "network.request",
            "credentials.list",
        )
    }
}
