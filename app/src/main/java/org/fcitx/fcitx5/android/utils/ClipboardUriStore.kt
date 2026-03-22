/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.util.Locale

data class ClipboardSharedContent(
    val uri: Uri,
    val mimeType: String
)

object ClipboardUriStore {
    private const val CACHE_DIR = "clipboard_shared"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    fun String.toClipboardUriOrNull(): Uri? {
        return if (startsWith("content://") || startsWith("file://")) Uri.parse(this) else null
    }

    fun stageForCommit(context: Context, raw: String): ClipboardSharedContent? {
        val uri = raw.toClipboardUriOrNull() ?: return null
        return stageForCommit(context, uri)
    }

    fun stageForCommit(context: Context, uri: Uri): ClipboardSharedContent? {
        val mimeType = resolveMimeType(context, uri)
        if (uri.authority == context.packageName + FILE_PROVIDER_SUFFIX) {
            return ClipboardSharedContent(uri, mimeType)
        }
        val displayName = resolveDisplayName(context, uri, mimeType)
        val targetFile = File(cacheRoot(context), displayName)
        return try {
            openInputStream(context, uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            pruneCache(targetFile)
            ClipboardSharedContent(
                uri = FileProvider.getUriForFile(
                    context,
                    context.packageName + FILE_PROVIDER_SUFFIX,
                    targetFile
                ),
                mimeType = mimeType
            )
        } catch (error: Exception) {
            Timber.w(error, "Failed to stage clipboard URI for commit: $uri")
            null
        }
    }

    fun normalizeClipboardText(context: Context, raw: String): String {
        return stageForCommit(context, raw)?.uri?.toString() ?: raw
    }

    private fun cacheRoot(context: Context): File {
        return File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    private fun pruneCache(latest: File) {
        val files = latest.parentFile?.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(32).forEach { it.delete() }
    }

    private fun openInputStream(context: Context, uri: Uri) = when (uri.scheme?.lowercase(Locale.ROOT)) {
        ContentResolver.SCHEME_CONTENT -> context.contentResolver.openInputStream(uri)
        ContentResolver.SCHEME_FILE -> uri.path?.let { FileInputStream(it) }
        else -> null
    }

    private fun resolveDisplayName(context: Context, uri: Uri, mimeType: String): String {
        val sourceName = when (uri.scheme?.lowercase(Locale.ROOT)) {
            ContentResolver.SCHEME_CONTENT -> context.contentResolver.queryFileName(uri)
            ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).name }
            else -> null
        }
        val baseName = sourceName
            ?.substringAfterLast('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "clipboard-${System.currentTimeMillis()}"
        val sanitized = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (sanitized.contains('.')) return sanitized
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
        return if (extension != null) "$sanitized.$extension" else sanitized
    }

    private fun resolveMimeType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri)
            ?: uri.path
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: DEFAULT_MIME_TYPE
    }
}
