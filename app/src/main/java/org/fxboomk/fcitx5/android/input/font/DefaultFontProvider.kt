/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.font

import android.graphics.Typeface
import java.io.File

class DefaultFontProvider : FontProviderApi {
    @Volatile
    private var cachedFontTypefaceMap: MutableMap<String, Typeface?>? = null
    @Volatile
    private var cachedFontSizeMap: MutableMap<String, Float>? = null
    @Volatile
    private var lastModified = 0L
    @Volatile
    private var isLoading = false

    @Synchronized
    override fun clearCache() {
        cachedFontTypefaceMap = null
        cachedFontSizeMap = null
        lastModified = 0L
        isLoading = false
    }

    /**
     * Preload fonts asynchronously to avoid blocking UI thread.
     * Call this when keyboard is about to show.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        if (isLoading) return  // Already loading
        isLoading = true

        Thread {
            val fonts = fontTypefaceMap
            isLoading = false
            onComplete?.invoke(fonts)
        }.start()
    }

    @get:Synchronized
    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            // Fast path: return cached map if available and up-to-date
            val cached = cachedFontTypefaceMap
            if (cached != null && !isLoading) {
                return cached
            }

            // Slow path: load fonts
            val snapshot = org.fxboomk.fcitx5.android.input.config.ConfigProviders
                .readFontsetPathMapSnapshot()
                .getOrNull() ?: run {
                cachedFontTypefaceMap = null
                return mutableMapOf()
            }
            val fontset = snapshot ?: run {
                cachedFontTypefaceMap = null
                return mutableMapOf()
            }
            val fontsDir = fontset.file?.parentFile ?: run {
                cachedFontTypefaceMap = null
                return mutableMapOf()
            }
            if (cachedFontTypefaceMap == null || lastModified != fontset.lastModified) {
                cachedFontTypefaceMap = runCatching {
                    fontset.value
                        .filterKeys { !it.endsWith("_size") }  // Exclude font size keys
                        .mapValues { (_, paths) ->
                            runCatching {
                                val fontPaths = paths.map { it.trim() }
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    var builder: android.graphics.Typeface.CustomFallbackBuilder? = null
                                    val validPaths = fontPaths.filter { File(fontsDir, it).exists() }
                                    if (validPaths.isNotEmpty()) {
                                        val firstFont = android.graphics.fonts.Font.Builder(File(fontsDir, validPaths[0])).build()
                                        val firstFamily = android.graphics.fonts.FontFamily.Builder(firstFont).build()
                                        builder = android.graphics.Typeface.CustomFallbackBuilder(firstFamily)

                                        for (i in 1 until validPaths.size) {
                                            val font = android.graphics.fonts.Font.Builder(File(fontsDir, validPaths[i])).build()
                                            val family = android.graphics.fonts.FontFamily.Builder(font).build()
                                            builder.addCustomFallback(family)
                                        }
                                        builder.build()
                                    } else {
                                        null
                                    }
                                } else {
                                    fontPaths.firstOrNull { File(fontsDir, it).exists() }
                                        ?.let { Typeface.createFromFile(File(fontsDir, it)) }
                                }
                            }.getOrNull()
                        } as MutableMap<String, Typeface?>
                }.getOrElse { mutableMapOf() }
                lastModified = fontset.lastModified
            }
            return cachedFontTypefaceMap ?: mutableMapOf()
        }

    @get:Synchronized
    override val fontSizeMap: MutableMap<String, Float>
        get() {
            // Fast path: return cached map if available and up-to-date
            val cached = cachedFontSizeMap
            if (cached != null) {
                return cached
            }

            // Slow path: parse font sizes
            val snapshot = org.fxboomk.fcitx5.android.input.config.ConfigProviders
                .readFontsetPathMapSnapshot()
                .getOrNull() ?: run {
                cachedFontSizeMap = null
                return mutableMapOf()
            }
            val fontset = snapshot ?: run {
                cachedFontSizeMap = null
                return mutableMapOf()
            }
            if (cachedFontSizeMap == null || lastModified != fontset.lastModified) {
                cachedFontSizeMap = runCatching {
                    fontset.value
                        .filterKeys { key -> key.endsWith("_size") }  // Only process font size keys
                        .mapValues { (_, values) ->
                            runCatching {
                                val sizeStr = values.firstOrNull()?.trim() ?: return@runCatching null
                                sizeStr.toFloatOrNull()?.coerceIn(8f, 72f)
                            }.getOrNull()
                        }
                        .filterValues { it != null }
                        .mapValues { it.value!! } as MutableMap<String, Float>
                }.getOrElse { mutableMapOf() }
            }
            return cachedFontSizeMap ?: mutableMapOf()
        }
}
