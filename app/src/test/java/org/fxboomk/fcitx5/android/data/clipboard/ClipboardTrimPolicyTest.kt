package org.fxboomk.fcitx5.android.data.clipboard

import android.content.ClipDescription
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardTrimPolicyTest {

    @Test
    fun trimsOldestEntriesByTimestamp() {
        val entries = listOf(
            clipboardEntry(id = 10, timestamp = 300L),
            clipboardEntry(id = 11, timestamp = 100L),
            clipboardEntry(id = 12, timestamp = 200L),
        )

        assertEquals(listOf(11), findClipboardIdsToTrim(entries, limit = 2))
    }

    @Test
    fun fallsBackToIdOrderWhenTimestampsMatch() {
        val entries = listOf(
            clipboardEntry(id = 21, timestamp = 100L),
            clipboardEntry(id = 22, timestamp = 100L),
            clipboardEntry(id = 23, timestamp = 100L),
        )

        assertEquals(listOf(21), findClipboardIdsToTrim(entries, limit = 2))
    }

    @Test
    fun trimsEverythingWhenLimitIsZero() {
        val entries = listOf(
            clipboardEntry(id = 31, timestamp = 100L),
            clipboardEntry(id = 32, timestamp = 200L),
        )

        assertEquals(listOf(31, 32), findClipboardIdsToTrim(entries, limit = 0))
    }

    private fun clipboardEntry(id: Int, timestamp: Long) = ClipboardEntry(
        id = id,
        text = "entry-$id",
        timestamp = timestamp,
        type = ClipDescription.MIMETYPE_TEXT_PLAIN,
    )
}
