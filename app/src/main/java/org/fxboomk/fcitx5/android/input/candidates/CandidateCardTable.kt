/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context

/** Look up rows sorted by their UTF-8 key without keeping a map of every entry in memory. */
internal class CandidateCardTable(private val asset: String) {
    @Volatile
    private var cached: ByteArray? = null

    fun lookup(context: Context, key: String): String? {
        val bytes = cached ?: synchronized(this) {
            cached ?: context.assets.open(asset).use { it.readBytes() }.also { cached = it }
        }
        val target = key.encodeToByteArray()
        var low = if (bytes.firstOrNull() == '#'.code.toByte()) bytes.find(NEWLINE, 0) + 1 else 0
        var high = bytes.size
        while (low < high) {
            var start = low + (high - low) / 2
            // Use the row containing the midpoint so the interval always shrinks.
            while (start > low && bytes[start - 1] != NEWLINE) start--
            val end = bytes.find(NEWLINE, start)
            val tab = bytes.find(TAB, start, end)
            if (tab == end) return null
            val comparison = bytes.compareKey(start, tab, target)
            when {
                comparison < 0 -> low = if (end < high) end + 1 else high
                comparison > 0 -> high = start
                else -> return bytes.decodeToString(tab + 1, end)
            }
        }
        return null
    }

    private fun ByteArray.compareKey(start: Int, end: Int, target: ByteArray): Int {
        val length = end - start
        for (index in 0 until minOf(length, target.size)) {
            val difference = (this[start + index].toInt() and 0xff) - (target[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return length - target.size
    }

    private fun ByteArray.find(value: Byte, start: Int, end: Int = size): Int {
        for (index in start until end) if (this[index] == value) return index
        return end
    }

    private companion object {
        const val NEWLINE: Byte = 10
        const val TAB: Byte = 9
    }
}
