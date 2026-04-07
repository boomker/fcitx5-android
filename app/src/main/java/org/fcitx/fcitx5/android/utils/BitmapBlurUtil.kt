/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.graphics.Bitmap
import android.os.Build
import androidx.collection.LruCache
import timber.log.Timber
import org.fcitx.fcitx5.android.utils.appContext

// RenderScript imports - use at runtime for API <= 30 when available
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur

/**
 * Utility for applying Gaussian blur to bitmaps.
 * Prefer RenderScript (API <= 30) for performance; fall back to software Stack Blur.
 */
object BitmapBlurUtil {

    // Cache blurred bitmaps to avoid re-blurring same image
    private val cache = LruCache<String, Bitmap>(5)

    /**
     * Blur a bitmap with given radius.
     *
     * @param source Source bitmap to blur
     * @param radius Blur radius (1-25)
     * @return Blurred bitmap
     */
    fun blur(source: Bitmap, radius: Float): Bitmap {
        if (radius <= 0f) return source

        val intRadius = radius.toInt().coerceIn(1, 25)
        val cacheKey = "blur_${source.width}x${source.height}_$intRadius"
        cache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        // Try RenderScript on supported devices (API <= 30)
        if (Build.VERSION.SDK_INT <= 30) {
            try {
                val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                val rs = RenderScript.create(appContext.applicationContext)
                val inputAlloc = Allocation.createFromBitmap(rs, source)
                val outputAlloc = Allocation.createFromBitmap(rs, output)
                val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                script.setRadius(intRadius.toFloat())
                script.setInput(inputAlloc)
                script.forEach(outputAlloc)
                outputAlloc.copyTo(output)
                rs.destroy()
                cache.put(cacheKey, output)
                return output
            } catch (e: Throwable) {
                Timber.w(e, "RenderScript blur failed, falling back to software blur")
                // fall through to software path
            }
        }

        // Software fallback: downscale for performance, then stack blur
        return try {
            val scale = when {
                intRadius >= 15 -> 4
                intRadius >= 8 -> 2
                else -> 1
            }

            val workingBitmap = if (scale > 1) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width / scale).coerceAtLeast(1),
                    (source.height / scale).coerceAtLeast(1),
                    true
                )
            } else {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
            }

            // ensure mutable bitmap
            val mutable = if (workingBitmap.isMutable) workingBitmap else workingBitmap.copy(Bitmap.Config.ARGB_8888, true)

            stackBlur(mutable, intRadius)

            val result = if (scale > 1) {
                Bitmap.createScaledBitmap(mutable, source.width, source.height, true)
            } else {
                mutable
            }

            cache.put(cacheKey, result)
            result
        } catch (e: Exception) {
            Timber.w(e, "Failed to blur bitmap, returning original")
            source
        }
    }

    /**
     * Stack Blur algorithm - produces similar results to Gaussian blur but much faster.
     * Implementation corrected for channel ordering and edge clamping.
     */
    private fun stackBlur(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)

        val vmin = IntArray(maxOf(w, h))
        val vmax = IntArray(maxOf(w, h))

        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum)
        for (i in dv.indices) {
            dv[i] = i / divSum
        }

        val stack = Array(div) { IntArray(3) }

        var yi = 0
        var yw = 0

        // Horizontal pass
        for (y in 0 until h) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var rinSum = 0
            var ginSum = 0
            var binSum = 0
            var routSum = 0
            var goutSum = 0
            var boutSum = 0

            for (i in -radius..radius) {
                val x = (if (i + 0 < 0) 0 else if (i + 0 > wm) wm else i + 0)
                val p = pix[yw + x]
                val sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                val rbs = radius + 1 - kotlin.math.abs(i)
                rSum += sir[0] * rbs
                gSum += sir[1] * rbs
                bSum += sir[2] * rbs
                if (i > 0) {
                    rinSum += sir[0]
                    ginSum += sir[1]
                    binSum += sir[2]
                } else {
                    routSum += sir[0]
                    goutSum += sir[1]
                    boutSum += sir[2]
                }
            }

            var stackPointer = radius
            for (x in 0 until w) {
                val idx = yw + x
                r[idx] = dv[rSum]
                g[idx] = dv[gSum]
                b[idx] = dv[bSum]

                rSum -= routSum
                gSum -= goutSum
                bSum -= boutSum

                val stackStart = stackPointer - radius + div
                val sir = stack[stackStart % div]

                routSum -= sir[0]
                goutSum -= sir[1]
                boutSum -= sir[2]

                val nextX = if (x + radius + 1 > wm) wm else x + radius + 1
                val p = pix[yw + nextX]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinSum += sir[0]
                ginSum += sir[1]
                binSum += sir[2]

                rSum += rinSum
                gSum += ginSum
                bSum += binSum

                stackPointer = (stackPointer + 1) % div
                val sir2 = stack[stackPointer % div]
                routSum += sir2[0]
                goutSum += sir2[1]
                boutSum += sir2[2]

                rinSum -= sir2[0]
                ginSum -= sir2[1]
                binSum -= sir2[2]
            }
            yw += w
        }

        // Vertical pass
        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var rinSum = 0
            var ginSum = 0
            var binSum = 0
            var routSum = 0
            var goutSum = 0
            var boutSum = 0

            var yp = -radius * w
            for (i in -radius..radius) {
                val y = if (i < 0) 0 else if (i > hm) hm else i
                val idx = (y * w) + x
                val sir = stack[i + radius]
                sir[0] = r[idx]
                sir[1] = g[idx]
                sir[2] = b[idx]

                val rbs = radius + 1 - kotlin.math.abs(i)
                rSum += r[idx] * rbs
                gSum += g[idx] * rbs
                bSum += b[idx] * rbs

                if (i > 0) {
                    rinSum += sir[0]
                    ginSum += sir[1]
                    binSum += sir[2]
                } else {
                    routSum += sir[0]
                    goutSum += sir[1]
                    boutSum += sir[2]
                }
            }

            var stackPointer = radius
            var y = 0
            while (y < h) {
                val pixIndex = y * w + x
                val origPixel = pix[pixIndex]
                val newPixel = (origPixel and -0x1000000) or
                        (dv[rSum] shl 16) or
                        (dv[gSum] shl 8) or
                        dv[bSum]
                pix[pixIndex] = newPixel

                rSum -= routSum
                gSum -= goutSum
                bSum -= boutSum

                val stackStart = stackPointer - radius + div
                val sir = stack[stackStart % div]

                routSum -= sir[0]
                goutSum -= sir[1]
                boutSum -= sir[2]

                val nextY = if (y + radius + 1 > hm) hm else y + radius + 1
                val idx = nextY * w + x

                sir[0] = r[idx]
                sir[1] = g[idx]
                sir[2] = b[idx]

                rinSum += sir[0]
                ginSum += sir[1]
                binSum += sir[2]

                rSum += rinSum
                gSum += ginSum
                bSum += binSum

                stackPointer = (stackPointer + 1) % div
                val sir2 = stack[stackPointer]
                routSum += sir2[0]
                goutSum += sir2[1]
                boutSum += sir2[2]

                rinSum -= sir2[0]
                ginSum -= sir2[1]
                binSum -= sir2[2]

                y++
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }

    /**
     * Clear the blur cache to free memory
     */
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * Check if a color is semi-transparent (has alpha component)
     */
    fun isSemiTransparent(color: Int): Boolean {
        val alpha = android.graphics.Color.alpha(color)
        return alpha in 1..254
    }
}
