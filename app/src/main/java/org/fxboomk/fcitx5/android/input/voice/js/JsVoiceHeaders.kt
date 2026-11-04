/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Builds the HTTP headers for one plugin connection.
 *
 * 这个对象是密钥边界上的唯一收口：插件脚本只能给出**字段名**（`connect(url, "apiKey")`），
 * 真正的密钥由宿主按字段名取出后在这里拼成请求头；脚本给出的额外头一律先过白名单，
 * 不允许覆盖 `Host`/`Content-Length` 这类由 OkHttp 自己算的头，也不允许塞入换行做请求头注入。
 *
 * 纯 Kotlin：JVM 单测直接钉住这些规则，不必上设备。
 */
internal class JsVoiceSecretMissingException(val field: String) : Exception("secret '$field' is not configured")

internal object JsVoiceHeaders {

    /** 由 OkHttp 或宿主自己管理、插件不得覆盖的头。 */
    private val RESERVED = setOf(
        "host", "content-length", "content-type", "connection", "transfer-encoding",
        "proxy-authorization", "upgrade", "authorization",
    )

    private val TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    private const val MAX_HEADER_VALUE_LENGTH = 4096

    const val DEFAULT_AUTH_HEADER = "Authorization"
    const val DEFAULT_AUTH_PREFIX = "Bearer "

    /**
     * @param secretField 插件声明要用的密钥字段，空串表示本次连接不需要认证
     * @param secret 宿主已解密出的密钥值
     * @param authHeader 认证头名，空串按 Foxy 约定回落为 `Authorization`
     * @param authPrefix 认证头前缀，空串按 Foxy 约定回落为 `Bearer `
     * @param headersJson 插件给的额外头（JSON 对象）
     */
    fun build(
        secretField: String,
        secret: String?,
        authHeader: String,
        authPrefix: String,
        headersJson: String,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        parseExtra(headersJson).forEach { (name, value) ->
            if (name.lowercase() in RESERVED) {
                throw IllegalArgumentException("Header '$name' is managed by the host")
            }
            headers[name] = value
        }
        if (secretField.isNotBlank()) {
            val value = secret?.takeIf { it.isNotBlank() }
                ?: throw JsVoiceSecretMissingException(secretField)
            val name = headerName(authHeader.ifBlank { DEFAULT_AUTH_HEADER })
            // 前缀的默认值只在「调用方没指定头名」时补：DashScope 走默认 Authorization: Bearer，
            // 而火山引擎要求 X-Api-Key 的**值就是原始 key**，此时空前缀是明确意图，不能补成 Bearer。
            val prefix = if (authHeader.isBlank()) authPrefix.ifBlank { DEFAULT_AUTH_PREFIX } else authPrefix
            headers[name] = headerValue(prefix + value)
        }
        return headers
    }

    private fun parseExtra(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyMap()
        val headers = LinkedHashMap<String, String>()
        obj.forEach { (name, element) ->
            val value = (element as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (name.isBlank()) return@forEach
            headers[headerName(name)] = headerValue(value)
        }
        return headers
    }

    fun headerName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && TOKEN.matches(trimmed)) { "Invalid header name" }
        return trimmed
    }

    /** 去掉 CR/LF：请求头里出现换行就是头注入，这里是最后一道闸。 */
    fun headerValue(value: String): String {
        val cleaned = value.replace("\r", "").replace("\n", "").trim()
        require(cleaned.length <= MAX_HEADER_VALUE_LENGTH) { "Header value too long" }
        return cleaned
    }
}
