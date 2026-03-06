/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.font

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CandidateAutoScaleTextView
import org.fcitx.fcitx5.android.input.config.ConfigProviders

interface FontProviderApi {
    fun clearCache()
    val fontTypefaceMap: MutableMap<String, Typeface?>
}

object FontProviders {
    // default provider is an instance of `DefaultFontProvider`
    @Volatile
    var provider: FontProviderApi = DefaultFontProvider()

    @Volatile
    private var listenerRegistered = false

    @Volatile
    private var needsAttachRefresh: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dispatchRefreshAllViews() {
        mainHandler.post {
            AutoScaleTextView.refreshAllFontTypeFaces()
            CandidateAutoScaleTextView.refreshAllFontTypeFaces()
        }
    }

    private fun handleFontsetChanged() {
        provider.clearCache()
        needsAttachRefresh = true
        dispatchRefreshAllViews()
    }

    @Synchronized
    private fun ensureListenerRegistered() {
        if (listenerRegistered) return
        ConfigProviders.addFontsetListener {
            handleFontsetChanged()
        }
        listenerRegistered = true
    }

    fun refreshIfNeededOnAttach() {
        ensureListenerRegistered()
        if (!needsAttachRefresh) return
        needsAttachRefresh = false
        dispatchRefreshAllViews()
    }

    fun clearCache() {
        provider.clearCache()
        needsAttachRefresh = true
    }

    val fontTypefaceMap: MutableMap<String, Typeface?>
        get() = provider.fontTypefaceMap
}
