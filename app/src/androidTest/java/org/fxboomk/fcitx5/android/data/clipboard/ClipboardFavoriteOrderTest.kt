/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import android.database.sqlite.SQLiteDatabase
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
import org.junit.Test
import java.lang.reflect.Field
import java.util.UUID

class ClipboardFavoriteOrderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reorderChangesFavoriteOrderWithoutChangingTimestampsOrHistory() = withDatabase { dao ->
        val first = insertFavorite(dao, "first", 30)
        val second = insertFavorite(dao, "second", 20)
        val third = insertFavorite(dao, "third", 10)
        val historyId = dao.insert(ClipboardEntry(text = "history", timestamp = 40))
        val history = dao.get(historyId)

        ClipboardManager.reorderFavorites(listOf(third, first, second))

        assertEquals(listOf(third, first, second), dao.getFavoriteEntries().map { it.id })
        assertEquals(30L, requireNotNull(dao.get(first)).timestamp)
        assertEquals(20L, requireNotNull(dao.get(second)).timestamp)
        assertEquals(10L, requireNotNull(dao.get(third)).timestamp)
        assertEquals(history, dao.get(historyId))
    }

    @Test
    fun searchAndExportFollowTheReorderedFavoriteList() = withDatabase { dao ->
        val first = insertFavorite(dao, "phrase first", 30)
        val second = insertFavorite(dao, "phrase second", 20)
        val third = insertFavorite(dao, "phrase third", 10)

        ClipboardManager.reorderFavorites(listOf(third, first, second))

        val expected = listOf("phrase third", "phrase first", "phrase second")
        assertEquals(expected, dao.getFavoriteTextEntries().map { it.text })
        assertEquals(expected, dao.searchFavoriteTextEntries("phrase").map { it.text })
        assertEquals(expected, ClipboardManager.exportPhrases())
    }

    @Test
    fun usingAndSavingAnExistingFavoritePreservesItsManualPosition() = withDatabase { dao ->
        val first = insertFavorite(dao, "first", 30)
        val second = insertFavorite(dao, "second", 20)
        val third = insertFavorite(dao, "third", 10)
        ClipboardManager.reorderFavorites(listOf(third, first, second))

        ClipboardManager.markUsed(second, timestamp = 1_000)
        ClipboardManager.addFavoriteText("first")
        ClipboardManager.pin(second)

        assertEquals(listOf(third, first, second), dao.getFavoriteEntries().map { it.id })
        assertEquals(1_000L, requireNotNull(dao.get(second)).timestamp)
        assertEquals(3, dao.itemCount())
    }

    @Test
    fun newlyPinnedHistoryAppearsBeforeTheExistingManualOrder() = withDatabase { dao ->
        val first = insertFavorite(dao, "first", 30)
        val second = insertFavorite(dao, "second", 20)
        ClipboardManager.reorderFavorites(listOf(second, first))
        val historyId = dao.insert(ClipboardEntry(text = "history to pin", timestamp = 1)).toInt()

        ClipboardManager.pin(historyId)

        assertEquals(listOf(historyId, second, first), dao.getFavoriteEntries().map { it.id })
    }

    @Test
    fun importAddsNewPhrasesInFileOrderWithoutReorderingExistingFavorites() = withDatabase { dao ->
        val first = insertFavorite(dao, "first", 30)
        val second = insertFavorite(dao, "second", 20)
        ClipboardManager.reorderFavorites(listOf(second, first))

        assertEquals(2, ClipboardManager.importPhrases(listOf("new first", "second", "new second")))

        assertEquals(listOf("new first", "new second", "second", "first"), ClipboardManager.exportPhrases())
    }

    @Test
    fun staleDragSnapshotIgnoresMissingIdsAndKeepsFavoritesAddedDuringTheDrag() = withDatabase { dao ->
        val first = insertFavorite(dao, "first", 30)
        val second = insertFavorite(dao, "second", 20)
        val removed = insertFavorite(dao, "removed", 10)
        val deleted = insertFavorite(dao, "deleted", 5)
        dao.updatePinStatus(removed, false)
        dao.markAsDeleted(deleted)
        val added = insertFavorite(dao, "added during drag", 40)

        ClipboardManager.reorderFavorites(listOf(second, deleted, first, removed, second, Int.MAX_VALUE))

        assertEquals(listOf(second, first, added), dao.getFavoriteEntries().map { it.id })
        assertFalse(requireNotNull(dao.get(removed)).pinned)
        assertEquals(4, dao.itemCount())
    }

    @Test
    fun reorderedFavoritesSurviveDatabaseReopen() = runBlocking {
        val name = "clipboard-order-reopen-${UUID.randomUUID()}"
        var database: ClipboardDatabase? = null
        try {
            val initial = Room.databaseBuilder(context, ClipboardDatabase::class.java, name).build()
            database = initial
            withManagerDatabase(initial) { dao ->
                val first = insertFavorite(dao, "first", 30)
                val second = insertFavorite(dao, "second", 20)
                ClipboardManager.reorderFavorites(listOf(second, first))
            }
            initial.close()

            val reopened = Room.databaseBuilder(context, ClipboardDatabase::class.java, name).build()
            database = reopened
            assertEquals(listOf("second", "first"), reopened.clipboardDao().getFavoriteEntries().map { it.text })
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun versionSevenMigrationPreservesRowsAndTheirPreviousFavoriteOrder() = runBlocking {
        val name = "clipboard-order-migration-${UUID.randomUUID()}"
        var database: ClipboardDatabase? = null
        try {
            val path = context.getDatabasePath(name)
            path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL(
                    "CREATE TABLE IF NOT EXISTS clipboard (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, " +
                        "originalText TEXT NOT NULL DEFAULT '', originalRootUri TEXT NOT NULL DEFAULT '', " +
                        "pinned INTEGER NOT NULL, timestamp INTEGER NOT NULL DEFAULT -1, " +
                        "type TEXT NOT NULL DEFAULT 'text/plain', source TEXT NOT NULL DEFAULT 'local', " +
                        "deleted INTEGER NOT NULL DEFAULT 0, sensitive INTEGER NOT NULL DEFAULT 0)"
                )
                old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                old.execSQL("INSERT INTO room_master_table VALUES(42, '95e4cf312ea59eda16aecc93ed6426ca')")
                old.execSQL(
                    "INSERT INTO clipboard (id, text, originalText, originalRootUri, pinned, timestamp, source, sensitive) " +
                        "VALUES(1, 'older favorite', 'content://original', 'content://root', 1, 10, 'remote', 1)"
                )
                old.execSQL("INSERT INTO clipboard (id, text, pinned, timestamp) VALUES(2, 'newer favorite', 1, 30)")
                old.execSQL("INSERT INTO clipboard (id, text, pinned, timestamp) VALUES(3, 'history', 0, 20)")
                old.execSQL("INSERT INTO clipboard (id, text, pinned, timestamp, deleted) VALUES(4, 'deleted', 1, 40, 1)")
                old.version = 7
            }

            val migrated = Room.databaseBuilder(context, ClipboardDatabase::class.java, name).build()
            database = migrated
            val dao = migrated.clipboardDao()
            assertEquals(listOf(2, 1), dao.getFavoriteEntries().map { it.id })
            assertEquals(
                ClipboardEntry(
                    id = 1,
                    text = "older favorite",
                    originalText = "content://original",
                    originalRootUri = "content://root",
                    pinned = true,
                    timestamp = 10,
                    source = ClipboardEntry.SOURCE_REMOTE,
                    sensitive = true,
                    favoriteOrder = 10
                ),
                dao.get(1)
            )
            assertEquals("history", requireNotNull(dao.get(3)).text)
            migrated.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM clipboard WHERE favoriteOrder = timestamp"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(4, cursor.getInt(0))
            }
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun insertFavorite(dao: ClipboardDao, text: String, timestamp: Long): Int =
        dao.insert(ClipboardEntry(text = text, pinned = true, timestamp = timestamp)).toInt()

    private fun withDatabase(block: suspend (ClipboardDao) -> Unit) = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java).build()
        try {
            withManagerDatabase(database, block)
        } finally {
            database.close()
        }
    }

    private suspend fun withManagerDatabase(database: ClipboardDatabase, block: suspend (ClipboardDao) -> Unit) {
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
            block(database.clipboardDao())
        } finally {
            ClipboardManager.coroutineContext[Job]?.children?.toList()?.joinAll()
            mutex.withLock { originals.forEach { (field, value) -> field.set(ClipboardManager, value) } }
        }
    }

    private fun field(name: String): Field = ClipboardManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
