/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.util.Base64
import okio.utf8Size
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** A session-local single-entry cache avoids decoding the same frame for each header field. */
internal class JsVoiceBinary {
    private var encoded: String? = null
    private var decoded: ByteArray? = null

    fun decode(value: String): ByteArray {
        require(value.length <= (JsVoiceRuntime.BINARY_BYTES + 2) / 3 * 4) { "Binary input exceeds its limit" }
        if (encoded == value) return decoded!!
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size <= JsVoiceRuntime.BINARY_BYTES) { "Binary input exceeds its limit" }
        encoded = value
        decoded = bytes
        return bytes
    }

    fun encodeUtf8(value: String): String {
        require(value.utf8Size() <= JsVoiceRuntime.BINARY_BYTES) { "Binary input exceeds its limit" }
        return Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    fun concatenate(json: String): String {
        require(json.length <= 2 * JsVoiceRuntime.BINARY_BYTES) { "Audio chunks exceed their limit" }
        val chunks = Json.parseToJsonElement(json).jsonArray
        require(chunks.size <= 4096) { "Too many audio chunks" }
        val bytes = JsVoiceHelpers.concatenate(chunks.map { it.jsonPrimitive.content }, ::decode)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
