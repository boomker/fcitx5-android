/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field

class PhraseTransferDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mergePreservesExistingFavoritesAndPromotesMatchingLocalHistory() = withDatabase { _, dao ->
        val existing = ClipboardEntry(text = "existing", pinned = true, timestamp = 1, source = ClipboardEntry.SOURCE_REMOTE)
        val existingId = dao.insert(existing)
        val localId = dao.insert(ClipboardEntry(text = "from history", timestamp = 2))
        val historyId = dao.insert(ClipboardEntry(text = "unrelated history"))
        val lastEntry = ClipboardManager.lastEntry
        var transformations = 0
        ClipboardManager.transformer = { transformations++; "changed" }

        assertEquals(2, ClipboardManager.importPhrases(listOf("existing", "from history", "new\nmultiline", "new\nmultiline")))

        assertEquals(existing.copy(id = existingId.toInt()), dao.get(existingId))
        assertTrue(requireNotNull(dao.get(localId)).pinned)
        assertFalse(requireNotNull(dao.get(historyId)).pinned)
        assertEquals(listOf("from history", "new\nmultiline", "existing"), ClipboardManager.exportPhrases())
        assertEquals(4, dao.itemCount())
        assertEquals(4, ClipboardManager.itemCount)
        assertEquals(0, transformations)
        assertEquals(lastEntry, ClipboardManager.lastEntry)
        assertEquals(0, ClipboardManager.importPhrases(listOf("existing", "from history", "new\nmultiline")))
    }

    @Test
    fun exportOnlyIncludesActiveFavoriteTextsAndUsesTheirDisplayedText() = withDatabase { _, dao ->
        dao.insert(ClipboardEntry(text = "shown text", originalText = "content://original", pinned = true, timestamp = 4))
        dao.insert(ClipboardEntry(text = "remote favorite", source = ClipboardEntry.SOURCE_REMOTE, pinned = true, timestamp = 3))
        dao.insert(ClipboardEntry(text = "remote favorite", pinned = true, timestamp = 2))
        dao.insert(ClipboardEntry(text = "history"))
        dao.insert(ClipboardEntry(text = "deleted", pinned = true, deleted = true))
        dao.insert(ClipboardEntry(text = "content://image", pinned = true))
        dao.insert(ClipboardEntry(text = "file:///image.png", pinned = true))

        assertEquals(listOf("shown text", "remote favorite"), ClipboardManager.exportPhrases())
    }

    @Test
    fun invalidListDoesNotInsertEarlierValidPhrases() = withDatabase { _, dao ->
        val result = runCatching { ClipboardManager.importPhrases(listOf("valid", "content://media")) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, dao.itemCount())
    }

    @Test
    fun databaseFailureRollsBackTheWholeImport() = withDatabase { database, dao ->
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_phrase BEFORE INSERT ON clipboard " +
                "WHEN NEW.text='reject' BEGIN SELECT RAISE(ABORT, 'test rollback'); END"
        )

        assertTrue(runCatching { ClipboardManager.importPhrases(listOf("first", "reject")) }.isFailure)
        assertEquals(0, dao.itemCount())
        assertEquals(0, ClipboardManager.itemCount)
    }

    private fun withDatabase(block: suspend (ClipboardDatabase, ClipboardDao) -> Unit) = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java).build()
        val mutex = field("mutex").get(ClipboardManager) as Mutex
        val fields = listOf("clbDb", "clbDao", "itemCount", "lastEntry", "transformer").map(::field)
        ClipboardManager.coroutineContext[Job]?.children?.toList()?.joinAll()
        val originals = mutex.withLock {
            val values = fields.associateWith { it.get(ClipboardManager) }
            field("clbDb").set(ClipboardManager, database)
            field("clbDao").set(ClipboardManager, database.clipboardDao())
            field("itemCount").setInt(ClipboardManager, 0)
            values
        }
        try {
            block(database, database.clipboardDao())
        } finally {
            ClipboardManager.coroutineContext[Job]?.children?.toList()?.joinAll()
            mutex.withLock { originals.forEach { (field, value) -> field.set(ClipboardManager, value) } }
            database.close()
        }
    }

    private fun field(name: String): Field = ClipboardManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
