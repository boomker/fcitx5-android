/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.ClipboardSourceDeletionTarget
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.deleteClipboardSourceFile
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.normalizeClipboardText
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.originalClipboardTextOrEmpty
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val localLimitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimitLocal
    private val remoteLimitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimitRemote
    private val mediaLimitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimitMedia

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch { removeOutdated() }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        clbDb = Room
            .databaseBuilder(context, ClipboardDatabase::class.java, "clbdb")
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
        clbDao = clbDb.clipboardDao()
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(localLimitPref.key, localLimitPref.getValue())
        localLimitPref.registerOnChangeListener(limitListener)
        remoteLimitPref.registerOnChangeListener(limitListener)
        mediaLimitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned(category: ClipboardCategory) = when (category) {
        ClipboardCategory.All -> clbDao.findUnpinnedIds().isNotEmpty()
        ClipboardCategory.Favorites -> false
        ClipboardCategory.Local -> clbDao.haveUnpinnedTextEntriesBySource(ClipboardEntry.SOURCE_LOCAL)
        ClipboardCategory.Remote -> clbDao.haveUnpinnedTextEntriesBySource(ClipboardEntry.SOURCE_REMOTE)
        ClipboardCategory.Media -> clbDao.haveUnpinnedMediaEntries()
    }

    fun allEntries() = clbDao.allEntries()

    fun favoriteEntries() = clbDao.favoriteEntries()

    fun localTextEntries() = clbDao.textEntriesBySource(ClipboardEntry.SOURCE_LOCAL)

    fun remoteTextEntries() = clbDao.textEntriesBySource(ClipboardEntry.SOURCE_REMOTE)

    fun mediaEntries() = clbDao.mediaEntries()

    suspend fun pin(id: Int) = clbDao.updatePinStatus(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinStatus(id, false)

    suspend fun updateText(id: Int, text: String) {
        lastEntry?.let {
            if (id == it.id) updateLastEntry(it.copy(text = text))
        }
        clbDao.updateText(id, text)
    }

    suspend fun delete(id: Int) {
        clbDao.markAsDeleted(id)
        updateItemCount()
    }

    suspend fun deleteAll(category: ClipboardCategory, skipPinned: Boolean = true): IntArray {
        val ids = when (category) {
            ClipboardCategory.All -> {
                if (skipPinned) {
                    clbDao.findUnpinnedIds()
                } else {
                    clbDao.findAllIds()
                }
            }

            ClipboardCategory.Favorites -> {
                if (skipPinned) {
                    intArrayOf()
                } else {
                    clbDao.findPinnedIds()
                }
            }

            ClipboardCategory.Local -> {
                if (skipPinned) {
                    clbDao.findUnpinnedTextEntryIdsBySource(ClipboardEntry.SOURCE_LOCAL)
                } else {
                    clbDao.findAllTextEntryIdsBySource(ClipboardEntry.SOURCE_LOCAL)
                }
            }

            ClipboardCategory.Remote -> {
                if (skipPinned) {
                    clbDao.findUnpinnedTextEntryIdsBySource(ClipboardEntry.SOURCE_REMOTE)
                } else {
                    clbDao.findAllTextEntryIdsBySource(ClipboardEntry.SOURCE_REMOTE)
                }
            }

            ClipboardCategory.Media -> {
                if (skipPinned) {
                    clbDao.findUnpinnedMediaEntryIds()
                } else {
                    clbDao.findAllMediaEntryIds()
                }
            }
        }
        if (ids.isNotEmpty()) {
            clbDao.markAsDeleted(*ids)
            updateItemCount()
        }
        return ids
    }

    suspend fun mediaDeletionTargets(skipPinned: Boolean): Map<Int, ClipboardSourceDeletionTarget> {
        val entries = if (skipPinned) {
            clbDao.getAllUnpinnedMediaEntries()
        } else {
            clbDao.getAllMediaEntries()
        }
        return entries.mapNotNull { entry ->
            entry.remoteMediaDeletionSource()?.let { entry.id to it }
        }.toMap()
    }

    suspend fun undoDelete(vararg ids: Int) {
        clbDao.undoDelete(*ids)
        updateItemCount()
    }

    suspend fun realDelete() {
        clbDao.realDelete()
    }

    suspend fun nukeTable() {
        withContext(coroutineContext) {
            clbDb.clearAllTables()
            updateItemCount()
        }
    }

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            mutex.withLock {
                val entry = ClipboardEntry.fromClipData(clip, transformer)?.let {
                    val normalizedText = normalizeClipboardText(appContext, it.text)
                    val originalText = originalClipboardTextOrEmpty(it.text, normalizedText)
                    if (normalizedText == it.text && originalText.isEmpty()) {
                        it
                    } else {
                        it.copy(text = normalizedText, originalText = originalText)
                    }
                } ?: return@withLock
                if (entry.text.isBlank()) return@withLock
                try {
                    clbDao.find(entry.text, entry.sensitive, entry.source)?.let {
                        updateLastEntry(it.copy(timestamp = entry.timestamp))
                        clbDao.updateTime(it.id, entry.timestamp)
                        return@withLock
                    }
                    val insertedEntry = clbDb.withTransaction {
                        val rowId = clbDao.insert(entry)
                        removeOutdated()
                        // new entry can be deleted immediately if clipboard limit == 0
                        clbDao.get(rowId) ?: entry
                    }
                    updateLastEntry(insertedEntry)
                    updateItemCount()
                } catch (exception: Exception) {
                    Timber.w("Failed to update clipboard database: $exception")
                    updateLastEntry(entry)
                }
            }
        }
    }

    private suspend fun removeOutdated() {
        var deletedAny = false
        deletedAny = trimOutdatedEntries(
            clbDao.getAllUnpinnedTextEntriesBySource(ClipboardEntry.SOURCE_LOCAL),
            localLimitPref.getValue()
        ) || deletedAny
        deletedAny = trimOutdatedEntries(
            clbDao.getAllUnpinnedTextEntriesBySource(ClipboardEntry.SOURCE_REMOTE),
            remoteLimitPref.getValue()
        ) || deletedAny
        deletedAny = trimOutdatedEntries(
            clbDao.getAllUnpinnedMediaEntries(),
            mediaLimitPref.getValue()
        ) || deletedAny
        if (deletedAny) {
            updateItemCount()
        }
    }

    private suspend fun trimOutdatedEntries(entries: List<ClipboardEntry>, limit: Int): Boolean {
        if (entries.size <= limit) {
            return false
        }
        val retained = entries
            .sortedBy { it.id }
            .takeLast(limit.coerceAtLeast(0))
            .mapTo(hashSetOf()) { it.id }
        val toDelete = entries
            .asSequence()
            .map { it.id }
            .filter { it !in retained }
            .toList()
        if (toDelete.isEmpty()) {
            return false
        }
        clbDao.markAsDeleted(*toDelete.toIntArray())
        return true
    }

    suspend fun deleteClipboardSourceFiles(targets: Collection<ClipboardSourceDeletionTarget>) {
        withContext(Dispatchers.IO) {
            targets.forEach { target ->
                runCatching { deleteClipboardSourceFile(appContext, target) }
                    .onFailure { Timber.w(it, "Failed to delete clipboard source file: %s", target.rawUri) }
            }
        }
    }

    private fun ClipboardEntry.remoteMediaDeletionSource(): ClipboardSourceDeletionTarget? {
        if (source != ClipboardEntry.SOURCE_REMOTE) return null
        val rawUri = originalText.takeIf { it.startsWith("content://") || it.startsWith("file://") } ?: return null
        val rootUri = originalRootUri.takeIf { it.startsWith("content://") || it.startsWith("file://") } ?: return null
        return ClipboardSourceDeletionTarget(rawUri = rawUri, rootUri = rootUri)
    }

}
