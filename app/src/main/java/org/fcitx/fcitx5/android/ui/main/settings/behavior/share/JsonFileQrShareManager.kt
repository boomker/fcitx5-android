/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.share

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.fcitx.fcitx5.android.BuildConfig
import java.io.File
import java.io.FileOutputStream

object JsonFileQrShareManager {
    fun encodeSavedJsonFileToLongImage(file: File): Pair<Bitmap, LayoutQrTransferCodec.ChunkBundle> {
        val rawJson = file.readText()
        val bundle = LayoutQrTransferCodec.encodeJsonToChunks(rawJson)
        val contents = bundle.chunks.map { it.encode() }
        val labels = bundle.chunks.map { "Chunk ${it.index}/${it.total} · ${bundle.transferId}" }
        return LayoutQrBitmapUtil.composeLongImageStreaming(contents, labels) to bundle
    }

    fun saveLongImageToShareCache(context: Context, bitmap: Bitmap, prefix: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.share.fileprovider",
            file
        )
    }

    fun decodeQrChunksFromImage(context: Context, uri: Uri): List<String> {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        return try {
            LayoutQrBitmapUtil.decodeAllQrFromImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun parseQrPayload(raw: String): LayoutQrTransferCodec.Chunk? {
        val payload = LayoutQrTransferCodec.parseQrImageText(raw) ?: return null
        return runCatching { LayoutQrTransferCodec.parseChunk(payload) }.getOrNull()
    }

    fun decodeChunksToJson(chunks: List<String>): String = LayoutQrTransferCodec.decodeChunksToJson(chunks)
}

class QrChunkCollector {
    private val chunks = linkedMapOf<Int, String>()
    private var transferId: String? = null
    private var total: Int = 0

    data class Progress(
        val current: Int,
        val total: Int,
        val completedJson: String?,
        val duplicate: Boolean
    )

    fun clear() {
        chunks.clear()
        transferId = null
        total = 0
    }

    fun addAndMaybeAssemble(rawText: String): Progress? {
        val payload = LayoutQrTransferCodec.parseQrImageText(rawText) ?: return null
        val chunk = runCatching { LayoutQrTransferCodec.parseChunk(payload) }.getOrNull() ?: return null
        if (transferId == null || transferId != chunk.transferId) {
            clear()
            transferId = chunk.transferId
            total = chunk.total
        }
        val duplicate = chunks.containsKey(chunk.index)
        chunks[chunk.index] = payload
        if (chunks.size == total) {
            val json = LayoutQrTransferCodec.decodeChunksToJson(chunks.values.toList())
            clear()
            return Progress(total, total, json, duplicate)
        }
        return Progress(chunks.size, total, null, duplicate)
    }
}
