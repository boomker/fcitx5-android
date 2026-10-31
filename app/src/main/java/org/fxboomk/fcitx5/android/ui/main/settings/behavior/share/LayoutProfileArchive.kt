/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.share

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 键盘布局配置的文件级导入 / 导出格式。
 *
 * ZIP 内每个布局配置对应一个 `<配置名>.json` 条目，不写入额外元数据，
 * 便于用户直接解压查看或用普通 JSON 文件继续导入。
 */
object LayoutProfileArchive {

    private const val MAX_ENTRIES = 200
    private const val MAX_ENTRY_BYTES = 4 * 1024 * 1024

    data class Entry(val name: String, val json: String)

    /** 将 [files]（配置名 to JSON 文件）打包为 ZIP。 */
    fun exportZip(files: List<Pair<String, File>>, dest: OutputStream) {
        ZipOutputStream(dest.buffered()).use { zip ->
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry("$name.json"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * 读取 ZIP 中的所有 JSON 布局条目。
     *
     * 忽略目录、`__MACOSX` 资源分叉和隐藏文件；条目名取文件名部分（含 .json），
     * 因此用户将配置文件放在子目录中打包也可以导入。
     */
    fun readZip(src: InputStream): List<Entry> {
        val entries = mutableListOf<Entry>()
        ZipInputStream(src.buffered()).use { zip ->
            while (true) {
                val rawEntry = zip.nextEntry ?: break
                val path = rawEntry.name.replace('\\', '/')
                val fileName = path.substringAfterLast('/')
                if (!rawEntry.isDirectory &&
                    !path.startsWith("__MACOSX/") &&
                    !fileName.startsWith("._") &&
                    fileName.endsWith(".json", ignoreCase = true) &&
                    fileName.length > ".json".length
                ) {
                    check(entries.size < MAX_ENTRIES) { "Too many layout files in ZIP" }
                    entries += Entry(
                        name = fileName,
                        json = zip.readBytesLimited(MAX_ENTRY_BYTES).toString(Charsets.UTF_8)
                    )
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "Layout JSON in ZIP is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
