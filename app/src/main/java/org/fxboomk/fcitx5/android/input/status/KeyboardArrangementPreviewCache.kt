/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.config.KeyIconThemeStore
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.utils.appContext

/** Keep only rendered images across panel instances, with a fixed pixel-memory budget. */
internal object KeyboardArrangementPreviewCache {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private val generation = AtomicLong()
    private val images = object : LruCache<Key, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
        // Do not recycle evicted images: an ImageView can still be displaying them.
    }

    private data class Key(
        val ime: InputMethodEntry,
        val variant: ShuangpinKeyboardLayout,
        val rows: JsonArray?,
        val profile: String,
        val theme: Theme,
        val resourceTheme: Resources.Theme,
        val configuration: Configuration,
        val width: Int,
        val height: Int,
        val semicolon: Boolean,
        val simulatedCaps: Boolean,
        val fontGeneration: Long,
        val iconFilePath: String?,
        val iconFileModified: Long,
        val iconFileLength: Long,
        val backgroundFilePath: String?,
        val backgroundFileModified: Long,
        val backgroundFileLength: Long,
        val generation: Long,
    )

    private val prefs = AppPrefs.getInstance()
    // Selection and management state do not change the two independently rendered variants.
    private val selectionKeys = setOf(
        prefs.keyboard.textKeyboardLayoutProfile.key,
        prefs.keyboard.textKeyboardLayoutProfileOrder.key,
        prefs.keyboard.textKeyboardLayoutProfileCollapsed.key,
        prefs.keyboard.textKeyboardLayoutProfileFiltered.key,
        prefs.keyboard.textKeyboardLayoutBaseCollapsed.key,
        prefs.internal.compactShuangpinInputMethods.key,
        prefs.internal.lastNonEnglishIme.key,
        prefs.internal.lastNonEnglishSubMode.key,
    )

    @Keep
    private val onPreferenceChanged = ManagedPreferenceProvider.OnChangeListener { key ->
        if (key !in selectionKeys) clear()
    }

    @Keep
    private val onThemeChanged = ThemeManager.OnThemeChangeListener { clear() }

    @Keep
    private val onThemeListChanged = ThemeManager.OnThemeListChangeListener { clear() }

    @Keep
    private val onIconsChanged: () -> Unit = { clear() }

    init {
        prefs.keyboard.registerOnChangeListener(onPreferenceChanged)
        prefs.internal.registerOnChangeListener(onPreferenceChanged)
        prefs.advanced.registerOnChangeListener(onPreferenceChanged)
        ThemeManager.addOnChangedListener(onThemeChanged)
        ThemeManager.addOnThemeListChangedListener(onThemeListChanged)
        KeyIconThemeStore.addOnChangedListener(onIconsChanged)
    }

    internal fun clear() {
        generation.incrementAndGet()
        images.evictAll()
    }

    fun getOrRender(
        context: Context,
        theme: Theme,
        ime: InputMethodEntry,
        variant: ShuangpinKeyboardLayout,
        rows: JsonArray?,
        width: Int,
        height: Int,
        semicolon: Boolean,
        render: () -> Bitmap,
    ): Bitmap {
        val icons = UserConfigFiles.keyIconThemesConfig()
        val background = backgroundFile(theme)
        val key = Key(
            ime, variant, rows, prefs.keyboard.textKeyboardLayoutProfile.getValue(), theme,
            context.theme, Configuration(context.resources.configuration), width, height, semicolon,
            simulatedCaps(context), FontProviders.fontGeneration,
            icons?.absolutePath, icons?.lastModified() ?: 0L, icons?.length() ?: 0L,
            background?.absolutePath, background?.lastModified() ?: 0L, background?.length() ?: 0L,
            generation.get(),
        )
        images.get(key)?.takeUnless { it.isRecycled }?.let { return it }
        // View rendering and icon lookup must stay outside the cache's lock.
        return render().also { bitmap ->
            if (key.generation == generation.get() && bitmap.allocationByteCount <= MAX_BYTES) {
                images.put(key, bitmap)
            }
        }
    }

    private fun backgroundFile(theme: Theme): File? {
        val path = (theme as? Theme.Custom)?.backgroundImage?.croppedFilePath ?: return null
        val direct = File(path)
        if (direct.exists()) return direct
        return appContext.getExternalFilesDir(null)?.let { File(File(it, "theme"), path) } ?: direct
    }

    private fun simulatedCaps(context: Context): Boolean {
        var current = context
        while (current is ContextWrapper) {
            if (current is FcitxInputMethodService) return current.isSimulatedCapsLockOn()
            current = current.baseContext
        }
        return false
    }
}
