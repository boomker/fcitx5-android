/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import org.fcitx.fcitx5.android.input.config.ConfigProviders

interface FontProviderApi {
    fun clearCache()
    val fontTypefaceMap: MutableMap<String, Typeface?>
}

object FontProviders {
    @Volatile
    var provider: FontProviderApi = DefaultFontProvider()

    @Volatile
    private var needsRefresh = false

    init {
        ensureListenerRegistered()
    }

    private fun ensureListenerRegistered() {
        ConfigProviders.addFontsetListener {
            handleFontsetChanged()
        }
    }

    private fun handleFontsetChanged() {
        provider.clearCache()
        needsRefresh = true
    }

    /**
     * Mark refresh needed after saving fontset in settings.
     */
    fun markNeedsRefresh() {
        provider.clearCache()
        needsRefresh = true
    }

    /**
     * Check and clear refresh flag. Call when keyboard loads.
     * @return true if font changed and keyboard needs refresh
     */
    fun checkAndClearRefreshFlag(): Boolean {
        if (!needsRefresh) return false
        needsRefresh = false
        return true
    }

    fun clearCache() {
        provider.clearCache()
        needsRefresh = true
    }

    val fontTypefaceMap: MutableMap<String, Typeface?>
        get() = provider.fontTypefaceMap

    /**
     * Preload fonts asynchronously. Call this before keyboard is shown.
     */
    fun preloadFontsAsync(onComplete: ((MutableMap<String, Typeface?>) -> Unit)? = null) {
        if (provider is DefaultFontProvider) {
            (provider as DefaultFontProvider).preloadFontsAsync(onComplete)
        } else {
            // For other providers, just return cached map immediately
            onComplete?.invoke(fontTypefaceMap)
        }
    }

    /**
     * Check if user has custom fonts configured in fontset.json.
     * @return true if any custom font is configured, false if using system default fonts
     */
    fun hasCustomFonts(): Boolean {
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot().getOrNull() ?: return false
        // Check if any font path is non-empty
        return snapshot.value.values.flatten().any { it.trim().isNotEmpty() }
    }
}
