package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object SyncClient {
    enum class ServerBackend {
        SYNCCLIPBOARD,
        ONECLIP;

        companion object {
            fun fromProfileType(profileType: String?): ServerBackend {
                return if (profileType.equals("oneclip", ignoreCase = true)) {
                    ONECLIP
                } else {
                    SYNCCLIPBOARD
                }
            }
        }
    }

    data class FetchResult(
        val data: ClipboardData?,
        val revision: String?
    )

    private const val TAG = "FcitxClipboardSync"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val OCTET_STREAM_TYPE = "application/octet-stream".toMediaType()

    fun fetchClipboard(
        context: Context,
        serverUrl: String,
        username: String,
        pass: String,
        backend: ServerBackend,
        lastRevision: String? = null,
        downloadDirUri: Uri? = null
    ): FetchResult {
        return when (backend) {
            ServerBackend.SYNCCLIPBOARD -> fetchSyncClipboard(
                context = context,
                serverUrl = serverUrl,
                username = username,
                pass = pass,
                lastRevision = lastRevision,
                downloadDirUri = downloadDirUri
            )

            ServerBackend.ONECLIP -> fetchOneClipClipboard(
                context = context,
                serverUrl = serverUrl,
                lastRevision = lastRevision,
                downloadDirUri = downloadDirUri
            )
        }
    }

    fun putClipboard(
        context: Context,
        serverUrl: String,
        username: String,
        pass: String,
        backend: ServerBackend,
        content: String
    ) {
        when (backend) {
            ServerBackend.SYNCCLIPBOARD -> putSyncClipboard(
                context = context,
                serverUrl = serverUrl,
                username = username,
                pass = pass,
                content = content
            )

            ServerBackend.ONECLIP -> putOneClip(
                context = context,
                serverUrl = serverUrl,
                content = content
            )
        }
    }

    fun testConnection(
        serverUrl: String,
        username: String,
        pass: String,
        backend: ServerBackend
    ): Result<String> {
        return try {
            val request = when (backend) {
                ServerBackend.SYNCCLIPBOARD -> {
                    val (_, jsonUrl) = getBaseAndJsonUrl(serverUrl)
                    Log.d(TAG, "[Test] Testing SyncClipboard connection to $jsonUrl")
                    Request.Builder()
                        .url(jsonUrl)
                        .header("Authorization", Credentials.basic(username, pass))
                        .get()
                        .build()
                }

                ServerBackend.ONECLIP -> {
                    val historyUrl = oneClipUrl(serverUrl, "/api/history")
                    Log.d(TAG, "[Test] Testing OneClip connection to $historyUrl")
                    Request.Builder()
                        .url(historyUrl)
                        .get()
                        .build()
                }
            }

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("Connection Successful: ${response.code}")
                } else {
                    Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Test] Error", e)
            Result.failure(e)
        }
    }

    private fun fetchSyncClipboard(
        context: Context,
        serverUrl: String,
        username: String,
        pass: String,
        lastRevision: String?,
        downloadDirUri: Uri?
    ): FetchResult {
        val previousEtag = lastRevision
            ?.takeIf { it.startsWith(REVISION_ETAG_PREFIX) }
            ?.removePrefix(REVISION_ETAG_PREFIX)

        val (partialData, newEtag) = fetchClipboardJson(serverUrl, username, pass, previousEtag)
        if (partialData == null) {
            val revision = newEtag?.let { REVISION_ETAG_PREFIX + it } ?: lastRevision
            return FetchResult(null, revision)
        }

        val data = downloadDetails(context, serverUrl, username, pass, partialData, downloadDirUri)
        val revision = newEtag?.let { REVISION_ETAG_PREFIX + it } ?: buildRevisionToken(data)
        return FetchResult(data, revision)
    }

    private fun fetchOneClipClipboard(
        context: Context,
        serverUrl: String,
        lastRevision: String?,
        downloadDirUri: Uri?
    ): FetchResult {
        val historyUrl = oneClipUrl(serverUrl, "/api/history")
        val historyRequest = Request.Builder()
            .url(historyUrl)
            .get()
            .build()

        client.newCall(historyRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to load OneClip history: ${response.code} ${response.message}")
            }

            val bodyString = response.body?.string().orEmpty()
            val history = json.decodeFromString<OneClipHistoryResponse>(bodyString)
            val latest = history.items.firstOrNull() ?: return FetchResult(null, lastRevision)
            val revision = REVISION_ONECLIP_PREFIX + latest.id

            if (revision == lastRevision) {
                return FetchResult(null, revision)
            }

            val detailRequest = Request.Builder()
                .url(oneClipUrl(serverUrl, "/api/item/${latest.id}"))
                .get()
                .build()

            client.newCall(detailRequest).execute().use { detailResponse ->
                if (!detailResponse.isSuccessful) {
                    throw IOException("Failed to load OneClip item: ${detailResponse.code} ${detailResponse.message}")
                }

                val detailBody = detailResponse.body?.string().orEmpty()
                val item = json.decodeFromString<ClipboardData>(detailBody)
                val normalizedType = item.type.lowercase(Locale.ROOT)

                if (normalizedType == "image" || latest.hasImage) {
                    val imageData = downloadOneClipImage(
                        context = context,
                        serverUrl = serverUrl,
                        itemId = latest.id,
                        timestamp = latest.timestamp,
                        downloadDirUri = downloadDirUri
                    )
                    return FetchResult(imageData, revision)
                }

                return FetchResult(
                    item.copy(
                        id = latest.id,
                        type = "Text",
                        hash = latest.id
                    ),
                    revision
                )
            }
        }
    }

    private fun putSyncClipboard(
        context: Context,
        serverUrl: String,
        username: String,
        pass: String,
        content: String
    ) {
        val (baseUrl, jsonUrl) = getBaseAndJsonUrl(serverUrl)
        val credential = Credentials.basic(username, pass)
        val uri = content.toClipboardUriOrNull()

        try {
            if (uri != null) {
                val fileName = getFileName(context, uri) ?: "unknown_file"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

                if (bytes != null) {
                    Log.d(TAG, "[Push] Uploading file $fileName (${bytes.size} bytes)")
                    val fileUrl = "${baseUrl}file/$fileName"
                    val fileBody = bytes.toRequestBody(OCTET_STREAM_TYPE)
                    val fileReq = Request.Builder()
                        .url(fileUrl)
                        .header("Authorization", credential)
                        .put(fileBody)
                        .build()

                    client.newCall(fileReq).execute().use {
                        if (!it.isSuccessful) throw IOException("File upload failed: ${it.code}")
                    }

                    val hash = HashUtils.calculateFileHash(fileName, bytes)
                    val data = ClipboardData(
                        type = "File",
                        text = fileName,
                        hash = hash,
                        hasData = true,
                        dataName = fileName,
                        size = bytes.size.toLong()
                    )

                    uploadSyncClipboardJson(jsonUrl, credential, data)
                }
            } else {
                Log.d(TAG, "[Push] Uploading text (${content.length} chars)")
                val hash = HashUtils.sha256(content)
                val data = ClipboardData(
                    type = "Text",
                    text = content,
                    hash = hash,
                    hasData = false,
                    size = content.toByteArray().size.toLong()
                )
                uploadSyncClipboardJson(jsonUrl, credential, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Push] Error", e)
            throw e
        }
    }

    private fun putOneClip(
        context: Context,
        serverUrl: String,
        content: String
    ) {
        val uri = content.toClipboardUriOrNull()
        try {
            if (uri != null && isImageUri(context, uri)) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Unable to read image from clipboard URI")
                uploadOneClipImage(serverUrl, bytes)
            } else {
                uploadOneClipText(serverUrl, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Push] OneClip upload failed", e)
            throw e
        }
    }

    @Throws(IOException::class)
    private fun fetchClipboardJson(
        serverUrl: String,
        username: String,
        pass: String,
        etag: String? = null
    ): Pair<ClipboardData?, String?> {
        val (_, jsonUrl) = getBaseAndJsonUrl(serverUrl)
        val credential = Credentials.basic(username, pass)
        val requestBuilder = Request.Builder()
            .url(jsonUrl)
            .header("Authorization", credential)
            .get()

        if (!etag.isNullOrEmpty()) {
            requestBuilder.header("If-None-Match", etag)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 304) {
                return null to (response.header("ETag") ?: etag)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "[Pull] Failed: ${response.code} ${response.message}")
                throw IOException("Unexpected code $response")
            }

            val newEtag = response.header("ETag")
            var bodyString = response.body?.string() ?: ""
            if (bodyString.startsWith("\uFEFF")) {
                bodyString = bodyString.substring(1)
            }
            return json.decodeFromString<ClipboardData>(bodyString) to newEtag
        }
    }

    @Throws(IOException::class)
    fun downloadDetails(
        context: Context,
        serverUrl: String,
        username: String,
        pass: String,
        data: ClipboardData,
        downloadDirUri: Uri? = null
    ): ClipboardData {
        if (!data.hasData || data.dataName.isEmpty()) {
            return data
        }

        val (baseUrl, _) = getBaseAndJsonUrl(serverUrl)
        val credential = Credentials.basic(username, pass)
        val fileUrl = "${baseUrl}file/${data.dataName}"

        val fileReq = Request.Builder()
            .url(fileUrl)
            .header("Authorization", credential)
            .get()
            .build()

        var newData = data

        client.newCall(fileReq).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download file: $response")
            val bytes = response.body?.bytes() ?: ByteArray(0)

            if (data.hash.isNotEmpty()) {
                val calculatedHash = if (data.type.equals("Text", ignoreCase = true)) {
                    HashUtils.sha256(bytes)
                } else {
                    HashUtils.calculateFileHash(data.dataName, bytes)
                }

                if (!calculatedHash.equals(data.hash, ignoreCase = true)) {
                    Log.e(TAG, "[Pull] Hash mismatch! Expected: ${data.hash}, Got: $calculatedHash")
                }
            }

            if (data.type.equals("Text", ignoreCase = true)) {
                newData = data.copy(text = String(bytes, Charsets.UTF_8))
            } else if (downloadDirUri != null) {
                val savedUri = saveFile(
                    context = context,
                    dirUri = downloadDirUri,
                    fileName = data.dataName,
                    bytes = bytes
                )
                if (savedUri != null) {
                    newData = data.copy(text = savedUri.toString())
                }
            } else {
                Log.w(TAG, "[Pull] No download directory set, skipping file save")
            }
        }

        return newData
    }

    private fun uploadSyncClipboardJson(url: String, credential: String, data: ClipboardData) {
        val jsonString = json.encodeToString(data)
        val body = jsonString.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("SyncClipboard JSON upload failed: ${response.code} ${response.message}")
            }
        }
    }

    private fun uploadOneClipText(serverUrl: String, text: String) {
        val requestBody = json.encodeToString(OneClipUploadTextRequest(text))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(oneClipUrl(serverUrl, "/api/upload"))
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OneClip text upload failed: ${response.code} ${response.message}")
            }

            val resultBody = response.body?.string().orEmpty()
            val result = runCatching { json.decodeFromString<OneClipUploadResponse>(resultBody) }.getOrNull()
            if (result != null && !result.status.equals("success", ignoreCase = true)) {
                throw IOException(result.message.ifBlank { "OneClip text upload failed" })
            }
        }
    }

    private fun uploadOneClipImage(serverUrl: String, bytes: ByteArray) {
        val base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val requestBody = json.encodeToString(OneClipUploadImageRequest(base64Image))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(oneClipUrl(serverUrl, "/api/upload-image"))
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OneClip image upload failed: ${response.code} ${response.message}")
            }

            val resultBody = response.body?.string().orEmpty()
            val result = runCatching { json.decodeFromString<OneClipUploadResponse>(resultBody) }.getOrNull()
            if (result != null && !result.status.equals("success", ignoreCase = true)) {
                throw IOException(result.message.ifBlank { "OneClip image upload failed" })
            }
        }
    }

    private fun downloadOneClipImage(
        context: Context,
        serverUrl: String,
        itemId: String,
        timestamp: Double,
        downloadDirUri: Uri?
    ): ClipboardData {
        if (downloadDirUri == null) {
            Log.w(TAG, "[Pull] OneClip image received but no download directory is configured")
            return ClipboardData(id = itemId, type = "Image", hash = itemId)
        }

        val request = Request.Builder()
            .url(oneClipUrl(serverUrl, "/api/image/$itemId"))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download OneClip image: ${response.code} ${response.message}")
            }

            val mimeType = response.body?.contentType()?.toString().orEmpty().ifBlank { "image/png" }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val fileName = buildOneClipImageFileName(itemId, timestamp, mimeType)
            val savedUri = saveFile(
                context = context,
                dirUri = downloadDirUri,
                fileName = fileName,
                bytes = bytes,
                mimeType = mimeType
            )

            return ClipboardData(
                id = itemId,
                type = "Image",
                text = savedUri?.toString().orEmpty(),
                hash = itemId,
                hasData = true,
                dataName = fileName,
                size = bytes.size.toLong()
            )
        }
    }

    private fun saveFile(
        context: Context,
        dirUri: Uri,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "*/*"
    ): Uri? {
        return try {
            val dir = DocumentFile.fromTreeUri(context, dirUri)
            if (dir != null && dir.isDirectory) {
                dir.findFile(fileName)?.delete()
                val newFile = dir.createFile(mimeType, fileName)
                if (newFile != null) {
                    context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(bytes) }
                    newFile.uri
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to save file", e)
            null
        }
    }

    private fun buildOneClipImageFileName(itemId: String, timestamp: Double, mimeType: String): String {
        val normalizedSubtype = mimeType
            .substringAfter('/', "png")
            .substringBefore(';')
            .substringBefore('+')
            .lowercase(Locale.ROOT)
            .let {
                when (it) {
                    "jpeg" -> "jpg"
                    "svg+xml" -> "svg"
                    else -> it.ifBlank { "png" }
                }
            }
        val unixSeconds = timestamp.toLong().takeIf { it > 0 } ?: System.currentTimeMillis() / 1000
        return "OneClip-$unixSeconds-$itemId.$normalizedSubtype"
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun isImageUri(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)
            ?: getFileName(context, uri)?.substringAfterLast('.', "")?.let { extension ->
                when (extension.lowercase(Locale.ROOT)) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> null
                }
            }

        return mimeType?.startsWith("image/") == true
    }

    private fun String.toClipboardUriOrNull(): Uri? {
        return if (startsWith("content://") || startsWith("file://")) Uri.parse(this) else null
    }

    private fun buildRevisionToken(data: ClipboardData): String? {
        return when {
            data.id.isNotBlank() -> REVISION_ONECLIP_PREFIX + data.id
            data.hash.isNotBlank() -> REVISION_HASH_PREFIX + data.hash
            data.text.isNotBlank() -> REVISION_TEXT_PREFIX + HashUtils.sha256(data.text)
            else -> null
        }
    }

    private fun resolveUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun getBaseAndJsonUrl(serverUrl: String): Pair<String, String> {
        var url = resolveUrl(serverUrl)
        if (url.endsWith("SyncClipboard.json", ignoreCase = true)) {
            val base = url.substringBeforeLast("SyncClipboard.json")
            return base to url
        }
        if (!url.endsWith("/")) url += "/"
        return url to "${url}SyncClipboard.json"
    }

    private fun oneClipUrl(serverUrl: String, path: String): String {
        val base = resolveUrl(serverUrl).trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return base + normalizedPath
    }

    private const val REVISION_ETAG_PREFIX = "etag:"
    private const val REVISION_HASH_PREFIX = "hash:"
    private const val REVISION_TEXT_PREFIX = "text:"
    private const val REVISION_ONECLIP_PREFIX = "oneclip:"
}
