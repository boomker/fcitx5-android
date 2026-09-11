/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber

fun ContentResolver.queryFileName(uri: Uri): String? = runCatching {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }
}.onFailure { error ->
    Timber.w(error, "Failed to query file name for uri: %s", uri)
}.getOrNull()

fun resolveClipboardUriFileName(context: Context, uri: Uri): String? = when (uri.scheme) {
    "content" -> context.contentResolver.queryFileName(uri) ?: uri.lastPathSegment ?: uri.path
    else -> uri.lastPathSegment ?: uri.path
}?.let { Uri.decode(it).substringAfterLast('/').substringAfterLast(':') }
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
