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
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.stageForCommit
import org.fcitx.fcitx5.android.utils.ClipboardUriStore.toClipboardUriOrNull
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

    private fun normalizeEntry(entry: ClipboardEntry): ClipboardEntry {
        if (entry.text.startsWith("content://") || entry.text.startsWith("file://")) {
            // For URI entries (like clipboard images), try to stage the content
            // so we have a local copy with proper permissions
            val staged = normalizeClipboardText(appContext, entry.text)
            return if (staged == entry.text) {
                // Staging failed or returned same URI, keep original
                entry
            } else {
                // Staging succeeded, use FileProvider URI
                entry.copy(text = staged)
            }
        }
        val normalizedText = normalizeClipboardText(appContext, entry.text)
        val normalizedOriginalText = when {
            entry.originalText.isNotEmpty() -> entry.originalText
            normalizedText == entry.text -> ""
            else -> originalClipboardTextOrEmpty(entry.text, normalizedText)
        }
        return if (normalizedText == entry.text && normalizedOriginalText == entry.originalText) {
            entry
        } else {
            entry.copy(text = normalizedText, originalText = normalizedOriginalText)
        }
    }

    private suspend fun insertOrUpdateEntry(
        entry: ClipboardEntry,
        notifyListeners: Boolean
    ): ClipboardEntry {
        val normalizedEntry = normalizeEntry(entry)
        if (normalizedEntry.text.isBlank()) return normalizedEntry
        return try {
            clbDao.find(normalizedEntry.text, normalizedEntry.sensitive, normalizedEntry.source)?.let {
                val updated = it.copy(
                    timestamp = normalizedEntry.timestamp,
                    originalText = normalizedEntry.originalText,
                    originalRootUri = normalizedEntry.originalRootUri,
                    type = normalizedEntry.type
                )
                if (notifyListeners) {
                    updateLastEntry(updated)
                }
                clbDao.updateTime(it.id, normalizedEntry.timestamp)
                updated
            } ?: run {
                val insertedEntry = clbDb.withTransaction {
                    val rowId = clbDao.insert(normalizedEntry)
                    removeOutdated()
                    // new entry can be deleted immediately if clipboard limit == 0
                    clbDao.get(rowId) ?: normalizedEntry
                }
                if (notifyListeners) {
                    updateLastEntry(insertedEntry)
                }
                updateItemCount()
                insertedEntry
            }
        } catch (exception: Exception) {
            Timber.w("Failed to update clipboard database: $exception")
            if (notifyListeners) {
                updateLastEntry(normalizedEntry)
            }
            normalizedEntry
        }
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

    suspend fun importRemoteEntry(
        text: String,
        originalText: String = "",
        originalRootUri: String = "",
        type: String = android.content.ClipDescription.MIMETYPE_TEXT_PLAIN,
        timestamp: Long = System.currentTimeMillis(),
        sensitive: Boolean = false,
        notifyListeners: Boolean = false
    ): ClipboardEntry? {
        if (text.isBlank()) return null
        return mutex.withLock {
            insertOrUpdateEntry(
                ClipboardEntry(
                    text = text,
                    originalText = originalText,
                    originalRootUri = originalRootUri,
                    timestamp = timestamp,
                    type = type,
                    source = ClipboardEntry.SOURCE_REMOTE,
                    sensitive = sensitive
                ),
                notifyListeners = notifyListeners
            )
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
                val entry = ClipboardEntry.fromClipData(clip, transformer) ?: return@withLock
                // For URI entries (clipboard images), try to stage the content immediately
                // while we have clipboard permission, so we have a local copy
                var finalEntry = entry
                if (entry.isUriEntry() && entry.type.startsWith("image/")) {
                    val staged = stageForCommit(appContext, entry.text.toClipboardUriOrNull()!!)
                    if (staged != null) {
                        finalEntry = entry.copy(text = staged.uri.toString())
                        Timber.d("Staged clipboard image to local file: ${staged.uri}")
                    } else {
                        Timber.w("Failed to stage clipboard image URI: ${entry.text}")
                    }
                }
                insertOrUpdateEntry(finalEntry, notifyListeners = true)
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
        if (!isUriEntry()) return null
        val rawUri = if (source == ClipboardEntry.SOURCE_REMOTE && originalText.isNotEmpty()) {
            originalText.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        } else {
            text.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        } ?: return null
        val rootUri = if (source == ClipboardEntry.SOURCE_REMOTE && originalRootUri.isNotEmpty()) {
            originalRootUri.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        } else {
            rawUri
        } ?: return null
        return ClipboardSourceDeletionTarget(rawUri = rawUri, rootUri = rootUri)
    }

}
