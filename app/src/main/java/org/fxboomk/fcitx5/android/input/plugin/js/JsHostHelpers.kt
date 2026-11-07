/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import org.fxboomk.fcitx5.android.utils.readBounded
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure helpers for the JS plugin host. Free of Android types so JVM tests can exercise them. */
internal object JsHostHelpers {
    /** Wraps raw little-endian PCM in a 44-byte WAV header. */
    fun wavBytes(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + pcm.size)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1)
        out.putShort(channels.toShort())
        out.putInt(sampleRate)
        out.putInt(byteRate)
        out.putShort(blockAlign.toShort())
        out.putShort(bitsPerSample.toShort())
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(pcm.size)
        out.put(pcm)
        return out.array()
    }

    /**
     * Only wss:// or https:// to a hostname that the plugin declared. Userinfo is rejected so a
     * declared host cannot be spoofed with a credential prefix.
     */
    fun isAllowedUrl(url: String, declaredHosts: Collection<String>): Boolean {
        val parsed = secureUrl(url) ?: return false
        return declaredHosts.any { it.equals(parsed.host.trimEnd('.'), ignoreCase = true) }
    }

    /** Use OkHttp's parser for both validation and the actual request, avoiding parser mismatches. */
    fun secureUrl(url: String): HttpUrl? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "wss" && scheme != "https") return null
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority || '\\' in authority) return null
        val parsed = ("https://" + url.substringAfter("://")).toHttpUrlOrNull() ?: return null
        return parsed.takeIf { it.username.isEmpty() && it.password.isEmpty() }
    }

    /** Configured HTTPS endpoints are explicit host-approval candidates for app plugins. */
    fun configuredHttpsHosts(
        declaredHosts: Set<String>,
        fields: List<JsPluginField>,
        values: Map<String, String>,
    ): Set<String> = buildSet {
        addAll(declaredHosts)
        fields.asSequence()
            .filter { it.key.endsWith("url", ignoreCase = true) }
            .mapNotNull { values[it.key]?.trim() }
            .filter { it.startsWith("https://", ignoreCase = true) }
            .mapNotNull(::secureUrl)
            .forEach { add(it.host.trimEnd('.')) }
    }

    fun gzip(data: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        GZIPOutputStream(bytes).use { it.write(data) }
        return bytes.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBounded(JsRuntime.RESPONSE_BYTES) }

    /** Concatenate decoded blocks, preserving padding and arbitrary capture short reads. */
    fun concatenate(chunks: List<String>, decode: (String) -> ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            for (chunk in chunks) {
                val bytes = decode(chunk)
                require(bytes.size <= JsRuntime.BINARY_BYTES - output.size()) { "Audio exceeds its limit" }
                output.write(bytes)
            }
            output.toByteArray()
        }

    /** prefix + 4-byte big-endian compressed length + compressed payload (swan.websocket.sendGzipFrame). */
    fun gzipFrame(prefix: ByteArray, payload: ByteArray): ByteArray {
        val compressed = gzip(payload)
        return ByteBuffer.allocate(prefix.size + 4 + compressed.size)
            .put(prefix).putInt(compressed.size).put(compressed).array()
    }

    fun readInt32BE(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).int
}
