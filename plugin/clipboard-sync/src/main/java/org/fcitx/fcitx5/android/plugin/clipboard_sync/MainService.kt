package org.fcitx.fcitx5.android.plugin.clipboard_sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.PersistableBundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.common.ClipboardMetadata
import org.fcitx.fcitx5.android.common.FcitxPluginService
import org.fcitx.fcitx5.android.common.PluginMessage
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.ui.PluginSettingsActivity
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ClipCascadeClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ClipCascadeClipboardData
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ClipboardData
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.OneClipEventClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient.ServerBackend
import org.fcitx.fcitx5.android.plugin.clipboard_sync.service.QuickSyncTileService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.ui.StoragePathUtils
import java.io.IOException
import java.util.Locale

class MainService : FcitxPluginService() {

    companion object {
        private const val TAG = "FcitxClipboardSync"
        private const val PREF_QUICK_SYNC = "quick_sync"
        private const val PREF_SYNC_INTERVAL = "sync_interval"
        private const val PREF_BACKGROUND_KEEP_ALIVE = "background_keep_alive"
        private const val PREF_USERNAME = "username"
        private const val PREF_PASSWORD = "password"
        private const val SERVER_PROFILE_TYPE_KEY = "server_profile_type"
        private const val SERVER_ADDRESS_KEY = "server_address"
        private const val SERVER_ADDRESS_SYNC_CLIPBOARD_KEY = "server_address_syncclipboard"
        private const val SERVER_ADDRESS_ONE_CLIP_KEY = "server_address_oneclip"
        private const val SERVER_ADDRESS_CLIP_CASCADE_KEY = "server_address_clipcascade"
        private const val SERVER_ADDRESS_CUSTOM_KEY = "server_address_custom"
        private const val PROFILE_SYNC_CLIPBOARD = "syncclipboard"
        private const val PROFILE_ONE_CLIP = "oneclip"
        private const val PROFILE_CLIP_CASCADE = "clipcascade"
        private const val PROFILE_CUSTOM = "custom"
        private const val DEFAULT_SYNC_CLIPBOARD_URL = "http://192.168.10.11:5003"
        private const val DEFAULT_ONE_CLIP_URL = "http://192.168.10.11:8899"
        private const val DEFAULT_CLIP_CASCADE_URL = "http://192.168.10.11:8080"
        private val CONNECTIVITY_RETRY_DELAYS_MS = longArrayOf(3_000L, 10_000L, 30_000L)
        private const val EVENT_BACKEND_HEALTH_CHECK_MS = 30_000L
        private const val EVENT_BACKEND_FALLBACK_PULL_MS = 60_000L
        private const val EVENT_BACKEND_STALE_RECONNECT_MS = 120_000L
        private const val SCREEN_OFF_POLL_INTERVAL_SECONDS = 15L
        private const val POWER_SAVE_POLL_INTERVAL_SECONDS = 30L
        private const val AGGRESSIVE_POLL_INTERVAL_SECONDS = 60L
        private const val SCREEN_OFF_HEALTH_CHECK_MS = 60_000L
        private const val POWER_SAVE_HEALTH_CHECK_MS = 120_000L
        private const val AGGRESSIVE_HEALTH_CHECK_MS = 180_000L
        private const val SCREEN_OFF_FALLBACK_PULL_MS = 180_000L
        private const val POWER_SAVE_FALLBACK_PULL_MS = 240_000L
        private const val AGGRESSIVE_FALLBACK_PULL_MS = 300_000L
        private const val SCREEN_OFF_STALE_RECONNECT_MS = 300_000L
        private const val POWER_SAVE_STALE_RECONNECT_MS = 420_000L
        private const val AGGRESSIVE_STALE_RECONNECT_MS = 600_000L
        private const val NETWORK_RECONNECT_DEBOUNCE_MS = 2_000L
        private const val NOTIFICATION_CHANNEL_ID = "clipboard-sync-keepalive"
        private const val NOTIFICATION_ID = 1302
        private const val ACTION_START_SYNC = "org.fcitx.fcitx5.android.plugin.clipboard_sync.action.START"
        private const val ACTION_RECONNECT_SYNC = "org.fcitx.fcitx5.android.plugin.clipboard_sync.action.RECONNECT"
        private const val ACTION_PAUSE_SYNC = "org.fcitx.fcitx5.android.plugin.clipboard_sync.action.PAUSE"
        private const val EXTRA_START_REASON = "start_reason"
        private const val EXTRA_FORCE_ENABLE_SYNC = "force_enable_sync"
        private const val PREF_PENDING_UPLOADS = "pending_uploads"
        private const val PREF_REMOTE_REVISIONS = "remote_revisions"
        private const val PREF_LAST_SYNCED_CONTENT = "last_synced_content"
        private const val MAX_PENDING_UPLOADS = 50

        fun shouldAutoStart(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(PREF_QUICK_SYNC, true)
        }

        fun startSyncService(context: Context, reason: String, forceEnableSync: Boolean = false) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val intent = Intent(context, MainService::class.java).apply {
                action = ACTION_START_SYNC
                putExtra(EXTRA_START_REASON, reason)
                putExtra(EXTRA_FORCE_ENABLE_SYNC, forceEnableSync)
            }
            if (prefs.getBoolean(PREF_BACKGROUND_KEEP_ALIVE, true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override val stopOnUnbind: Boolean = false
    override val handler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                PluginMessage.WHAT_LOCAL_CLIPBOARD_UPDATED -> {
                    val content = msg.data?.getString(PluginMessage.KEY_CLIPBOARD_TEXT).orEmpty()
                    handleLocalClipboardUpdate(content, "fcitx-plugin-message")
                }

                else -> super.handleMessage(msg)
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private var connection: FcitxRemoteConnection? = null
    private var syncJob: Job? = null
    private var healthMonitorJob: Job? = null
    private var networkReconnectJob: Job? = null
    private var scope = createScope()
    private var transformerRegistered = false
    private var serviceRunning = false
    private var selfStarted = false
    private var networkCallbackRegistered = false
    private var foregroundActive = false
    private var screenStateReceiverRegistered = false
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private val powerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    private var clipboardListenerRegistered = false
    private var prefsListenerRegistered = false
    private var clipCascadeClient: ClipCascadeClient? = null
    private var oneClipClient: OneClipEventClient? = null
    private var lastNetworkAvailableAt = 0L
    private var connectionSessionId = 0
    private var activeEndpointIdentity: String? = null

    // Cache to avoid circular updates (Pull -> Local -> Push -> Loop)
    private var lastLocalContent: String? = null
    private var lastRemoteContent: String? = null
    private var lastRemoteRevision: String? = null
    private var lastUploadedContent: String? = null
    private var lastSuccessfulRemoteSyncAt = 0L
    private var lastBackendActivityAt = 0L
    private val remoteFetchMutex = Mutex()
    private val pendingUploadMutex = Mutex()
    private val pendingUploadDrainMutex = Mutex()
    private val pendingUploads = mutableListOf<PendingUploadEntry>()
    private val storedRemoteRevisions = mutableMapOf<String, String>()
    private val stateJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now = SystemClock.elapsedRealtime()
            lastNetworkAvailableAt = now
            scheduleReconnect("network-available")
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastNetworkAvailableAt >= NETWORK_RECONNECT_DEBOUNCE_MS) {
                    lastNetworkAvailableAt = now
                    scheduleReconnect("network-capabilities")
                }
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleSystemClipboardChanged()
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> Log.d(TAG, "[Power] Screen turned off, sync will switch to a lower-power interval")
                Intent.ACTION_SCREEN_ON -> scheduleReconnect("screen-on")
                Intent.ACTION_USER_PRESENT -> scheduleReconnect("user-present")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> scheduleReconnect("power-save-mode")
            }
        }
    }

    private val transformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 100

        override fun transform(clipboardText: String): String {
            // This is called when user copies text locally
            if (clipboardText == lastRemoteContent) {
                // If this change matches what we just pulled, ignore it (don't push back)
                return clipboardText
            }

            handleLocalClipboardUpdate(clipboardText, "transformer")
            return clipboardText
        }

        override fun getDescription(): String = "SyncClipboard"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MainService onCreate")
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        loadPersistentSyncState()
        lastUploadedContent = prefs.getString(PREF_LAST_SYNCED_CONTENT, null)
        createNotificationChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureScope()
        selfStarted = true
        handleActionIntent(intent)
        start()
        return START_STICKY
    }

    override fun start() {
        ensureScope()
        if (serviceRunning) {
            updateForegroundState()
            refreshSyncRuntime()
            ensureRemoteBinding()
            return
        }
        Log.d(TAG, "MainService start")
        serviceRunning = true
        ensureSelfStarted()
        updateForegroundState()
        registerClipboardListenerIfNeeded()
        registerPrefsListenerIfNeeded()
        registerNetworkCallbackIfNeeded()
        registerScreenStateReceiverIfNeeded()
        refreshSyncRuntime()
        ensureRemoteBinding(forceRebind = true)
        handleSystemClipboardChanged()
    }

    override fun stop() {
        if (!serviceRunning) return
        Log.d(TAG, "MainService stop")
        serviceRunning = false
        unregisterClipboardListenerIfNeeded()
        unregisterPrefsListenerIfNeeded()
        unregisterNetworkCallbackIfNeeded()
        unregisterScreenStateReceiverIfNeeded()
        stopPeriodicSync()
        stopHealthMonitor()
        stopForegroundState()
        connectionSessionId += 1
        val activeConnection = connection
        connection = null
        runCatching {
            if (transformerRegistered) {
                activeConnection?.remoteService?.unregisterClipboardEntryTransformer(transformer)
            }
        }
        transformerRegistered = false
        runCatching {
            if (activeConnection != null) {
                unbindService(activeConnection)
            }
        }
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroy() {
        unregisterClipboardListenerIfNeeded()
        unregisterPrefsListenerIfNeeded()
        unregisterNetworkCallbackIfNeeded()
        unregisterScreenStateReceiverIfNeeded()
        stopPeriodicSync()
        stopHealthMonitor()
        stopForegroundState()
        connectionSessionId += 1
        connection = null
        scope.cancel()
        serviceRunning = false
        selfStarted = false
        super.onDestroy()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SERVER_ADDRESS_KEY || key == PREF_USERNAME || key == PREF_PASSWORD || key == SERVER_PROFILE_TYPE_KEY) {
            Log.d(TAG, "Sync credential/config changed: $key, resetting sync cache")
            resetRemoteCache()
            resetFailureState()
            refreshSyncRuntime()
        } else if (
            key == SyncFilterPrefs.PREF_FILTER_BLOCKED_EXTENSIONS ||
            key == SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE ||
            key == SyncFilterPrefs.PREF_FILTER_MAX_FILE_SIZE_UNIT ||
            key == SyncFilterPrefs.PREF_FILTER_MIN_TEXT_CHARS ||
            key == SyncFilterPrefs.PREF_FILTER_MAX_TEXT_CHARS
        ) {
            Log.d(TAG, "Receive filter changed: $key")
        } else if (key == PREF_QUICK_SYNC || key == PREF_SYNC_INTERVAL || key == PREF_BACKGROUND_KEEP_ALIVE) {
            Log.d(TAG, "Preference changed: $key, restarting sync")
            updateForegroundState()
            refreshSyncRuntime()
            if (key == PREF_QUICK_SYNC) {
                QuickSyncTileService.requestTileRefresh(this)
            }
        }
    }

    private fun handleLocalClipboardUpdate(content: String, origin: String) {
        if (content.isBlank()) return
        if (content == lastRemoteContent || content == lastUploadedContent) {
            return
        }
        if (content != lastLocalContent) {
            lastLocalContent = content
        }
        scope.launch {
            val queued = enqueuePendingUpload(content)
            if (!queued) {
                return@launch
            }
            Log.d(TAG, "[Push] Detected local change from $origin, queued for upload")
            flushPendingUploads("local-change:$origin")
        }
    }

    private fun refreshSyncRuntime() {
        if (!prefs.getBoolean(PREF_QUICK_SYNC, true)) {
            stopPeriodicSync()
            stopHealthMonitor()
            return
        }
        ensureSelfStarted()
        startPeriodicSync()
        startHealthMonitor()
    }

    private fun startHealthMonitor() {
        if (healthMonitorJob?.isActive == true) return
        healthMonitorJob = scope.launch {
            while (isActive) {
                delay(currentHealthCheckDelayMs())
                if (!prefs.getBoolean(PREF_QUICK_SYNC, true)) {
                    continue
                }
                val endpoint = currentEndpoint()
                val backend = endpoint.backend
                if (backend == ServerBackend.SYNCCLIPBOARD) {
                    if (syncJob?.isActive != true) {
                        Log.w(TAG, "[Health] Polling loop is inactive, restarting")
                        startPeriodicSync()
                    }
                    continue
                }

                val connected = when (backend) {
                    ServerBackend.CLIPCASCADE -> clipCascadeClient?.isConnected() == true
                    ServerBackend.ONECLIP -> oneClipClient?.isConnected() == true
                    ServerBackend.SYNCCLIPBOARD -> true
                }
                if (!connected || syncJob?.isActive != true) {
                    Log.w(TAG, "[Health] Event stream is disconnected, restarting $backend")
                    startPeriodicSync()
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastSuccessfulRemoteSyncAt >= currentFallbackPullDelayMs()) {
                    try {
                        checkRemoteClipboard()
                        flushPendingUploads("health-fallback")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "[Health] Fallback pull failed for ${endpoint.address}", error)
                        handleConnectivityFailure(endpoint, error)
                    }
                }

                if (now - lastBackendActivityAt >= currentStaleReconnectDelayMs()) {
                    Log.w(TAG, "[Health] Backend stream appears stale, forcing reconnect for $backend")
                    startPeriodicSync()
                }
            }
        }
    }

    private fun stopHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = null
    }

    private fun startPeriodicSync() {
        stopPeriodicSync()

        val quickSync = prefs.getBoolean(PREF_QUICK_SYNC, true)
        if (!quickSync) {
            Log.d(TAG, "[Pull] Quick sync disabled, stopping background polling")
            return
        }

        when (currentBackend()) {
            ServerBackend.CLIPCASCADE -> {
                Log.d(TAG, "[ClipCascade] Starting persistent websocket sync")
                startClipCascadeSync()
                return
            }

            ServerBackend.ONECLIP -> {
                Log.d(TAG, "[OneClip] Starting persistent SSE sync")
                startOneClipSync()
                return
            }

            ServerBackend.SYNCCLIPBOARD -> Unit
        }

        Log.d(TAG, "[Pull] Starting periodic sync")
        syncJob = scope.launch {
            while (isActive) {
                val endpoint = currentEndpoint()
                try {
                    val safeInterval = prefs.getString(PREF_SYNC_INTERVAL, "3")?.toLongOrNull()
                        ?.coerceIn(1, 60)
                        ?: 3L

                    checkRemoteClipboard()
                    resetFailureState()
                    flushPendingUploads("poll-loop")

                    delay(currentPollingIntervalSeconds(safeInterval) * 1000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (handleConnectivityFailure(endpoint, e)) {
                        continue
                    }
                    Log.e(TAG, "[Pull] Loop error", e)
                    delay(5000)
                }
            }
        }
    }

    private fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        disconnectClipCascadeClient()
        disconnectOneClipClient()
    }

    private fun startClipCascadeSync() {
        disconnectClipCascadeClient()
        syncJob = scope.launch {
            while (isActive) {
                val endpoint = currentEndpoint()
                if (endpoint.address.isBlank()) {
                    Log.d(TAG, "[ClipCascade] Server address is blank, skipping websocket sync")
                    return@launch
                }

                val username = currentUsernameForProfile(endpoint.profileKey)
                val password = currentPasswordForProfile(endpoint.profileKey)
                val client = ClipCascadeClient(
                    serverUrl = endpoint.address,
                    username = username,
                    password = password
                )
                clipCascadeClient = client

                try {
                    client.connect { data ->
                        markBackendActivity()
                        handleClipCascadeMessage(data)
                    }
                    markBackendActivity()
                    resetFailureState()
                    flushPendingUploads("clipcascade-connected")
                    val closeCause = client.awaitClose()
                    if (!isActive) {
                        return@launch
                    }

                    val error = closeCause as? Exception
                        ?: IOException("ClipCascade websocket disconnected")
                    if (handleConnectivityFailure(endpoint, error)) {
                        continue
                    }
                    delay(5000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (handleConnectivityFailure(endpoint, e)) {
                        continue
                    }
                    Log.e(TAG, "[ClipCascade] Connection loop error", e)
                    delay(5000)
                } finally {
                    if (clipCascadeClient === client) {
                        clipCascadeClient = null
                    }
                    client.close()
                }
            }
        }
    }

    private fun startOneClipSync() {
        disconnectOneClipClient()
        syncJob = scope.launch {
            while (isActive) {
                val endpoint = currentEndpoint()
                if (endpoint.address.isBlank()) {
                    Log.d(TAG, "[OneClip] Server address is blank, skipping SSE sync")
                    return@launch
                }

                val client = OneClipEventClient(endpoint.address)
                oneClipClient = client

                try {
                    client.connect { event ->
                        markBackendActivity()
                        if (!event.update) {
                            return@connect
                        }
                        scope.launch {
                            runCatching { checkRemoteClipboard() }
                                .onFailure { error ->
                                    Log.e(TAG, "[OneClip] Failed to refresh clipboard after SSE event", error)
                                }
                        }
                    }

                    checkRemoteClipboard()
                    markBackendActivity()
                    resetFailureState()
                    flushPendingUploads("oneclip-connected")

                    val closeCause = client.awaitClose()
                    if (!isActive) {
                        return@launch
                    }

                    val error = closeCause as? Exception
                        ?: IOException("OneClip SSE disconnected")
                    if (handleConnectivityFailure(endpoint, error)) {
                        continue
                    }
                    delay(5000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (handleConnectivityFailure(endpoint, e)) {
                        continue
                    }
                    Log.e(TAG, "[OneClip] Connection loop error", e)
                    delay(5000)
                } finally {
                    if (oneClipClient === client) {
                        oneClipClient = null
                    }
                    client.close()
                }
            }
        }
    }

    private suspend fun checkRemoteClipboard() {
        remoteFetchMutex.withLock {
            val endpoint = currentEndpoint()
            ensureEndpointState(endpoint)
            val url = endpoint.address
            val user = currentUsernameForProfile(endpoint.profileKey)
            val pass = currentPasswordForProfile(endpoint.profileKey)
            val backend = endpoint.backend

            if (url.isBlank()) return

            val downloadPath = prefs.getString("download_path", null)
            val downloadUri = StoragePathUtils.resolveDownloadUri(
                displayPath = downloadPath,
                storedUri = prefs.getString("download_path_uri", null)
            )

            val result = SyncClient.fetchClipboard(
                context = this,
                serverUrl = url,
                username = user,
                pass = pass,
                backend = backend,
                lastRevision = lastRemoteRevision,
                downloadDirUri = downloadUri,
                preDownloadFilter = ::shouldAcceptIncomingMetadata
            )
            noteRemoteSyncSuccess()

            if (result.revision != null) {
                lastRemoteRevision = result.revision
                persistRemoteRevision(endpoint, result.revision)
            }

            val fetchedItems = result.items
            if (fetchedItems.isEmpty()) {
                return
            }
            for (data in fetchedItems) {
                if (!shouldAcceptIncomingClipboard(data)) {
                    Log.d(TAG, "[Pull] Incoming clipboard rejected by receive filter: type=${data.type} name=${data.dataName}")
                    continue
                }

                val remoteText = data.text
                Log.d(TAG, "[Pull] Processed data: type=${data.type}, text=$remoteText")
                acknowledgePendingUploads(remoteText)

                if (
                    remoteText.isNotEmpty() &&
                    remoteText != lastLocalContent &&
                    remoteText != lastRemoteContent &&
                    remoteText != lastUploadedContent
                ) {
                    Log.d(TAG, "[Pull] Remote content changed, updating local")
                    lastRemoteContent = remoteText
                    lastLocalContent = remoteText
                    lastUploadedContent = remoteText
                    persistLastSyncedContent(remoteText)

                    withContext(Dispatchers.Main) {
                        if (data.type == "Text") {
                            updateSystemClipboard(remoteText)
                        } else {
                            updateSystemClipboardWithUri(Uri.parse(remoteText))
                        }
                    }
                }
            }
        }
    }

    private fun updateSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", text).also(::markRemoteClip)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated (Text)")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard", e)
        }
    }

    private fun updateSystemClipboardWithUri(uri: Uri) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(contentResolver, "SyncClipboard", uri).also(::markRemoteClip)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated with URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard with URI", e)
        }
    }

    private fun markRemoteClip(clip: ClipData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        clip.description.extras = PersistableBundle().apply {
            putString(ClipboardMetadata.EXTRA_SOURCE, ClipboardMetadata.SOURCE_REMOTE)
        }
    }

    private fun handleSystemClipboardChanged() {
        val content = readClipboardContent() ?: return
        handleLocalClipboardUpdate(content, "system-clipboard")
    }

    private fun handleClipCascadeMessage(data: ClipCascadeClipboardData) {
        val normalizedType = data.type.lowercase(Locale.ROOT)
        val payloadFingerprint = buildClipCascadePayloadFingerprint(data)
        Log.d(TAG, "[ClipCascade] Received remote payload type=$normalizedType size=${data.payload.length}")
        if (payloadFingerprint.isEmpty() ||
            payloadFingerprint == lastLocalContent ||
            payloadFingerprint == lastRemoteContent ||
            payloadFingerprint == lastUploadedContent
        ) {
            return
        }

        scope.launch {
            when (normalizedType) {
                "text" -> {
                    if (!shouldAcceptIncomingText(data.payload)) {
                        Log.d(TAG, "[ClipCascade] Rejected text payload by receive filter")
                        return@launch
                    }
                    acknowledgePendingUploads(data.payload)
                    rememberAcceptedRemotePayload(payloadFingerprint)
                    withContext(Dispatchers.Main) {
                        updateSystemClipboard(data.payload)
                    }
                }

                "image" -> handleClipCascadeImage(data, payloadFingerprint)

                "file_eager" -> handleClipCascadeFileEager(data, payloadFingerprint)

                "file_stub" -> {
                    if (!shouldAcceptIncomingBinary(data.filename, null)) {
                        Log.d(TAG, "[ClipCascade] Rejected file placeholder by receive filter")
                        return@launch
                    }
                    rememberAcceptedRemotePayload(payloadFingerprint)
                    withContext(Dispatchers.Main) {
                        updateSystemClipboard(buildClipCascadeFileStubSummary(data))
                    }
                }

                else -> Log.w(TAG, "[ClipCascade] Ignoring unsupported payload type: ${data.type}")
            }
        }
    }

    private suspend fun handleClipCascadeImage(data: ClipCascadeClipboardData, payloadFingerprint: String) {
        val bytes = runCatching { Base64.decode(data.payload, Base64.DEFAULT) }
            .getOrElse { error ->
                Log.e(TAG, "[ClipCascade] Failed to decode image payload", error)
                return
            }
        val downloadUri = resolveDownloadUri()
        val fileName = SyncClient.buildClipCascadeImageFileName(data.filename)
        if (!shouldAcceptIncomingBinary(fileName, bytes.size.toLong())) {
            Log.d(TAG, "[ClipCascade] Rejected image payload by receive filter: $fileName")
            return
        }
        val mimeType = SyncClient.guessClipCascadeImageMimeType(fileName)
        val savedUri = SyncClient.saveIncomingBytes(
            context = this,
            dirUri = downloadUri,
            fileName = fileName,
            bytes = bytes,
            mimeType = mimeType
        )
        if (savedUri != null) {
            rememberAcceptedRemotePayload(payloadFingerprint)
            withContext(Dispatchers.Main) {
                updateSystemClipboardWithUri(savedUri)
            }
        }
    }

    private suspend fun handleClipCascadeFileEager(data: ClipCascadeClipboardData, payloadFingerprint: String) {
        val bytes = runCatching { Base64.decode(data.payload, Base64.DEFAULT) }
            .getOrElse { error ->
                Log.e(TAG, "[ClipCascade] Failed to decode file payload", error)
                return
            }
        val downloadUri = resolveDownloadUri()
        val fileName = data.filename
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "ClipCascade-${System.currentTimeMillis()}.bin"
        if (!shouldAcceptIncomingBinary(fileName, bytes.size.toLong())) {
            Log.d(TAG, "[ClipCascade] Rejected file payload by receive filter: $fileName")
            return
        }
        val savedUri = SyncClient.saveIncomingBytes(
            context = this,
            dirUri = downloadUri,
            fileName = fileName,
            bytes = bytes
        )
        if (savedUri != null) {
            rememberAcceptedRemotePayload(payloadFingerprint)
            withContext(Dispatchers.Main) {
                updateSystemClipboardWithUri(savedUri)
            }
        }
    }

    private fun buildClipCascadePayloadFingerprint(data: ClipCascadeClipboardData): String {
        return when (data.type.lowercase(Locale.ROOT)) {
            "text" -> data.payload
            else -> "${data.type}:${data.filename.orEmpty()}:${data.payload.hashCode()}"
        }
    }

    private fun buildClipCascadeFileStubSummary(data: ClipCascadeClipboardData): String {
        val firstName = data.filename
            ?.takeIf { it.isNotBlank() }
            ?: data.payload.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstName.isNotBlank()) {
            "ClipCascade file placeholder: $firstName"
        } else {
            "ClipCascade file placeholder received"
        }
    }

    private fun resolveDownloadUri(): Uri? {
        val downloadPath = prefs.getString("download_path", null)
        return StoragePathUtils.resolveDownloadUri(
            displayPath = downloadPath,
            storedUri = prefs.getString("download_path_uri", null)
        )
    }

    private fun readClipboardContent(): String? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        item.uri?.toString()?.let { return it }
        item.text?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        return item.coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    private fun registerClipboardListenerIfNeeded() {
        if (clipboardListenerRegistered) return
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListenerIfNeeded() {
        if (!clipboardListenerRegistered) return
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        clipboardListenerRegistered = false
    }

    private fun registerPrefsListenerIfNeeded() {
        if (prefsListenerRegistered) return
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        prefsListenerRegistered = true
    }

    private fun unregisterPrefsListenerIfNeeded() {
        if (!prefsListenerRegistered) return
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        prefsListenerRegistered = false
    }

    private fun registerScreenStateReceiverIfNeeded() {
        if (screenStateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenStateReceiver, filter)
            }
            screenStateReceiverRegistered = true
        }.onFailure {
            Log.w(TAG, "Failed to register screen-state receiver", it)
        }
    }

    private fun unregisterScreenStateReceiverIfNeeded() {
        if (!screenStateReceiverRegistered) return
        runCatching {
            unregisterReceiver(screenStateReceiver)
        }.onFailure {
            Log.w(TAG, "Failed to unregister screen-state receiver", it)
        }
        screenStateReceiverRegistered = false
    }

    private fun ensureScope() {
        if (!scope.isActive) {
            scope = createScope()
        }
    }

    private fun ensureRemoteBinding(forceRebind: Boolean = false) {
        if (!serviceRunning) return
        if (!forceRebind && transformerRegistered && connection?.remoteService != null) {
            return
        }

        val previousConnection = connection
        connection = null
        if (previousConnection != null) {
            runCatching { unbindService(previousConnection) }
        }

        val sessionId = ++connectionSessionId
        connection = bindFcitxRemoteService(
            BuildConfig.MAIN_APPLICATION_ID,
            onDisconnect = {
                if (sessionId != connectionSessionId) return@bindFcitxRemoteService
                Log.d(TAG, "Disconnected from Fcitx")
                transformerRegistered = false
                scope.launch {
                    delay(1000)
                    if (serviceRunning) {
                        ensureRemoteBinding(forceRebind = true)
                    }
                }
            },
            onConnected = { service ->
                if (sessionId != connectionSessionId || !serviceRunning) {
                    return@bindFcitxRemoteService
                }
                Log.d(TAG, "Connected to Fcitx")
                runCatching {
                    service.registerClipboardEntryTransformer(transformer)
                }.onSuccess {
                    transformerRegistered = true
                    Log.d(TAG, "Clipboard transformer registered")
                }.onFailure { error ->
                    transformerRegistered = false
                    Log.e(TAG, "Failed to register transformer; pull sync will continue", error)
                }
            }
        )
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }.onFailure {
            Log.w(TAG, "Failed to register network callback", it)
        }
    }

    private fun unregisterNetworkCallbackIfNeeded() {
        if (!networkCallbackRegistered) return
        runCatching {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        }.onFailure {
            Log.w(TAG, "Failed to unregister network callback", it)
        }
        networkCallbackRegistered = false
    }

    private fun handleActionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE_SYNC -> {
                prefs.edit().putBoolean(PREF_QUICK_SYNC, false).apply()
                QuickSyncTileService.requestTileRefresh(this)
            }

            ACTION_RECONNECT_SYNC -> {
                scheduleReconnect("notification-reconnect", immediate = true)
            }

            ACTION_START_SYNC,
            null -> {
                if (intent?.getBooleanExtra(EXTRA_FORCE_ENABLE_SYNC, false) == true) {
                    prefs.edit().putBoolean(PREF_QUICK_SYNC, true).apply()
                    QuickSyncTileService.requestTileRefresh(this)
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String, immediate: Boolean = false) {
        if (!prefs.getBoolean(PREF_QUICK_SYNC, true)) return
        networkReconnectJob?.cancel()
        networkReconnectJob = scope.launch {
            if (!immediate) {
                delay(NETWORK_RECONNECT_DEBOUNCE_MS)
            }
            Log.d(TAG, "[Reconnect] Restarting sync runtime: $reason")
            startPeriodicSync()
        }
    }

    private fun updateForegroundState() {
        if (shouldRunInForeground()) {
            startForegroundCompat()
        } else {
            stopForegroundState()
        }
    }

    private fun shouldRunInForeground(): Boolean {
        return prefs.getBoolean(PREF_QUICK_SYNC, true) &&
                prefs.getBoolean(PREF_BACKGROUND_KEEP_ALIVE, true)
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.keep_alive_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.keep_alive_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundActive = true
    }

    private fun stopForegroundState() {
        if (!foregroundActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        foregroundActive = false
    }

    private fun buildForegroundNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(getString(R.string.keep_alive_notification_title))
        .setContentText(buildForegroundNotificationText())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, PluginSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_popup_sync,
            getString(R.string.keep_alive_notification_reconnect),
            PendingIntent.getService(
                this,
                2,
                Intent(this, MainService::class.java).apply { action = ACTION_RECONNECT_SYNC },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            getString(R.string.keep_alive_notification_pause),
            PendingIntent.getService(
                this,
                3,
                Intent(this, MainService::class.java).apply { action = ACTION_PAUSE_SYNC },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun buildForegroundNotificationText(): String {
        val backendLabel = when (currentBackend()) {
            ServerBackend.SYNCCLIPBOARD -> getString(R.string.server_profile_syncclipboard)
            ServerBackend.ONECLIP -> getString(R.string.server_profile_oneclip)
            ServerBackend.CLIPCASCADE -> getString(R.string.server_profile_clipcascade)
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBatteryOptimization = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        } else {
            true
        }
        return if (ignoringBatteryOptimization) {
            getString(R.string.keep_alive_notification_text, backendLabel)
        } else {
            getString(R.string.keep_alive_notification_text_battery, backendLabel)
        }
    }

    private fun noteRemoteSyncSuccess() {
        lastSuccessfulRemoteSyncAt = SystemClock.elapsedRealtime()
        markBackendActivity()
    }

    private fun markBackendActivity() {
        lastBackendActivityAt = SystemClock.elapsedRealtime()
    }

    private fun rememberAcceptedRemotePayload(payloadFingerprint: String) {
        lastRemoteContent = payloadFingerprint
        lastLocalContent = payloadFingerprint
        lastUploadedContent = payloadFingerprint
        persistLastSyncedContent(payloadFingerprint)
        noteRemoteSyncSuccess()
    }

    private fun disconnectClipCascadeClient() {
        clipCascadeClient?.close()
        clipCascadeClient = null
    }

    private fun disconnectOneClipClient() {
        oneClipClient?.close()
        oneClipClient = null
    }

    private fun ensureSelfStarted() {
        if (selfStarted) return
        runCatching {
            startSyncService(this, "self-start")
            selfStarted = true
            Log.d(TAG, "MainService promoted to started service")
        }.onFailure {
            Log.w(TAG, "Failed to self-start MainService; background sync may depend on plugin binding", it)
        }
    }

    private fun resetRemoteCache() {
        activeEndpointIdentity = null
        lastRemoteRevision = null
        lastRemoteContent = null
    }

    private fun resetFailureState() {
        // Reserved for future failure-state extensions.
    }

    private fun currentBackend(): ServerBackend {
        return ServerBackend.fromProfileType(
            prefs.getString(SERVER_PROFILE_TYPE_KEY, null)
        )
    }

    private fun isScreenInteractive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    private fun isPowerSaveEnabled(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager.isPowerSaveMode
    }

    private fun currentRuntimeMode(): RuntimeMode {
        return when {
            isPowerSaveEnabled() -> RuntimeMode.POWER_SAVE
            !isScreenInteractive() && !prefs.getBoolean(PREF_BACKGROUND_KEEP_ALIVE, true) -> RuntimeMode.AGGRESSIVE
            !isScreenInteractive() -> RuntimeMode.SCREEN_OFF
            else -> RuntimeMode.NORMAL
        }
    }

    private fun currentPollingIntervalSeconds(baseIntervalSeconds: Long): Long {
        return when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> baseIntervalSeconds
            RuntimeMode.SCREEN_OFF -> maxOf(baseIntervalSeconds, SCREEN_OFF_POLL_INTERVAL_SECONDS)
            RuntimeMode.POWER_SAVE -> maxOf(baseIntervalSeconds, POWER_SAVE_POLL_INTERVAL_SECONDS)
            RuntimeMode.AGGRESSIVE -> maxOf(baseIntervalSeconds, AGGRESSIVE_POLL_INTERVAL_SECONDS)
        }
    }

    private fun currentHealthCheckDelayMs(): Long {
        return when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> EVENT_BACKEND_HEALTH_CHECK_MS
            RuntimeMode.SCREEN_OFF -> SCREEN_OFF_HEALTH_CHECK_MS
            RuntimeMode.POWER_SAVE -> POWER_SAVE_HEALTH_CHECK_MS
            RuntimeMode.AGGRESSIVE -> AGGRESSIVE_HEALTH_CHECK_MS
        }
    }

    private fun currentFallbackPullDelayMs(): Long {
        return when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> EVENT_BACKEND_FALLBACK_PULL_MS
            RuntimeMode.SCREEN_OFF -> SCREEN_OFF_FALLBACK_PULL_MS
            RuntimeMode.POWER_SAVE -> POWER_SAVE_FALLBACK_PULL_MS
            RuntimeMode.AGGRESSIVE -> AGGRESSIVE_FALLBACK_PULL_MS
        }
    }

    private fun currentStaleReconnectDelayMs(): Long {
        return when (currentRuntimeMode()) {
            RuntimeMode.NORMAL -> EVENT_BACKEND_STALE_RECONNECT_MS
            RuntimeMode.SCREEN_OFF -> SCREEN_OFF_STALE_RECONNECT_MS
            RuntimeMode.POWER_SAVE -> POWER_SAVE_STALE_RECONNECT_MS
            RuntimeMode.AGGRESSIVE -> AGGRESSIVE_STALE_RECONNECT_MS
        }
    }

    private fun currentReceiveFilter(): ReceiveFilter {
        val state = SyncFilterPrefs.loadState(prefs)
        if (!state.hasActiveRule) {
            return ReceiveFilter(
                blockedExtensions = emptySet(),
                minFileSizeBytes = null,
                maxFileSizeBytes = null,
                minTextChars = null,
                maxTextChars = null
            )
        }
        return ReceiveFilter(
            blockedExtensions = state.blockedExtensions,
            minFileSizeBytes = null,
            maxFileSizeBytes = state.maxFileSizeBytes,
            minTextChars = state.minTextChars,
            maxTextChars = state.maxTextChars
        ).normalized()
    }

    private fun shouldAcceptIncomingMetadata(data: ClipboardData): Boolean {
        return if (isBinaryClipboardData(data)) {
            shouldAcceptIncomingBinary(
                fileName = inferIncomingFileName(data),
                sizeBytes = data.size.takeIf { it > 0 }
            )
        } else {
            shouldAcceptIncomingText(data.text)
        }
    }

    private fun shouldAcceptIncomingClipboard(data: ClipboardData): Boolean {
        return if (isBinaryClipboardData(data)) {
            shouldAcceptIncomingBinary(
                fileName = inferIncomingFileName(data),
                sizeBytes = data.size.takeIf { it > 0 }
            )
        } else {
            shouldAcceptIncomingText(data.text)
        }
    }

    private fun shouldAcceptIncomingText(text: String): Boolean {
        val filter = currentReceiveFilter()
        val length = text.codePointCount(0, text.length)
        filter.minTextChars?.let { minChars ->
            if (length < minChars) {
                return false
            }
        }
        filter.maxTextChars?.let { maxChars ->
            if (length > maxChars) {
                return false
            }
        }
        return true
    }

    private fun shouldAcceptIncomingBinary(fileName: String?, sizeBytes: Long?): Boolean {
        val filter = currentReceiveFilter()
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (extension.isNotEmpty() && extension in filter.blockedExtensions) {
            return false
        }
        sizeBytes?.takeIf { it > 0 }?.let { actualSize ->
            filter.minFileSizeBytes?.let { minSize ->
                if (actualSize < minSize) {
                    return false
                }
            }
            filter.maxFileSizeBytes?.let { maxSize ->
                if (actualSize > maxSize) {
                    return false
                }
            }
        }
        return true
    }

    private fun isBinaryClipboardData(data: ClipboardData): Boolean {
        return !data.type.equals("text", ignoreCase = true) ||
            data.hasData ||
            data.hasImage ||
            data.dataName.isNotBlank()
    }

    private fun inferIncomingFileName(data: ClipboardData): String? {
        if (data.dataName.isNotBlank()) {
            return data.dataName
        }
        if (data.type.equals("text", ignoreCase = true)) {
            return null
        }
        return data.text
            .substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.isNotBlank() }
    }

    private suspend fun handleConnectivityFailure(
        endpoint: ServerEndpoint,
        cause: Exception
    ): Boolean {
        Log.e(TAG, "[Pull] Error checking remote clipboard for ${endpoint.profileKey}@${endpoint.address}", cause)

        if (verifyEndpointRecovered(endpoint)) {
            Log.d(TAG, "[Pull] Endpoint recovered during connectivity retry: ${endpoint.address}")
            resetFailureState()
            return true
        }

        if (switchToReachableEndpoint(endpoint)) {
            Log.w(TAG, "[Pull] Switched active endpoint away from ${endpoint.address}")
            resetRemoteCache()
            resetFailureState()
            return true
        }

        Log.e(TAG, "[Pull] All configured endpoints are unreachable, disabling quick sync")
        prefs.edit().putBoolean(PREF_QUICK_SYNC, false).apply()
        QuickSyncTileService.requestTileRefresh(this)
        stopPeriodicSync()
        resetFailureState()
        return true
    }

    private suspend fun verifyEndpointRecovered(endpoint: ServerEndpoint): Boolean {
        val username = currentUsernameForProfile(endpoint.profileKey)
        val password = currentPasswordForProfile(endpoint.profileKey)

        CONNECTIVITY_RETRY_DELAYS_MS.forEach { delayMs ->
            delay(delayMs)
            val result = SyncClient.testConnection(
                serverUrl = endpoint.address,
                username = username,
                pass = password,
                backend = endpoint.backend
            )
            if (result.isSuccess) {
                return true
            }
        }
        return false
    }

    private suspend fun switchToReachableEndpoint(failedEndpoint: ServerEndpoint): Boolean {
        val alternatives = endpointCandidates()
            .filter { it.identity != failedEndpoint.identity }

        for (candidate in alternatives) {
            val username = currentUsernameForProfile(candidate.profileKey)
            val password = currentPasswordForProfile(candidate.profileKey)
            val result = SyncClient.testConnection(
                serverUrl = candidate.address,
                username = username,
                pass = password,
                backend = candidate.backend
            )
            if (result.isSuccess) {
                prefs.edit()
                    .putString(SERVER_PROFILE_TYPE_KEY, candidate.profileKey)
                    .putString(SERVER_ADDRESS_KEY, candidate.address)
                    .putString(PREF_USERNAME, username)
                    .putString(PREF_PASSWORD, password)
                    .apply()
                return true
            }
        }
        return false
    }

    private fun currentEndpoint(): ServerEndpoint {
        val profileKey = prefs.getString(SERVER_PROFILE_TYPE_KEY, PROFILE_SYNC_CLIPBOARD)
            ?.takeIf { it.isNotBlank() }
            ?: PROFILE_SYNC_CLIPBOARD
        val address = prefs.getString(SERVER_ADDRESS_KEY, null)
            ?.trim()
            .orEmpty()
            .ifBlank { storedAddressForProfile(profileKey) }
        return ServerEndpoint(
            profileKey = profileKey,
            address = address,
            backend = ServerBackend.fromProfileType(profileKey)
        )
    }

    private fun endpointCandidates(): List<ServerEndpoint> {
        val current = currentEndpoint()
        val orderedProfiles = buildList {
            add(current.profileKey)
            add(PROFILE_SYNC_CLIPBOARD)
            add(PROFILE_ONE_CLIP)
            add(PROFILE_CLIP_CASCADE)
            add(PROFILE_CUSTOM)
        }.distinct()

        return orderedProfiles.mapNotNull { profileKey ->
            val address = if (profileKey == current.profileKey) {
                current.address
            } else {
                storedAddressForProfile(profileKey)
            }.trim()

            if (address.isBlank()) {
                null
            } else {
                ServerEndpoint(
                    profileKey = profileKey,
                    address = address,
                    backend = ServerBackend.fromProfileType(profileKey)
                )
            }
        }.distinctBy { it.identity }
    }

    private fun storedAddressForProfile(profileKey: String): String {
        val key = when (profileKey) {
            PROFILE_SYNC_CLIPBOARD -> SERVER_ADDRESS_SYNC_CLIPBOARD_KEY
            PROFILE_ONE_CLIP -> SERVER_ADDRESS_ONE_CLIP_KEY
            PROFILE_CLIP_CASCADE -> SERVER_ADDRESS_CLIP_CASCADE_KEY
            PROFILE_CUSTOM -> SERVER_ADDRESS_CUSTOM_KEY
            else -> null
        }
        val stored = key?.let { prefs.getString(it, null) }?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        return when (profileKey) {
            PROFILE_SYNC_CLIPBOARD -> DEFAULT_SYNC_CLIPBOARD_URL
            PROFILE_ONE_CLIP -> DEFAULT_ONE_CLIP_URL
            PROFILE_CLIP_CASCADE -> DEFAULT_CLIP_CASCADE_URL
            else -> ""
        }
    }

    private fun currentUsernameForProfile(profileKey: String): String {
        val key = when (profileKey) {
            PROFILE_SYNC_CLIPBOARD -> "username_syncclipboard"
            PROFILE_ONE_CLIP -> "username_oneclip"
            PROFILE_CLIP_CASCADE -> "username_clipcascade"
            PROFILE_CUSTOM -> "username_custom"
            else -> null
        }
        val stored = key?.let { prefs.getString(it, null) }.orEmpty()
        return if (stored.isNotBlank() || profileKey == PROFILE_ONE_CLIP) {
            stored
        } else if (profileKey == PROFILE_CLIP_CASCADE || profileKey == PROFILE_SYNC_CLIPBOARD || profileKey == PROFILE_CUSTOM) {
            "admin"
        } else {
            ""
        }
    }

    private fun currentPasswordForProfile(profileKey: String): String {
        val key = when (profileKey) {
            PROFILE_SYNC_CLIPBOARD -> "password_syncclipboard"
            PROFILE_ONE_CLIP -> "password_oneclip"
            PROFILE_CLIP_CASCADE -> "password_clipcascade"
            PROFILE_CUSTOM -> "password_custom"
            else -> null
        }
        val stored = key?.let { prefs.getString(it, null) }.orEmpty()
        return if (stored.isNotBlank() || profileKey == PROFILE_ONE_CLIP) {
            stored
        } else when (profileKey) {
            PROFILE_CLIP_CASCADE -> "admin123"
            PROFILE_ONE_CLIP -> ""
            else -> "123456"
        }
    }

    private data class ServerEndpoint(
        val profileKey: String,
        val address: String,
        val backend: ServerBackend
    ) {
        val identity: String
            get() = "$profileKey|${address.trim()}"
    }

    @Serializable
    private data class PendingUploadEntry(
        val content: String,
        val enqueuedAt: Long = System.currentTimeMillis()
    )

    private fun loadPersistentSyncState() {
        pendingUploads.clear()
        prefs.getString(PREF_PENDING_UPLOADS, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { serialized ->
                runCatching {
                    stateJson.decodeFromString<List<PendingUploadEntry>>(serialized)
                }.onSuccess { restored ->
                    pendingUploads += restored
                }.onFailure { error ->
                    Log.w(TAG, "[State] Failed to restore pending uploads", error)
                }
            }

        storedRemoteRevisions.clear()
        prefs.getString(PREF_REMOTE_REVISIONS, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { serialized ->
                runCatching {
                    stateJson.decodeFromString<Map<String, String>>(serialized)
                }.onSuccess { restored ->
                    storedRemoteRevisions.putAll(restored)
                }.onFailure { error ->
                    Log.w(TAG, "[State] Failed to restore remote revisions", error)
                }
            }
    }

    private fun persistPendingUploadsLocked() {
        prefs.edit()
            .putString(PREF_PENDING_UPLOADS, stateJson.encodeToString(pendingUploads))
            .apply()
    }

    private fun persistRemoteRevisions() {
        prefs.edit()
            .putString(PREF_REMOTE_REVISIONS, stateJson.encodeToString(storedRemoteRevisions))
            .apply()
    }

    private fun persistLastSyncedContent(content: String) {
        prefs.edit()
            .putString(PREF_LAST_SYNCED_CONTENT, content)
            .apply()
    }

    private suspend fun enqueuePendingUpload(content: String): Boolean {
        return pendingUploadMutex.withLock {
            if (content.isEmpty()) {
                return@withLock false
            }
            pendingUploads.removeAll { it.content == content }
            pendingUploads += PendingUploadEntry(content = content)
            val overflow = pendingUploads.size - MAX_PENDING_UPLOADS
            if (overflow > 0) {
                repeat(overflow) {
                    pendingUploads.removeAt(0)
                }
            }
            persistPendingUploadsLocked()
            true
        }
    }

    private suspend fun acknowledgePendingUploads(content: String) {
        if (content.isBlank()) return
        pendingUploadMutex.withLock {
            val matchedIndex = pendingUploads.indexOfLast { it.content == content }
            if (matchedIndex < 0) {
                return@withLock
            }
            repeat(matchedIndex + 1) {
                pendingUploads.removeAt(0)
            }
            persistPendingUploadsLocked()
        }
    }

    private fun ensureEndpointState(endpoint: ServerEndpoint) {
        if (activeEndpointIdentity == endpoint.identity) {
            return
        }
        activeEndpointIdentity = endpoint.identity
        lastRemoteRevision = storedRemoteRevisions[endpoint.identity]
        lastRemoteContent = null
    }

    private fun persistRemoteRevision(endpoint: ServerEndpoint, revision: String) {
        storedRemoteRevisions[endpoint.identity] = revision
        persistRemoteRevisions()
    }

    private suspend fun flushPendingUploads(reason: String) {
        if (!prefs.getBoolean(PREF_QUICK_SYNC, true)) {
            return
        }
        pendingUploadDrainMutex.withLock {
            while (true) {
                val next = pendingUploadMutex.withLock {
                    pendingUploads.firstOrNull()
                } ?: return

                try {
                    pushClipboardToCloud(next.content)
                    pendingUploadMutex.withLock {
                        if (pendingUploads.firstOrNull() == next) {
                            pendingUploads.removeAt(0)
                        } else {
                            pendingUploads.removeAll { it.content == next.content }
                        }
                        persistPendingUploadsLocked()
                    }
                    Log.d(TAG, "[Push] Uploaded queued clipboard item from $reason")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[Push] Failed to upload queued clipboard item from $reason", e)
                    return
                }
            }
        }
    }

    private suspend fun pushClipboardToCloud(text: String) {
        val endpoint = currentEndpoint()
        val url = endpoint.address
        val user = currentUsernameForProfile(endpoint.profileKey)
        val pass = currentPasswordForProfile(endpoint.profileKey)
        val backend = endpoint.backend

        if (url.isBlank()) {
            throw IOException("Server address is blank")
        }

        if (backend == ServerBackend.CLIPCASCADE) {
            val activeClient = clipCascadeClient
            if (activeClient?.isConnected() == true) {
                val outgoing = SyncClient.buildClipCascadeClipboardData(this@MainService, text)
                activeClient.sendClipboard(
                    payload = outgoing.payload,
                    type = outgoing.type,
                    filename = outgoing.filename
                )
            } else {
                SyncClient.putClipboard(this@MainService, url, user, pass, backend, text)
            }
        } else {
            SyncClient.putClipboard(this@MainService, url, user, pass, backend, text)
        }
        lastUploadedContent = text
        persistLastSyncedContent(text)
        markBackendActivity()
    }

    private enum class RuntimeMode {
        NORMAL,
        SCREEN_OFF,
        POWER_SAVE,
        AGGRESSIVE
    }

    private data class ReceiveFilter(
        val blockedExtensions: Set<String>,
        val minFileSizeBytes: Long?,
        val maxFileSizeBytes: Long?,
        val minTextChars: Int?,
        val maxTextChars: Int?
    ) {
        fun normalized(): ReceiveFilter {
            val normalizedFileBounds = normalizeBounds(minFileSizeBytes, maxFileSizeBytes)
            val normalizedTextBounds = normalizeBounds(minTextChars, maxTextChars)
            return copy(
                minFileSizeBytes = normalizedFileBounds.first,
                maxFileSizeBytes = normalizedFileBounds.second,
                minTextChars = normalizedTextBounds.first,
                maxTextChars = normalizedTextBounds.second
            )
        }

        private fun normalizeBounds(minValue: Long?, maxValue: Long?): Pair<Long?, Long?> {
            return when {
                minValue == null -> null to maxValue
                maxValue == null -> minValue to null
                minValue <= maxValue -> minValue to maxValue
                else -> maxValue to minValue
            }
        }

        private fun normalizeBounds(minValue: Int?, maxValue: Int?): Pair<Int?, Int?> {
            return when {
                minValue == null -> null to maxValue
                maxValue == null -> minValue to null
                minValue <= maxValue -> minValue to maxValue
                else -> maxValue to minValue
            }
        }
    }

    private fun createScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
