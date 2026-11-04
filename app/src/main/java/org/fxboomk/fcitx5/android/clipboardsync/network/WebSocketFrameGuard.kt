/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.clipboardsync.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Inspects decrypted bytes before Okio/OkHttp can assemble a WebSocket message. */
internal class WebSocketFrameGuard(private val source: InputStream, private val maxMessage: Long) : InputStream() {
    private var headers: ByteArrayOutputStream? = ByteArrayOutputStream()
    private var terminator = 0
    private val frame = ByteArray(10)
    private var headerCount = 0
    private var headerSize = 2
    private var remaining = 0L
    private var fragmented = false
    private var messageBytes = 0L
    private var finalData = false
    private val single = ByteArray(1)

    init { require(maxMessage > 0) }

    override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 255

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        // Bound read-ahead even if a caller asks for more than Okio's usual 8 KiB.
        val count = source.read(bytes, offset, minOf(length, 8192))
        if (count < 0) return -1
        var index = offset
        val end = offset + count
        while (index < end) {
            val http = headers
            if (http != null) {
                val b = bytes[index++].toInt() and 255
                if (http.size() == 64 * 1024) throw IOException("WebSocket response headers exceed 64 KiB")
                http.write(b)
                terminator = when {
                    b == 13 && (terminator == 0 || terminator == 2) -> terminator + 1
                    b == 10 && (terminator == 1 || terminator == 3) -> terminator + 1
                    b == 13 -> 1
                    else -> 0
                }
                if (terminator == 4) {
                    val lines = http.toString("US-ASCII").split("\r\n")
                    if (!lines.first().matches(Regex("HTTP/1\\.[01] 101(?: .*)?")))
                        throw IOException("The WebSocket endpoint must return an HTTP 101 upgrade")
                    if (lines.any { it.substringBefore(':').equals("Sec-WebSocket-Extensions", true) &&
                            it.substringAfter(':', "").isNotBlank() })
                        throw IOException("Compressed WebSocket extensions are not supported")
                    headers = null
                }
            } else if (remaining > 0) {
                val skipped = minOf(remaining, (end - index).toLong()).toInt()
                remaining -= skipped; index += skipped
                if (remaining == 0L) endFrame()
            } else {
                frame[headerCount++] = bytes[index++]
                if (headerCount == 2) {
                    val n = frame[1].toInt() and 127
                    headerSize = when (n) { 126 -> 4; 127 -> 10; else -> 2 }
                }
                if (headerCount == headerSize) beginFrame()
            }
        }
        return count
    }

    private fun beginFrame() {
        val first = frame[0].toInt() and 255
        val second = frame[1].toInt() and 255
        val opcode = first and 15
        val fin = first and 128 != 0
        if (first and 112 != 0 || second and 128 != 0) throw IOException("Invalid server WebSocket flags")
        var length = (second and 127).toLong()
        if (length >= 126) {
            if (headerSize == 10 && frame[2].toInt() and 128 != 0) throw IOException("Invalid WebSocket length")
            length = 0
            for (i in 2 until headerSize) length = (length shl 8) or (frame[i].toLong() and 255)
            if ((headerSize == 4 && length < 126) || (headerSize == 10 && length < 65536))
                throw IOException("Noncanonical WebSocket length")
        }
        when (opcode) {
            0 -> {
                if (!fragmented) throw IOException("Unexpected WebSocket continuation")
                checkBudget(length)
                if (fin) fragmented = false
            }
            1, 2 -> {
                if (fragmented) throw IOException("Unfinished WebSocket message")
                messageBytes = 0; checkBudget(length); fragmented = !fin
            }
            8, 9, 10 -> if (!fin || length > 125) throw IOException("Invalid WebSocket control frame")
            else -> throw IOException("Unsupported WebSocket opcode")
        }
        finalData = opcode <= 2 && fin
        remaining = length
        headerCount = 0; headerSize = 2
        if (remaining == 0L) endFrame()
    }

    private fun checkBudget(length: Long) {
        if (length > maxMessage - messageBytes)
            throw IOException("ClipCascade WebSocket message exceeds $maxMessage bytes")
        messageBytes += length
    }
    private fun endFrame() { if (finalData) messageBytes = 0; finalData = false }

    override fun skip(n: Long): Long {
        val buffer = ByteArray(8192)
        var skipped = 0L
        while (skipped < n) {
            val count = read(buffer, 0, minOf(n - skipped, buffer.size.toLong()).toInt())
            if (count < 0) break
            skipped += count
        }
        return skipped
    }
    override fun available(): Int = source.available()
    override fun close() = source.close()
}
