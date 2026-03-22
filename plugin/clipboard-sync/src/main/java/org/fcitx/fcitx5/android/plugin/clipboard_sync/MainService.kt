package org.fcitx.fcitx5.android.plugin.clipboard_sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.common.FcitxPluginService
import org.fcitx.fcitx5.android.common.ipc.FcitxRemoteConnection
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.bindFcitxRemoteService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ClipCascadeClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.ClipCascadeClipboardData
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.OneClipEventClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient
import org.fcitx.fcitx5.android.plugin.clipboard_sync.network.SyncClient.ServerBackend
import org.fcitx.fcitx5.android.plugin.clipboard_sync.ui.StoragePathUtils
import java.io.IOException
import java.util.Locale

class MainService : FcitxPluginService() {

    companion object {
        private const val TAG = "FcitxClipboardSync"
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
        private const val CONNECTIVITY_RETRY_COUNT = 3
        private const val CONNECTIVITY_RETRY_DELAY_MS = 30_000L
    }

    override val stopOnUnbind: Boolean = false

    private lateinit var connection: FcitxRemoteConnection
    private lateinit var prefs: SharedPreferences
    private var syncJob: Job? = null
    private var scope = createScope()
    private var transformerRegistered = false
    private var serviceRunning = false
    private var selfStarted = false
    private val clipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private var clipboardListenerRegistered = false
    private var prefsListenerRegistered = false
    private var clipCascadeClient: ClipCascadeClient? = null
    private var oneClipClient: OneClipEventClient? = null

