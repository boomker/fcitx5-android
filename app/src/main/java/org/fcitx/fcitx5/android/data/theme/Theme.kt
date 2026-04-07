/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.util.LruCache
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.utils.BitmapBlurUtil
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.RectSerializer
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.appContext
import java.io.File

@Serializable
sealed class Theme : Parcelable {

    private object BackgroundBlurBitmapCache {
        private val cache = object : LruCache<String, android.graphics.Bitmap>(16 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
                value.byteCount / 1024
        }

        fun getOrPut(key: String, producer: () -> android.graphics.Bitmap): android.graphics.Bitmap {
            synchronized(cache) {
                cache.get(key)?.let { return it }
                val value = producer()
                cache.put(key, value)
                return value
            }
        }
    }

    abstract val name: String
    abstract val isDark: Boolean

    abstract val backgroundColor: Int
    abstract val barColor: Int
    abstract val keyboardColor: Int

    abstract val keyBackgroundColor: Int
    abstract val keyTextColor: Int

    //  Color of candidate text
    abstract val candidateTextColor: Int
    abstract val candidateLabelColor: Int
    abstract val candidateCommentColor: Int

    abstract val altKeyBackgroundColor: Int
    abstract val altKeyTextColor: Int

    abstract val accentKeyBackgroundColor: Int
    abstract val accentKeyTextColor: Int

    abstract val keyPressHighlightColor: Int
    abstract val keyShadowColor: Int

    abstract val popupBackgroundColor: Int
    abstract val popupTextColor: Int

    abstract val spaceBarColor: Int
    abstract val dividerColor: Int
    abstract val clipboardEntryColor: Int

    abstract val genericActiveBackgroundColor: Int
    abstract val genericActiveForegroundColor: Int

    open fun backgroundDrawable(keyBorder: Boolean = false): Drawable {
        return ColorDrawable(if (keyBorder) backgroundColor else keyboardColor)
    }

    @Serializable
    @Parcelize
    data class Custom(
        override val name: String,
        override val isDark: Boolean,
        /**
         * absolute paths of cropped and src png files
         */
        val backgroundImage: CustomBackground?,
        override val backgroundColor: Int,
        override val barColor: Int,
        override val keyboardColor: Int,
        override val keyBackgroundColor: Int,
        override val keyTextColor: Int,
        override val candidateTextColor: Int,
        override val candidateLabelColor: Int,
        override val candidateCommentColor: Int,
        override val altKeyBackgroundColor: Int,
        override val altKeyTextColor: Int,
        override val accentKeyBackgroundColor: Int,
        override val accentKeyTextColor: Int,
        override val keyPressHighlightColor: Int,
        override val keyShadowColor: Int,
        override val popupBackgroundColor: Int,
        override val popupTextColor: Int,
        override val spaceBarColor: Int,
        override val dividerColor: Int,
        override val clipboardEntryColor: Int,
        override val genericActiveBackgroundColor: Int,
        override val genericActiveForegroundColor: Int
    ) : Theme() {
        @Parcelize
        @Serializable
        data class CustomBackground(
            val croppedFilePath: String,
            val srcFilePath: String,
            val brightness: Int = 70,
            val cropRect: @Serializable(RectSerializer::class) Rect?,
            val cropRotation: Int = 0,
            val blurRadius: Float = 0f // 0 = no blur, 1-25 = blur radius
        ) : Parcelable {
            fun toDrawable(): Drawable? {
                return loadBitmapForRendering()?.let { bitmap ->
                    BitmapDrawable(appContext.resources, bitmap).apply {
                        colorFilter = DarkenColorFilter(100 - brightness)
                    }
                }
            }
            
            /**
             * Load bitmap with optional blur
             */
            fun toBlurredDrawable(): Drawable? {
                val bitmap = loadBlurredBitmapForRendering() ?: return null
                return BitmapDrawable(appContext.resources, bitmap).apply {
                    colorFilter = DarkenColorFilter(100 - brightness)
                }
            }

            fun loadBlurredBitmapForRendering(): android.graphics.Bitmap? {
                val bitmap = loadBitmapForRendering() ?: return null
                return if (blurRadius > 0f) {
                    BackgroundBlurBitmapCache.getOrPut(blurCacheKey()) {
                        BitmapBlurUtil.blur(bitmap, blurRadius)
                    }
                } else {
                    bitmap
                }
            }

            fun loadBitmapForRendering(): android.graphics.Bitmap? {
                // Try direct path first (absolute path)
                val cropped = File(croppedFilePath)
                if (cropped.exists()) {
                    return runCatching {
                        BitmapFactory.decodeStream(cropped.inputStream())
                    }.getOrNull()
                }

                // Try relative to theme directory
                return runCatching {
                    val appFilesDir = appContext.getExternalFilesDir(null)
                    if (appFilesDir != null) {
                        val themeDir = File(appFilesDir, "theme")
                        val relativeFile = File(themeDir, croppedFilePath)
                        if (relativeFile.exists()) {
                            relativeFile.inputStream().use { stream ->
                                return@runCatching BitmapFactory.decodeStream(stream)
                            }
                        }
                    }
                    null
                }.getOrNull()
            }

            private fun blurCacheKey(): String {
                val file = File(croppedFilePath)
                val mtime = file.takeIf { it.exists() }?.lastModified() ?: 0L
                return "$croppedFilePath|$blurRadius|$mtime"
            }
        }

        override fun backgroundDrawable(keyBorder: Boolean): Drawable {
            return backgroundImage?.toDrawable() ?: super.backgroundDrawable(keyBorder)
        }
        
        /**
         * Get background drawable with optional blur effect.
         * Only applies blur when:
         * 1. Background image exists
         * 2. blurRadius > 0
         * 3. Key background color is semi-transparent
         */
        fun blurredBackgroundDrawable(keyBorder: Boolean, enableBlur: Boolean = false): Drawable {
            if (!enableBlur) return backgroundDrawable(keyBorder)
            
            return backgroundImage?.toBlurredDrawable() ?: super.backgroundDrawable(keyBorder)
        }
        
        /**
         * Check if blur effect should be applied.
         * Returns true when background image exists and blurRadius > 0.
         */
        fun shouldApplyBlur(): Boolean {
            val bg = backgroundImage ?: return false
            return bg.blurRadius > 0f
        }

    }

