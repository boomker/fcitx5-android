package org.fcitx.fcitx5.android.plugin.clipboard_sync.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.util.concurrent.TimeUnit

object SyncClient {
    private const val TAG = "FcitxClipboardSync"
    // encodeDefaults = true ensures we send all fields including new ones
    // coerceInputValues = true converts nulls to default values (fixes "Expected string literal but 'null' literal was found")
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

    @Throws(IOException::class)
    fun fetchClipboardJson(
        serverUrl: String, 
        username: String, 
        pass: String,
        etag: String? = null
    ): Pair<ClipboardData?, String?> {
        val (_, jsonUrl) = getBaseAndJsonUrl(serverUrl)
        // Log.d(TAG, "[Pull] Fetching from $jsonUrl")
        
        val credential = Credentials.basic(username, pass)
        val requestBuilder = Request.Builder()
            .url(jsonUrl)
            .header("Authorization", credential)
            .get()

        if (!etag.isNullOrEmpty()) {
            requestBuilder.header("If-None-Match", etag)
        }

        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (response.code == 304) {
                // Not Modified
                return null to (response.header("ETag") ?: etag)
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "[Pull] Failed: ${response.code} ${response.message}")
                throw IOException("Unexpected code $response")
            }
            
            val newETag = response.header("ETag")
            var bodyString = response.body?.string() ?: ""
            // Handle BOM
            if (bodyString.startsWith("\uFEFF")) {
                bodyString = bodyString.substring(1)
            }
            // Log.d(TAG, "[Pull] Response body: $bodyString")
            try {
                return json.decodeFromString<ClipboardData>(bodyString) to newETag
            } catch (e: Exception) {
                Log.e(TAG, "[Pull] JSON Parse Error", e)
                throw IOException("Failed to parse JSON: ${e.message}", e)
            }
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
        if (!data.hasData || data.dataName.isNullOrEmpty()) {
            return data
        }

        val (baseUrl, _) = getBaseAndJsonUrl(serverUrl)
        val credential = Credentials.basic(username, pass)
        val fileUrl = "${baseUrl}file/${data.dataName}"
        Log.d(TAG, "[Pull] Downloading extra data from $fileUrl")
        
        val fileReq = Request.Builder()
            .url(fileUrl)
            .header("Authorization", credential)
            .get()
            .build()
        
        var newData = data

        client.newCall(fileReq).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download file: $response")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            
            // Verify Hash
            if (data.hash.isNotEmpty()) {
                val calculatedHash = if (data.type == "Text") {
                    HashUtils.sha256(bytes)
                } else {
                    HashUtils.calculateFileHash(data.dataName, bytes)
                }
                
                if (!calculatedHash.equals(data.hash, ignoreCase = true)) {
                    Log.e(TAG, "[Pull] Hash mismatch! Expected: ${data.hash}, Got: $calculatedHash")
                }
            }

            if (data.type == "Text") {
                newData = data.copy(text = String(bytes, Charsets.UTF_8))
            } else {
                // Save file
                if (downloadDirUri != null) {
                    val savedUri = saveFile(context, downloadDirUri, data.dataName, bytes)
                    if (savedUri != null) {
                        newData = data.copy(text = savedUri.toString())
                        Log.d(TAG, "[Pull] Updated ClipboardData text to URI: ${newData.text}")
                    }
                } else {
                    Log.w(TAG, "[Pull] No download directory set, skipping file save")
                }
            }
            Unit
        }
        return newData
    }

    // Deprecated: Kept for compatibility but redirects to new logic
    @Throws(IOException::class)
    fun getClipboard(
        context: Context,
        serverUrl: String, 
        username: String, 
        pass: String,
        downloadDirUri: Uri? = null
    ): ClipboardData {
        val (data, _) = fetchClipboardJson(serverUrl, username, pass)
        if (data == null) throw IOException("Unexpected 304 in legacy getClipboard")
        return downloadDetails(context, serverUrl, username, pass, data, downloadDirUri)
    }

    private fun saveFile(context: Context, dirUri: Uri, fileName: String, bytes: ByteArray): Uri? {
        try {
            val dir = DocumentFile.fromTreeUri(context, dirUri)
            if (dir != null && dir.isDirectory) {
                val existing = dir.findFile(fileName)
                existing?.delete()
                
                val newFile = dir.createFile("*/*", fileName)
                if (newFile != null) {
                    context.contentResolver.openOutputStream(newFile.uri)?.use { 
                        it.write(bytes)
                    }
                    Log.d(TAG, "[Pull] Saved file to $fileName")
                    return newFile.uri
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Pull] Failed to save file", e)
        }
        return null
    }

    fun putClipboard(
        context: Context,
        serverUrl: String, 
        username: String, 
        pass: String, 
        content: String
    ) {
        val (baseUrl, jsonUrl) = getBaseAndJsonUrl(serverUrl)
        val credential = Credentials.basic(username, pass)
        
        // Simple URI check
        val uri = if (content.startsWith("content://") || content.startsWith("file://")) Uri.parse(content) else null
        
        try {
            if (uri != null) {
                // File Upload
                val fileName = getFileName(context, uri) ?: "unknown_file"
                // Read bytes - careful with large files, but for now load to memory as per typical clipboard size
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                
                if (bytes != null) {
                    Log.d(TAG, "[Push] Uploading file $fileName (${bytes.size} bytes)")
                    
                    // 1. Upload File
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
                    
                    // 2. Upload JSON
                    val hash = HashUtils.calculateFileHash(fileName, bytes)
                    val data = ClipboardData(
                        type = "File", // TODO: Detect Image?
                        text = fileName, // Text field usually stores preview or filename for files
                        hash = hash,
                        hasData = true,
                        dataName = fileName,
                        size = bytes.size.toLong()
                    )
                    
                    uploadJson(jsonUrl, credential, data)
                }
            } else {
                // Text Upload
                Log.d(TAG, "[Push] Uploading text (${content.length} chars)")
                val hash = HashUtils.sha256(content)
                val data = ClipboardData(
                    type = "Text",
                    text = content,
                    hash = hash,
                    hasData = false,
                    size = content.toByteArray().size.toLong()
                )
                uploadJson(jsonUrl, credential, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Push] Error", e)
        }
    }

    private fun uploadJson(url: String, credential: String, data: ClipboardData) {
        val jsonString = json.encodeToString(data)
        val body = jsonString.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", credential)
            .put(body)
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "[Push] Failed: ${response.code} ${response.message}")
            } else {
                Log.d(TAG, "[Push] Success")
            }
        }
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

    fun testConnection(serverUrl: String, username: String, pass: String): Result<String> {
        val (baseUrl, jsonUrl) = getBaseAndJsonUrl(serverUrl)
        Log.d(TAG, "[Test] Testing connection to $jsonUrl")
        return try {
            val credential = Credentials.basic(username, pass)
            val request = Request.Builder()
                .url(jsonUrl)
                .header("Authorization", credential)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "[Test] Success")
                    Result.success("Connection Successful: ${response.code}")
                } else {
                    Log.e(TAG, "[Test] Failed: ${response.code}")
                    Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Test] Error", e)
            Result.failure(e)
        }
    }
}
