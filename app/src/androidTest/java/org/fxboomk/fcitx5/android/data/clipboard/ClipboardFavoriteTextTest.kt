/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.clipboard

import android.content.ClipDescription
import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.util.UUID

class ClipboardFavoriteTextTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun preservesSelectedTextAndBypassesTransformerWithoutChangingLastClipboardEntry() = withDatabase { dao ->
        val text = "  selected text\n保留原文和空白  "
        var transformations = 0
        ClipboardManager.transformer = { transformations++; "changed" }
        val lastEntry = ClipboardManager.lastEntry

        val entry = requireNotNull(ClipboardManager.addFavoriteText(text))

        assertTrue(entry.id > 0)
        assertEquals(text, entry.text)
        assertTrue(entry.pinned)
        assertEquals(ClipboardEntry.SOURCE_LOCAL, entry.source)
        assertEquals(ClipDescription.MIMETYPE_TEXT_PLAIN, entry.type)
        assertEquals(entry, dao.get(entry.id))
        assertEquals(listOf(entry), dao.searchFavoriteTextEntries("保留原文"))
        assertEquals(0, transformations)
        assertEquals(lastEntry, ClipboardManager.lastEntry)
        assertEquals(1, ClipboardManager.itemCount)
    }

    @Test
    fun favoriteSurvivesZeroLocalHistoryLimitWithListeningDisabled() = withDatabase { dao ->
        assertEquals(0, (field("localLimitPref").get(ClipboardManager) as ManagedPreference<*>).getValue())
        assertEquals(false, (field("enabledPref").get(ClipboardManager) as ManagedPreference<*>).getValue())
        val entry = requireNotNull(ClipboardManager.addFavoriteText("favorite kept with history disabled"))
        val unpinnedRowId = dao.insert(ClipboardEntry(text = "ordinary local history"))

        // Existing insertion runs the real trim policy against the isolated database.
        ClipboardManager.importRemoteEntry("remote insertion triggers trimming")

        assertNotNull(dao.get(entry.id))
        assertTrue(requireNotNull(dao.get(entry.id)).pinned)
        assertNull(dao.get(unpinnedRowId))
        assertEquals(listOf(entry), dao.searchFavoriteTextEntries("favorite kept"))
    }

    @Test
    fun repeatedAndConcurrentFavoritesPinExistingLocalEntryWithoutDuplicates() = withDatabase { dao ->
        val originalRowId = dao.insert(ClipboardEntry(text = "already copied", timestamp = 1))
        val remoteRowId = dao.insert(ClipboardEntry(text = "already copied", source = ClipboardEntry.SOURCE_REMOTE))
        val first = requireNotNull(ClipboardManager.addFavoriteText("already copied"))
        assertEquals(originalRowId, first.id.toLong())
        assertTrue(first.timestamp > 1)

        val entries = coroutineScope {
            (0 until 6).map { async(Dispatchers.Default) { ClipboardManager.addFavoriteText("already copied") } }.awaitAll()
        }

        assertTrue(entries.all { it != null && it.id == first.id && it.pinned })
        assertEquals(2, dao.itemCount())
        assertTrue(requireNotNull(dao.get(originalRowId)).pinned)
        assertFalse(requireNotNull(dao.get(remoteRowId)).pinned)
        assertEquals(2, ClipboardManager.itemCount)
    }

    @Test
    fun blankSelectionsDoNotCreateFavorites() = withDatabase { dao ->
        listOf("", "  ", "\n\t").forEach { assertNull(ClipboardManager.addFavoriteText(it)) }
        assertEquals(0, dao.itemCount())
        assertEquals(0, ClipboardManager.itemCount)
    }

    private fun withDatabase(block: suspend (ClipboardDao) -> Unit) = runBlocking {
        // Swap only test resources: never clear the real clipboard table or change user preferences.
        val database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java).build()
        val prefsName = "clipboard-favorite-test-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val mutex = field("mutex").get(ClipboardManager) as Mutex
        val fields = listOf("clbDb", "clbDao", "localLimitPref", "enabledPref", "itemCount", "lastEntry", "transformer")
            .map(::field)
        ClipboardManager.coroutineContext[Job]?.children?.toList()?.joinAll()
        val originals = mutex.withLock {
            val values = fields.associateWith { it.get(ClipboardManager) }
            field("clbDb").set(ClipboardManager, database)
            field("clbDao").set(ClipboardManager, database.clipboardDao())
            field("localLimitPref").set(ClipboardManager, ManagedPreference.PInt(prefs, "localLimit", 0))
            field("enabledPref").set(ClipboardManager, ManagedPreference.PBool(prefs, "listening", false))
            field("itemCount").setInt(ClipboardManager, 0)
            values
        }
        try {
            block(database.clipboardDao())
        } finally {
            ClipboardManager.coroutineContext[Job]?.children?.toList()?.joinAll()
            mutex.withLock { originals.forEach { (field, value) -> field.set(ClipboardManager, value) } }
            database.close()
            context.deleteSharedPreferences(prefsName)
        }
    }

    private fun field(name: String): Field = ClipboardManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
