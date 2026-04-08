/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object LayoutQrBitmapUtil {
    private const val QR_SIZE = 768
    private const val PAGE_PADDING = 24
    private const val TEXT_SIZE = 22f
    private const val TEXT_GAP = 12

    private data class ScaledPreview(val bitmap: Bitmap, val heightWithPadding: Int)

    private fun buildScaledPreviewOrNull(previewBitmap: Bitmap?, targetWidth: Int): ScaledPreview? {
        val source = previewBitmap ?: return null
        if (source.isRecycled || source.width <= 0 || source.height <= 0 || targetWidth <= 0) return null
        val scale = targetWidth.toFloat() / source.width.toFloat()
        val scaledHeight = maxOf(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, scaledHeight, true)
        return ScaledPreview(scaled, scaledHeight + PAGE_PADDING)
    }

    fun createQrBitmap(content: String): Bitmap {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, com.google.zxing.BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        val pixels = IntArray(QR_SIZE * QR_SIZE)
        for (y in 0 until QR_SIZE) {
            for (x in 0 until QR_SIZE) {
                pixels[y * QR_SIZE + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
    }

    fun composeLongImage(qrBitmaps: List<Bitmap>, labels: List<String>): Bitmap {
        require(qrBitmaps.size == labels.size) { "Bitmap count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2
        val totalHeight = pageHeight * qrBitmaps.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)
        qrBitmaps.forEachIndexed { index, bitmap ->
            val top = index * pageHeight
            canvas.drawBitmap(bitmap, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
        }
        return merged
    }

    fun composeLongImageStreaming(contents: List<String>, labels: List<String>): Bitmap {
        require(contents.size == labels.size) { "Content count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2
        val totalHeight = pageHeight * contents.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)
        contents.forEachIndexed { index, content ->
            val top = index * pageHeight
            val qr = createQrBitmap(content)
            canvas.drawBitmap(qr, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
            qr.recycle()
        }
        return merged
    }

    /**
     * Compose a long image with QR codes and a preview image at the top.
     * @param qrBitmaps List of QR code bitmaps
     * @param labels List of labels for each QR code
     * @param previewBitmap Optional preview bitmap to place at the top
     * @return Composed bitmap with preview (if provided) followed by QR codes
     */
    fun composeLongImageWithPreview(
        qrBitmaps: List<Bitmap>,
        labels: List<String>,
        previewBitmap: Bitmap?
    ): Bitmap {
        require(qrBitmaps.size == labels.size) { "Bitmap count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2

        val scaledPreview = buildScaledPreviewOrNull(previewBitmap, width)
        val previewSectionHeight = scaledPreview?.heightWithPadding ?: 0
        val totalHeight = previewSectionHeight + pageHeight * qrBitmaps.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)

        var currentTop = 0

        // Draw preview at the top if provided
        scaledPreview?.let {
            canvas.drawBitmap(it.bitmap, 0f, currentTop.toFloat(), null)
            it.bitmap.recycle()
            currentTop += it.heightWithPadding
        }

        // Draw QR codes below preview
        qrBitmaps.forEachIndexed { index, bitmap ->
            val top = currentTop + index * pageHeight
            canvas.drawBitmap(bitmap, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
        }

        return merged
    }

    /**
     * Compose a long image with QR codes (generated from contents) and a preview image at the top.
     * @param contents List of QR code contents
     * @param labels List of labels for each QR code
     * @param previewBitmap Optional preview bitmap to place at the top
     * @return Composed bitmap with preview (if provided) followed by QR codes
     */
    fun composeLongImageStreamingWithPreview(
        contents: List<String>,
        labels: List<String>,
        previewBitmap: Bitmap?
    ): Bitmap {
        require(contents.size == labels.size) { "Content count must equal label count" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val width = QR_SIZE + PAGE_PADDING * 2

        val scaledPreview = buildScaledPreviewOrNull(previewBitmap, width)
        val previewSectionHeight = scaledPreview?.heightWithPadding ?: 0
        val totalHeight = previewSectionHeight + pageHeight * contents.size
        val merged = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(merged)
        canvas.drawColor(Color.WHITE)

        var currentTop = 0

        // Draw preview at the top if provided
        scaledPreview?.let {
            canvas.drawBitmap(it.bitmap, 0f, currentTop.toFloat(), null)
            it.bitmap.recycle()
            currentTop += it.heightWithPadding
        }

        // Draw QR codes below preview
        contents.forEachIndexed { index, content ->
            val top = currentTop + index * pageHeight
            val qr = createQrBitmap(content)
            canvas.drawBitmap(qr, PAGE_PADDING.toFloat(), (top + PAGE_PADDING).toFloat(), null)
            canvas.drawText(labels[index], PAGE_PADDING.toFloat(), (top + PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE), paint)
            qr.recycle()
        }

        return merged
    }

    fun decodeAllQrFromImage(bitmap: Bitmap): List<String> {
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE)
        )
        val found = linkedSetOf<String>()
        val pageHeight = PAGE_PADDING + QR_SIZE + TEXT_GAP + TEXT_SIZE.toInt() + PAGE_PADDING
        val pages = maxOf(1, bitmap.height / pageHeight)
        var i = 0
        while (i < pages) {
            val top = i * pageHeight
            val safeLeft = minOf(PAGE_PADDING, maxOf(0, bitmap.width - 1))
            val safeTop = minOf(maxOf(0, top + PAGE_PADDING), bitmap.height - 1)
            val cropWidth = minOf(QR_SIZE, bitmap.width - safeLeft)
            val cropHeight = minOf(QR_SIZE, bitmap.height - safeTop)
            if (cropWidth > 0 && cropHeight > 0) {
                val cropped = Bitmap.createBitmap(bitmap, safeLeft, safeTop, cropWidth, cropHeight)
                decodeSingle(cropped, hints)?.let { found += it }
                cropped.recycle()
            }
            i++
        }
        if (found.isNotEmpty()) return found.toList()
        decodeSingle(bitmap, hints)?.let { return listOf(it) }
        return emptyList()
    }

    private fun decodeSingle(bitmap: Bitmap, hints: Map<DecodeHintType, Any>): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        return runCatching {
            MultiFormatReader().decode(binaryBitmap, hints).text
        }.recoverCatching {
            QRCodeReader().decode(binaryBitmap, hints).text
        }.getOrNull()
    }
}