    @Parcelize
    data class Builtin(
        override val name: String,
        override val isDark: Boolean,
        override val backgroundColor: Int,
        override val barColor: Int,
        override val keyboardColor: Int,
        override val keyBackgroundColor: Int,
        override val keyTextColor: Int,
        override val candidateTextColor: Int,
        override val candidateLabelColor: Int,
        override val candidateCommentColor: Int,
        override val altKeyBackgroundColor: Int,
        override val altKeyTextColor: Int,
        override val accentKeyBackgroundColor: Int,
        override val accentKeyTextColor: Int,
        override val keyPressHighlightColor: Int,
        override val keyShadowColor: Int,
        override val popupBackgroundColor: Int,
        override val popupTextColor: Int,
        override val spaceBarColor: Int,
        override val dividerColor: Int,
        override val clipboardEntryColor: Int,
        override val genericActiveBackgroundColor: Int,
        override val genericActiveForegroundColor: Int
    ) : Theme() {

        // an alias to use 0xAARRGGBB color literal in code
        // because kotlin compiler treats `0xff000000` as Long, not Int
        constructor(
            name: String,
            isDark: Boolean,
            backgroundColor: Number,
            barColor: Number,
            keyboardColor: Number,
            keyBackgroundColor: Number,
            keyTextColor: Number,
            candidateTextColor: Number,
            candidateLabelColor: Number,
            candidateCommentColor: Number,
            altKeyBackgroundColor: Number,
            altKeyTextColor: Number,
            accentKeyBackgroundColor: Number,
            accentKeyTextColor: Number,
            keyPressHighlightColor: Number,
            keyShadowColor: Number,
            popupBackgroundColor: Number,
            popupTextColor: Number,
            spaceBarColor: Number,
            dividerColor: Number,
            clipboardEntryColor: Number,
            genericActiveBackgroundColor: Number,
            genericActiveForegroundColor: Number
        ) : this(
            name,
            isDark,
            backgroundColor.toInt(),
            barColor.toInt(),
            keyboardColor.toInt(),
            keyBackgroundColor.toInt(),
            keyTextColor.toInt(),
            candidateTextColor.toInt(),
            candidateLabelColor.toInt(),
            candidateCommentColor.toInt(),
            altKeyBackgroundColor.toInt(),
            altKeyTextColor.toInt(),
            accentKeyBackgroundColor.toInt(),
            accentKeyTextColor.toInt(),
            keyPressHighlightColor.toInt(),
            keyShadowColor.toInt(),
            popupBackgroundColor.toInt(),
            popupTextColor.toInt(),
            spaceBarColor.toInt(),
            dividerColor.toInt(),
            clipboardEntryColor.toInt(),
            genericActiveBackgroundColor.toInt(),
            genericActiveForegroundColor.toInt()
        )

        fun deriveCustomNoBackground(name: String) = Custom(
            name,
            isDark,
            null,
            backgroundColor,
            barColor,
            keyboardColor,
            keyBackgroundColor,
            keyTextColor,
            candidateTextColor,
            candidateLabelColor,
            candidateCommentColor,
            altKeyBackgroundColor,
            altKeyTextColor,
            accentKeyBackgroundColor,
            accentKeyTextColor,
            keyPressHighlightColor,
            keyShadowColor,
            popupBackgroundColor,
            popupTextColor,
            spaceBarColor,
            dividerColor,
            clipboardEntryColor,
            genericActiveBackgroundColor,
            genericActiveForegroundColor
        )

        fun deriveCustomBackground(
            name: String,
            croppedBackgroundImage: String,
            originBackgroundImage: String,
            brightness: Int = 70,
            cropBackgroundRect: Rect? = null,
            cropBackgroundRotation: Int = 0,
            blurRadius: Float = 10f // Default blur for frosted glass effect
        ) = Custom(
            name,
            isDark,
            Custom.CustomBackground(
                croppedBackgroundImage,
                originBackgroundImage,
                brightness,
                cropBackgroundRect,
                cropBackgroundRotation,
                blurRadius
            ),
            backgroundColor,
            barColor,
            keyboardColor,
            keyBackgroundColor,
            keyTextColor,
            candidateTextColor,
            candidateLabelColor,
            candidateCommentColor,
            altKeyBackgroundColor,
            altKeyTextColor,
            accentKeyBackgroundColor,
            accentKeyTextColor,
            keyPressHighlightColor,
            keyShadowColor,
            popupBackgroundColor,
            popupTextColor,
            spaceBarColor,
            dividerColor,
            clipboardEntryColor,
            genericActiveBackgroundColor,
            genericActiveForegroundColor
        )
    }

