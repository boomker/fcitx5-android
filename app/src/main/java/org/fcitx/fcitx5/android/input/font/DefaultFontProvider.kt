/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import java.io.File

class DefaultFontProvider : FontProviderApi {
    private var cachedFontTypefaceMap: MutableMap<String, Typeface?>? = null
    private var lastModified = 0L

    @Synchronized
    override fun clearCache() {
        cachedFontTypefaceMap = null
        lastModified = 0L
    }

    @get:Synchronized
    override val fontTypefaceMap: MutableMap<String, Typeface?>
        get() {
            val snapshot = org.fcitx.fcitx5.android.input.config.ConfigProviders
                .readFontsetPathMapSnapshot()
                .getOrNull() ?: run {
                cachedFontTypefaceMap = null
                return mutableMapOf()
            }
            val fontset = snapshot ?: run {
                cachedFontTypefaceMap = null
                return mutableMapOf()
            }
            val fontsDir = fontset.file.parentFile ?: run {
                cachedFontTypefaceMap = null
                return mutableMapOf()
            }
            if (cachedFontTypefaceMap == null || lastModified != fontset.lastModified) {
                cachedFontTypefaceMap = runCatching {
                    fontset.value
                        .asSequence()
                        .associateTo(mutableMapOf()) { (key, paths) ->
                            key to runCatching {
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
}
