/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber

fun ContentResolver.queryFileName(uri: Uri): String? = runCatching {
    query(uri, null, null, null, null)?.use {
        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index == -1 || !it.moveToFirst()) {
            null
        } else {
            it.getString(index)
        }
    }
}.onFailure { error ->
    Timber.w(error, "Failed to query file name for uri: %s", uri)
}.getOrNull()
