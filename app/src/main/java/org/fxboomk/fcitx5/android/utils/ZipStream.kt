/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.utils

import android.os.Build
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * @return all extracted files in zip file (including subdirectories)
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    var entry = nextEntry
    val canonicalDest = destDir.canonicalPath
    while (entry != null) {
        if (!entry.isDirectory) {
            val file = File(destDir, entry.name)
            if (!file.canonicalPath.startsWith(canonicalDest)) throw SecurityException()
            file.parentFile?.mkdirs()
            copyTo(file.outputStream())
        } else {
            val dir = File(destDir, entry.name)
            dir.mkdirs()
        }
        entry = nextEntry
    }
    // Return all files recursively (not just top-level)
    return destDir.walkTopDown().filter { it.isFile }.toList()
}

fun zipInputStream(src: InputStream, charset: Charset? = null): ZipInputStream {
    return if (charset != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        ZipInputStream(src, charset)
    } else {
        ZipInputStream(src)
    }
}
