/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.content.Context
import android.content.SharedPreferences
import org.fxboomk.fcitx5.android.utils.appContext

/**
 * JS 语音插件的宿主侧设置存储。
 *
 * 两种值必须分开存放，**这是安全边界而不是风格问题**：
 * - 普通项（平台/模型/端点等）走明文 SharedPreferences，会被并入 `start(context)` 下发给 JS；
 * - 密钥项单独加密，永远不进 JS 上下文，只在宿主建立 WebSocket/HTTP 连接时按字段名取用。
 *
 * 键名带插件前缀（`v:<pluginId>:<field>` / `s:<pluginId>:<field>`），因此多个 JS 插件可以
 * 共用同一个偏好文件而互不串值。
 */
internal object JsVoiceSettingsStore {

    private const val PREFS_NAME = "js_voice_plugins"
    private const val VALUE_PREFIX = "v:"
    private const val SECRET_PREFIX = "s:"

    fun valueKey(pluginId: String, field: String) = VALUE_PREFIX + pluginId + ":" + field

    fun secretKey(pluginId: String, field: String) = SECRET_PREFIX + pluginId + ":" + field

    private fun prefs(): SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keysOf(pluginId: String, prefix: String): Map<String, String> {
        val head = prefix + pluginId + ":"
        val result = LinkedHashMap<String, String>()
        prefs().all.forEach { (key, value) ->
            if (key.startsWith(head) && value is String) {
                result[key.substring(head.length)] = value
            }
        }
        return result
    }

    /** 普通设置项（不含密钥）。 */
    fun values(pluginId: String): Map<String, String> = keysOf(pluginId, VALUE_PREFIX)

    fun value(pluginId: String, field: String): String? =
        prefs().getString(valueKey(pluginId, field), null)

    fun setValue(pluginId: String, field: String, value: String?) {
        prefs().edit().apply {
            if (value == null) remove(valueKey(pluginId, field)) else putString(valueKey(pluginId, field), value)
        }.apply()
    }

    enum class SecretState { MISSING, READY, UNAVAILABLE }

    fun secretState(pluginId: String, field: String): SecretState =
        if (!prefs().contains(secretKey(pluginId, field))) SecretState.MISSING
        else if (secret(pluginId, field) == null) SecretState.UNAVAILABLE else SecretState.READY

    fun hasSecret(pluginId: String, field: String): Boolean =
        secretState(pluginId, field) == SecretState.READY

    /** 解密后的密钥；未设置或解密失败都返回 null。 */
    fun secret(pluginId: String, field: String): String? =
        prefs().getString(secretKey(pluginId, field), null)?.let(JsVoiceSecretCrypto::decrypt)

    /**
     * 保存或清除密钥，返回是否成功。
     *
     * 加密失败时**不能**当成「用户清空了密钥」处理：那会静默删掉一份本来可用的密钥，
     * 而界面还显示已保存。失败就原样不动并返回 false，由调用方提示用户。
     */
    fun setSecret(pluginId: String, field: String, value: String?): Boolean {
        val plain = value?.takeIf { it.isNotEmpty() }
        if (plain == null) {
            prefs().edit().remove(secretKey(pluginId, field)).apply()
            return true
        }
        val encrypted = JsVoiceSecretCrypto.encrypt(plain) ?: return false
        prefs().edit().putString(secretKey(pluginId, field), encrypted).apply()
        return true
    }
}