    // Cache to avoid circular updates (Pull -> Local -> Push -> Loop)
    private var lastLocalContent: String? = null
    private var lastRemoteContent: String? = null
    private var lastRemoteRevision: String? = null
    private var lastUploadedContent: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleSystemClipboardChanged()
    }

    private val transformer = object : IClipboardEntryTransformer.Stub() {
        override fun getPriority(): Int = 100

        override fun transform(clipboardText: String): String {
            // This is called when user copies text locally
            if (clipboardText == lastRemoteContent) {
                // If this change matches what we just pulled, ignore it (don't push back)
                return clipboardText
            }

            if (clipboardText != lastLocalContent) {
                lastLocalContent = clipboardText
                Log.d(TAG, "[Push] Detected local change, triggering upload")
                uploadToCloud(clipboardText)
            }
            return clipboardText
        }

        override fun getDescription(): String = "SyncClipboard"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MainService onCreate")
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureScope()
        start()
        return START_STICKY
    }

    override fun start() {
        if (serviceRunning) return
        Log.d(TAG, "MainService start")
        serviceRunning = true
        ensureScope()
        ensureSelfStarted()
        registerClipboardListenerIfNeeded()
        registerPrefsListenerIfNeeded()
        startPeriodicSync()
        connection = bindFcitxRemoteService(BuildConfig.MAIN_APPLICATION_ID,
            onDisconnect = {
                Log.d(TAG, "Disconnected from Fcitx")
                transformerRegistered = false
            },
            onConnected = { service ->
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
        handleSystemClipboardChanged()
    }

    override fun stop() {
        if (!serviceRunning) return
        Log.d(TAG, "MainService stop")
        serviceRunning = false
        unregisterClipboardListenerIfNeeded()
        unregisterPrefsListenerIfNeeded()
        stopPeriodicSync()
        runCatching {
            if (transformerRegistered) {
                connection.remoteService?.unregisterClipboardEntryTransformer(transformer)
            }
        }
        transformerRegistered = false
        runCatching {
            if (::connection.isInitialized) {
                unbindService(connection)
            }
        }
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroy() {
        unregisterClipboardListenerIfNeeded()
        unregisterPrefsListenerIfNeeded()
        stopPeriodicSync()
        scope.cancel()
        serviceRunning = false
        selfStarted = false
        super.onDestroy()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "server_address" || key == "username" || key == "password" || key == "server_profile_type") {
            Log.d(TAG, "Sync credential/config changed: $key, resetting sync cache")
            resetRemoteCache()
            resetFailureState()
            startPeriodicSync()
        } else if (key == "quick_sync" || key == "sync_interval") {
            Log.d(TAG, "Preference changed: $key, restarting sync")
            startPeriodicSync()
        }
    }

    private fun uploadToCloud(text: String) {
        if (!prefs.getBoolean("quick_sync", true)) {
            Log.d(TAG, "[Push] Sync disabled, skipping upload")
            return
        }
        val url = prefs.getString("server_address", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        val backend = currentBackend()

        if (url.isBlank()) return

        scope.launch {
            try {
                lastUploadedContent = text
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
            } catch (e: Exception) {
                Log.e(TAG, "[Push] Failed to upload clipboard", e)
            }
        }
    }

    private fun startPeriodicSync() {
        stopPeriodicSync()

        val quickSync = prefs.getBoolean("quick_sync", true)
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
                    val safeInterval = prefs.getString("sync_interval", "3")?.toLongOrNull()
                        ?.coerceIn(1, 60)
                        ?: 3L

                    checkRemoteClipboard()
                    resetFailureState()

                    delay(safeInterval * 1000L)
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

                val username = prefs.getString("username", "") ?: ""
                val password = prefs.getString("password", "") ?: ""
                val client = ClipCascadeClient(
                    serverUrl = endpoint.address,
                    username = username,
                    password = password
                )
                clipCascadeClient = client

                try {
                    client.connect(::handleClipCascadeMessage)
                    resetFailureState()
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
                    resetFailureState()

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
        val url = prefs.getString("server_address", "") ?: ""
        val user = prefs.getString("username", "") ?: ""
        val pass = prefs.getString("password", "") ?: ""
        val backend = currentBackend()

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
            downloadDirUri = downloadUri
        )

        if (result.revision != null) {
            lastRemoteRevision = result.revision
        }

        val data = result.data ?: run {
            return
        }

        val remoteText = data.text
        Log.d(TAG, "[Pull] Processed data: type=${data.type}, text=$remoteText")

        if (remoteText.isNotEmpty() && remoteText != lastLocalContent && remoteText != lastRemoteContent) {
            Log.d(TAG, "[Pull] Remote content changed, updating local")
            lastRemoteContent = remoteText
            lastLocalContent = remoteText // Update local cache to prevent echo
            lastUploadedContent = remoteText

            withContext(Dispatchers.Main) {
                if (data.type == "Text") {
                    updateSystemClipboard(remoteText)
                } else {
                    updateSystemClipboardWithUri(Uri.parse(remoteText))
                }
            }
        }
    }

    private fun updateSystemClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SyncClipboard", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated (Text)")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard", e)
        }
    }

    private fun updateSystemClipboardWithUri(uri: Uri) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(contentResolver, "SyncClipboard", uri)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "[Pull] System clipboard updated with URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to update system clipboard with URI", e)
        }
    }

    private fun handleSystemClipboardChanged() {
        val content = readClipboardContent() ?: return
        if (content == lastRemoteContent || content == lastUploadedContent) {
            return
        }
        if (content != lastLocalContent) {
            lastLocalContent = content
            Log.d(TAG, "[Push] System clipboard changed, triggering upload")
            uploadToCloud(content)
        }
    }

    private fun handleClipCascadeMessage(data: ClipCascadeClipboardData) {
        val normalizedType = data.type.lowercase(Locale.ROOT)
        val payloadFingerprint = buildClipCascadePayloadFingerprint(data)
        Log.d(TAG, "[ClipCascade] Received remote payload type=$normalizedType size=${data.payload.length}")
        if (payloadFingerprint.isEmpty() ||
            payloadFingerprint == lastLocalContent ||
            payloadFingerprint == lastRemoteContent
        ) {
            return
        }

        lastRemoteContent = payloadFingerprint
        lastLocalContent = payloadFingerprint
        lastUploadedContent = payloadFingerprint

        scope.launch {
            when (normalizedType) {
                "text" -> withContext(Dispatchers.Main) {
                    updateSystemClipboard(data.payload)
                }

                "image" -> handleClipCascadeImage(data)

                "file_eager" -> handleClipCascadeFileEager(data)

                "file_stub" -> withContext(Dispatchers.Main) {
                    updateSystemClipboard(buildClipCascadeFileStubSummary(data))
                }

                else -> Log.w(TAG, "[ClipCascade] Ignoring unsupported payload type: ${data.type}")
            }
        }
    }

    private suspend fun handleClipCascadeImage(data: ClipCascadeClipboardData) {
        val bytes = runCatching { Base64.decode(data.payload, Base64.DEFAULT) }
            .getOrElse { error ->
                Log.e(TAG, "[ClipCascade] Failed to decode image payload", error)
                return
            }
        val downloadUri = resolveDownloadUri()
        val fileName = SyncClient.buildClipCascadeImageFileName(data.filename)
        val mimeType = SyncClient.guessClipCascadeImageMimeType(fileName)
        val savedUri = SyncClient.saveIncomingBytes(
            context = this,
            dirUri = downloadUri,
            fileName = fileName,
            bytes = bytes,
            mimeType = mimeType
        )
        if (savedUri != null) {
            withContext(Dispatchers.Main) {
                updateSystemClipboardWithUri(savedUri)
            }
        }
    }

    private suspend fun handleClipCascadeFileEager(data: ClipCascadeClipboardData) {
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
        val savedUri = SyncClient.saveIncomingBytes(
            context = this,
            dirUri = downloadUri,
            fileName = fileName,
            bytes = bytes
        )
        if (savedUri != null) {
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

    private fun ensureScope() {
        if (!scope.isActive) {
            scope = createScope()
        }
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
            startService(Intent(this, MainService::class.java))
            selfStarted = true
            Log.d(TAG, "MainService promoted to started service")
        }.onFailure {
            Log.w(TAG, "Failed to self-start MainService; background sync may depend on plugin binding", it)
        }
    }

    private fun resetRemoteCache() {
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
        prefs.edit().putBoolean("quick_sync", false).apply()
        stopPeriodicSync()
        resetFailureState()
        return true
    }

    private suspend fun verifyEndpointRecovered(endpoint: ServerEndpoint): Boolean {
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        repeat(CONNECTIVITY_RETRY_COUNT) { index ->
            val result = SyncClient.testConnection(
                serverUrl = endpoint.address,
                username = username,
                pass = password,
                backend = endpoint.backend
            )
            if (result.isSuccess) {
                return true
            }
            if (index < CONNECTIVITY_RETRY_COUNT - 1) {
                delay(CONNECTIVITY_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private suspend fun switchToReachableEndpoint(failedEndpoint: ServerEndpoint): Boolean {
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""

        val alternatives = endpointCandidates()
            .filter { it.identity != failedEndpoint.identity }

        for (candidate in alternatives) {
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

    private data class ServerEndpoint(
        val profileKey: String,
        val address: String,
        val backend: ServerBackend
    ) {
        val identity: String
            get() = "$profileKey|${address.trim()}"
    }

    private fun createScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
