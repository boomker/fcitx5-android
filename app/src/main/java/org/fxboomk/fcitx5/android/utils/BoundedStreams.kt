/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

fun InputStream.copyBoundedTo(output: OutputStream, limit: Long): Long {
    require(limit >= 0 && limit < Long.MAX_VALUE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
        if (read < 0) return total
        if (read == 0) continue
        if (read > limit - total) throw IOException("File exceeds the ${limit / 1024} KiB limit")
        output.write(buffer, 0, read)
        total += read
    }
}

fun InputStream.readBounded(limit: Int): ByteArray = ByteArrayOutputStream().use { output ->
    copyBoundedTo(output, limit.toLong())
    output.toByteArray()
}
