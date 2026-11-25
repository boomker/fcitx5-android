/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.input.font

import timber.log.Timber

import android.graphics.Typeface
import android.os.Process
import android.os.SystemClock
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import java.io.File

class DefaultFontProvider : FontProviderApi {
    private data class FontConfig(
        val paths: Map<String, List<String>>,
        val fontsDir: File
    )

    @Volatile
    private var cachedFontConfig: FontConfig? = null
    private var fontConfigLoaded = false
    private val cachedFontTypefaceMap = mutableMapOf<String, Typeface?>()
    private val cachedTypefaceByPaths = mutableMapOf<List<String>, Typeface?>()
    @Volatile
    private var cachedFontSizeMap: MutableMap<String, Float>? = null
    @Volatile
    private var isLoading = false

    @Synchronized
    override fun clearCache() {
        cachedFontConfig = null
        fontConfigLoaded = false
        cachedFontTypefaceMap.clear()
        cachedTypefaceByPaths.clear()
        cachedFontSizeMap = null
        isLoading = false
    }

    /**
     * Preload fonts asynchronously to avoid blocking UI thread.
     * Call this when keyboard is about to show.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        synchronized(this) {
            if (isLoading) return
            isLoading = true
        }

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val keys = synchronized(this) {
                    loadFontConfigLocked()?.paths?.keys
                        ?.filterNot { it.endsWith("_size") }
                        .orEmpty()
                }
                keys.forEach { key ->
                    synchronized(this) {
                        loadFontConfigLocked()?.let { loadTypefaceLocked(key, it) }
                    }
                }
                val fonts = synchronized(this) {
                    cachedFontTypefaceMap.toMutableMap()
                }
                Timber.tag("FcitxColdStart").i("font preload keys=${keys.size} duration=${SystemClock.elapsedRealtime() - startedAt}ms")
                onComplete?.invoke(fonts)
            } finally {
                synchronized(this) {
                    isLoading = false
                }
            }
        }, "FcitxFontPreload").start()
    }

    @get:Synchronized
    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() = synchronized(this) {
            val config = loadFontConfigLocked() ?: return@synchronized mutableMapOf()
            config.paths.keys
                .filterNot { it.endsWith("_size") }
                .forEach { loadTypefaceLocked(it, config) }
            cachedFontTypefaceMap.toMutableMap()
        }

    override fun resolveTypeface(key: String, current: Typeface?): Typeface = synchronized(this) {
        val config = loadFontConfigLocked()
        val resolved = config?.let { loadTypefaceLocked(key, it) }
            ?: config?.let { loadTypefaceLocked("font", it) }
        resolved ?: current ?: Typeface.DEFAULT
    }

    private fun loadFontConfigLocked(): FontConfig? {
        if (fontConfigLoaded) return cachedFontConfig
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull()
        val fontsDir = snapshot?.file?.parentFile
        val config = if (snapshot != null && fontsDir != null) FontConfig(snapshot.value, fontsDir) else null
        cachedFontConfig = config
        fontConfigLoaded = true
        return config
    }

    private fun loadTypefaceLocked(key: String, config: FontConfig): Typeface? {
        if (cachedFontTypefaceMap.containsKey(key)) return cachedFontTypefaceMap[key]

        val validPaths = config.paths[key].orEmpty()
            .map { it.trim() }
            .map { File(config.fontsDir, it) }
            .filter(File::exists)
            .map(File::getAbsolutePath)
        val typeface = if (cachedTypefaceByPaths.containsKey(validPaths)) {
            cachedTypefaceByPaths[validPaths]
        } else {
            val startedAt = SystemClock.elapsedRealtime()
            val loaded = runCatching {
                when {
                    validPaths.isEmpty() -> null
                    validPaths.size == 1 || android.os.Build.VERSION.SDK_INT < 29 ->
                        Typeface.createFromFile(validPaths.first())
                    else -> buildCustomFallbackTypeface(validPaths)
                }
            }.getOrNull()
            cachedTypefaceByPaths[validPaths] = loaded
            if (validPaths.isNotEmpty()) {
                Timber.tag("FcitxColdStart").i("font key=$key files=${validPaths.size} loaded=${loaded != null} " +
                        "duration=${SystemClock.elapsedRealtime() - startedAt}ms")
            }
            loaded
        }
        cachedFontTypefaceMap[key] = typeface
        return typeface
    }

    @androidx.annotation.RequiresApi(29)
    private fun buildCustomFallbackTypeface(validPaths: List<String>): Typeface {
        val firstFamily = android.graphics.fonts.FontFamily.Builder(
            android.graphics.fonts.Font.Builder(File(validPaths[0])).build()
        ).build()
        val builder = android.graphics.Typeface.CustomFallbackBuilder(firstFamily)
        for (i in 1 until validPaths.size) {
            val family = android.graphics.fonts.FontFamily.Builder(
                android.graphics.fonts.Font.Builder(File(validPaths[i])).build()
            ).build()
            builder.addCustomFallback(family)
        }
        return builder.build()
    }

    @get:Synchronized
    override val fontSizeMap: MutableMap<String, Float>
        get() {
            val recheck = cachedFontSizeMap
            if (recheck != null) return recheck

            val snapshot = ConfigProviders
                .readFontsetPathMapSnapshot()
                .getOrNull() ?: run {
                cachedFontSizeMap = null
                return mutableMapOf()
            }
            cachedFontSizeMap = runCatching {
                snapshot.value
                    .filterKeys { it.endsWith("_size") }
                    .mapValues { (key, values) ->
                        runCatching {
                            val minSize = if (key == "cand_comment_font_size") 1f else 8f
                            values.firstOrNull()?.trim()
                                ?.toFloatOrNull()?.coerceIn(minSize, 72f)
                        }.getOrNull()
                    }
                    .filterValues { it != null }
                    .mapValues { it.value!! }
                    .toMutableMap()
            }.getOrElse { mutableMapOf() }
            return cachedFontSizeMap ?: mutableMapOf()
        }
}