    @Parcelize
    data class Monet(
        override val name: String,
        override val isDark: Boolean,
        override val backgroundColor: Int,
        override val barColor: Int,
        override val keyboardColor: Int,
        override val keyBackgroundColor: Int,
        override val keyTextColor: Int,
        override val candidateTextColor: Int,
        override val candidateLabelColor: Int,
        override val candidateCommentColor: Int,
        override val altKeyBackgroundColor: Int,
        override val altKeyTextColor: Int,
        override val accentKeyBackgroundColor: Int,
        override val accentKeyTextColor: Int,
        override val keyPressHighlightColor: Int,
        override val keyShadowColor: Int,
        override val popupBackgroundColor: Int,
        override val popupTextColor: Int,
        override val spaceBarColor: Int,
        override val dividerColor: Int,
        override val clipboardEntryColor: Int,
        override val genericActiveBackgroundColor: Int,
        override val genericActiveForegroundColor: Int
    ) : Theme() {
        constructor(
            isDark: Boolean,
            surfaceContainer: Int,
            surfaceContainerHigh: Int,
            surfaceBright: Int,
            onSurface: Int,
            primary: Int,
            onPrimary: Int,
            secondaryContainer: Int,
            onSurfaceVariant: Int,
        ) : this(
            name = "Monet" + if (isDark) "Dark" else "Light",
            isDark = isDark,
            backgroundColor = surfaceContainer,
            barColor = surfaceContainerHigh,
            keyboardColor = surfaceContainer,
            keyBackgroundColor = surfaceBright,
            keyTextColor = onSurface,
            candidateTextColor = onSurface,
            candidateLabelColor = onSurface,
            candidateCommentColor = onSurfaceVariant,
            altKeyBackgroundColor = secondaryContainer,
            altKeyTextColor = onSurfaceVariant,
            accentKeyBackgroundColor = primary,
            accentKeyTextColor = onPrimary,
            keyPressHighlightColor = onSurface.alpha(if (isDark) 0.2f else 0.12f),
            keyShadowColor = 0x000000,
            popupBackgroundColor = surfaceContainer,
            popupTextColor = onSurface,
            spaceBarColor = surfaceBright,
            dividerColor = surfaceBright,
            clipboardEntryColor = surfaceBright,
            genericActiveBackgroundColor = primary,
            genericActiveForegroundColor = onPrimary
        )

        @OptIn(ExperimentalStdlibApi::class)
        fun toCustom() = Custom(
            name = name + "#" + this.accentKeyBackgroundColor.toHexString(), // Use primary color as identifier
            isDark = isDark,
            backgroundImage = null,
            backgroundColor = backgroundColor,
            barColor = barColor,
            keyboardColor = keyboardColor,
            keyBackgroundColor = keyBackgroundColor,
            keyTextColor = keyTextColor,
            candidateTextColor = candidateTextColor,
            candidateLabelColor = candidateLabelColor,
            candidateCommentColor = candidateCommentColor,
            altKeyBackgroundColor = altKeyBackgroundColor,
            altKeyTextColor = altKeyTextColor,
            accentKeyBackgroundColor = accentKeyBackgroundColor,
            accentKeyTextColor = accentKeyTextColor,
            keyPressHighlightColor = keyPressHighlightColor,
            keyShadowColor = keyShadowColor,
            popupBackgroundColor = popupBackgroundColor,
            popupTextColor = popupTextColor,
            spaceBarColor = spaceBarColor,
            dividerColor = dividerColor,
            clipboardEntryColor = clipboardEntryColor,
            genericActiveBackgroundColor = genericActiveBackgroundColor,
            genericActiveForegroundColor = genericActiveForegroundColor
        )
    }
}
