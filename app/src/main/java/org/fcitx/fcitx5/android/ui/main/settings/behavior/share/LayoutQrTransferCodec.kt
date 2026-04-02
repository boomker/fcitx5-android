/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.share

import android.util.Log
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.CRC32

object LayoutQrTransferCodec {
    private const val TAG = "LayoutQrTransferCodec"
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val prettyJson = Json {
        prettyPrint = true
    }

    private const val MAGIC = "F5AQR1"
    private const val MAX_CHUNK_BYTES = 1500
    private const val MAX_DECOMPRESSED_BYTES = 256 * 1024
    const val TRANSFER_TYPE_LAYOUT = 'L'
    const val TRANSFER_TYPE_POPUP = 'P'
    const val TRANSFER_TYPE_THEME = 'T'
    private const val TRANSFER_PROFILE_SEPARATOR = "~"

    data class Chunk(
        val transferId: String,
        val index: Int,
        val total: Int,
        val crc32: Long,
        val payloadBase64: String
    ) {
        fun encode(): String = buildString {
            append(MAGIC).append('|')
            append(transferId).append('|')
            append(index).append('/').append(total).append('|')
            append(crc32).append('|')
            append(payloadBase64)
        }
    }

    data class ChunkBundle(val transferId: String, val total: Int, val chunks: List<Chunk>)

    fun encodeJsonToChunks(
        rawJson: String,
        transferType: Char? = null,
        transferProfile: String? = null
    ): ChunkBundle {
        val compressed = compress(rawJson.toByteArray(Charsets.UTF_8))
        val crc = crc32(compressed)
        val transferId = buildTransferId(transferType, transferProfile)
        val chunkCount = (compressed.size + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES
        val chunks = ArrayList<Chunk>(chunkCount)
        var i = 0
        while (i < chunkCount) {
            val start = i * MAX_CHUNK_BYTES
            val end = minOf(start + MAX_CHUNK_BYTES, compressed.size)
            val part = compressed.copyOfRange(start, end)
            chunks += Chunk(
                transferId = transferId,
                index = i + 1,
                total = chunkCount,
                crc32 = crc,
                payloadBase64 = Base64.encodeToString(part, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
            i++
        }
        return ChunkBundle(transferId, chunkCount, chunks)
    }

    fun decodeChunksToJson(encodedChunks: List<String>): String {
        val parsed = encodedChunks.map { decodeChunkLine(it) }
        val transferId = parsed.firstOrNull()?.transferId ?: throw IllegalArgumentException("No chunk data")
        val total = parsed.first().total
        if (parsed.any { it.transferId != transferId || it.total != total }) {
            throw IllegalArgumentException("Chunk header mismatch")
        }
        val ordered = parsed.distinctBy { it.index }.sortedBy { it.index }
        if (ordered.size != total) {
            throw IllegalArgumentException("Incomplete chunks: ${ordered.size}/$total")
        }
        ordered.forEachIndexed { i, chunk ->
            if (chunk.index != i + 1) throw IllegalArgumentException("Chunk sequence error at ${i + 1}")
        }
        val crc = ordered.first().crc32
        if (ordered.any { it.crc32 != crc }) throw IllegalArgumentException("CRC header mismatch")
        val merged = ByteArrayOutputStream().use { out ->
            ordered.forEach {
                out.write(Base64.decode(it.payloadBase64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            }
            out.toByteArray()
        }
        if (crc32(merged) != crc) throw IllegalArgumentException("CRC check failed")
        return decompress(merged).toString(Charsets.UTF_8)
    }

    fun parseChunk(raw: String): Chunk = decodeChunkLine(raw)

    fun detectTransferType(transferId: String): Char? = when (transferId.firstOrNull()) {
        TRANSFER_TYPE_LAYOUT, TRANSFER_TYPE_POPUP, TRANSFER_TYPE_THEME -> transferId.first()
        else -> null
    }

    fun extractProfileFromTransferId(transferId: String): String? {
        val separatorIndex = transferId.indexOf(TRANSFER_PROFILE_SEPARATOR)
        if (separatorIndex < 0 || separatorIndex == transferId.length - 1) return null
        val encoded = transferId.substring(separatorIndex + 1)
        return runCatching {
            String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun normalizeLayoutJson(jsonText: String): String {
        val element = json.parseToJsonElement(jsonText)
        return prettyJson.encodeToString(element) + "\n"
    }

    fun exportElementToJsonString(element: JsonElement): String =
        prettyJson.encodeToString(element) + "\n"

    fun importJsonStringToElement(raw: String): JsonElement = json.parseToJsonElement(raw)

    fun parseQrImageText(raw: String): String? {
        val trimmed = raw.trim()
        return if (trimmed.startsWith(MAGIC)) trimmed else null
    }

    private fun decodeChunkLine(line: String): Chunk {
        val parts = line.split('|', limit = 5)
        if (parts.size != 5 || parts[0] != MAGIC) throw IllegalArgumentException("Invalid QR payload header")
        val transferId = parts[1]
        val seq = parts[2].split('/', limit = 2)
        if (seq.size != 2) throw IllegalArgumentException("Invalid sequence header")
        val index = seq[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid chunk index")
        val total = seq[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid total chunks")
        if (index <= 0 || total <= 0 || index > total) throw IllegalArgumentException("Chunk sequence out of range")
        val crc = parts[3].toLongOrNull() ?: throw IllegalArgumentException("Invalid crc32")
        return Chunk(transferId, index, total, crc, parts[4])
    }

    private fun buildTransferId(transferType: Char?, transferProfile: String? = null): String {
        val random = UUID.randomUUID().toString().replace("-", "").lowercase()
        val normalizedType = transferType?.uppercaseChar()
        val baseId = if (normalizedType != null) "$normalizedType${random.take(11)}" else random.take(12)
        val profile = transferProfile?.takeIf { it.isNotBlank() } ?: return baseId
        val encodedProfile = Base64.encodeToString(
            profile.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$baseId$TRANSFER_PROFILE_SEPARATOR$encodedProfile"
    }

    private fun compress(raw: ByteArray): ByteArray {
        val presets = intArrayOf(
            LZMA2Options.PRESET_MAX,
            8, 7, 6
        )
        var lastError: Throwable? = null
        for (preset in presets) {
            try {
                return ByteArrayOutputStream().use { baos ->
                    XZOutputStream(baos, LZMA2Options(preset)).use { it.write(raw) }
                    baos.toByteArray()
                }
            } catch (oom: OutOfMemoryError) {
                lastError = oom
                Log.w(TAG, "LZMA2 preset $preset OOM, retry with lower preset")
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "LZMA2 preset $preset failed: ${t.message}")
            }
        }
        throw IllegalStateException("LZMA2 compression failed", lastError)
    }

    private fun decompress(raw: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        XZInputStream(ByteArrayInputStream(raw)).use { input ->
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_DECOMPRESSED_BYTES) {
                    throw IllegalArgumentException("Decompressed QR payload too large")
                }
            }
        }
        return buffer.toByteArray()
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
