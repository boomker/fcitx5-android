/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.fxboomk.fcitx5.android.utils.DeviceInfoCollector
import org.fxboomk.fcitx5.android.utils.DeviceType

/**
 * Split keyboard state manager.
 *
 * Centralizes split keyboard state management to avoid duplicated logic and ensure consistency.
 */
class SplitKeyboardStateManager private constructor(private val context: Context) {

    private val prefs = AppPrefs.getInstance()
    private val keyboardPrefs = prefs.keyboard

    private var cachedDeviceInfo: DeviceInfoCollector.DeviceInfo? = null
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var lastSmallestWidthDp: Int = 0

    /**
     * Listener for split keyboard state changes.
     */
    fun interface OnSplitStateChangeListener {
        fun onSplitStateChanged(shouldSplit: Boolean)
    }

    private val listeners = mutableListOf<OnSplitStateChangeListener>()

    /**
     * Register a listener for split keyboard state changes.
     */
    fun registerListener(listener: OnSplitStateChangeListener) {
        listeners.add(listener)
    }

    /**
     * Unregister a listener for split keyboard state changes.
     */
    fun unregisterListener(listener: OnSplitStateChangeListener) {
        listeners.remove(listener)
    }

    /**
     * Get current device info (cached).
     * 
     * Cache is refreshed when:
     * 1. Cache is null (first access)
     * 2. Screen orientation changed (portrait ↔ landscape)
     * 3. Smallest width changed (foldable inner/outer screen switch)
     */
    fun getDeviceInfo(refresh: Boolean = false): DeviceInfoCollector.DeviceInfo {
        if (refresh || shouldRefreshCache()) {
            cachedDeviceInfo = DeviceInfoCollector.collect(context)
            lastOrientation = context.resources.configuration.orientation
            lastSmallestWidthDp = context.resources.configuration.smallestScreenWidthDp
            Log.d(TAG, "Device info refreshed: orientation=$lastOrientation, smallestWidth=$lastSmallestWidthDp")
        }
        return cachedDeviceInfo!!
    }

    /**
     * Check if cache should be refreshed.
     * 
     * Cache refresh is triggered when:
     * 1. Orientation changed (portrait ↔ landscape)
     * 2. Smallest width changed (foldable inner/outer screen switch)
     */
    private fun shouldRefreshCache(): Boolean {
        val currentOrientation = context.resources.configuration.orientation
        val currentSmallestWidth = context.resources.configuration.smallestScreenWidthDp
        
        val orientationChanged = currentOrientation != lastOrientation
        val screenConfigChanged = currentSmallestWidth != lastSmallestWidthDp
        
        if (orientationChanged) {
            Log.d(TAG, "Orientation changed: $lastOrientation → $currentOrientation")
        }
        if (screenConfigChanged) {
            Log.d(TAG, "Smallest width changed: $lastSmallestWidthDp → $currentSmallestWidth")
        }
        
        return orientationChanged || screenConfigChanged
    }

    /**
     * Manually refresh device info cache.
     * 
     * Call this when you detect a foldable screen state change (inner/outer screen switch).
     */
    fun refreshDeviceInfo() {
        Log.d(TAG, "Manual device info refresh requested")
        getDeviceInfo(refresh = true)
    }

    /**
     * Calculate current keyboard width (dp) based on actual screen width.
     *
     * This handles edge cases like:
     * - Portrait-forced app on landscape device (narrow strip)
     * - Multi-window / split-screen mode
     */
    fun calculateKeyboardWidthDp(): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels
        val density = displayMetrics.density
        
        // Calculate actual keyboard width from current screen width
        // Use landscape side padding as default (since device is physically landscape)
        val sidePaddingDp = keyboardPrefs.keyboardSidePaddingLandscape.getValue()
        val sidePaddingPx = (sidePaddingDp * density).toInt()
        val keyboardWidthPx = (screenWidthPx - sidePaddingPx * 2).coerceAtLeast(0)
        return (keyboardWidthPx / density).toInt()
    }

    /**
     * Determine whether to use split keyboard based on view width.
     *
     * @param viewWidthPx The actual keyboard view width in pixels
     * @return true if split keyboard should be used
     */
    fun shouldUseSplitKeyboard(viewWidthPx: Int): Boolean {
        // If switch is off → never split
        if (!keyboardPrefs.splitKeyboardEnabled.getValue()) {
            return false
        }

        // Calculate keyboard width from actual view width
        val density = context.resources.displayMetrics.density
        val keyboardWidthDp = (viewWidthPx / density).toInt()
        val threshold = getSplitKeyboardThreshold()
        return keyboardWidthDp >= threshold
    }

    /**
     * Determine whether to use split keyboard (fallback without view width).
     */
    fun shouldUseSplitKeyboard(): Boolean {
        return shouldUseSplitKeyboard(calculateKeyboardWidthDp())
    }

    /**
     * Get split keyboard threshold.
     */
    fun getSplitKeyboardThreshold(): Int {
        return keyboardPrefs.splitKeyboardThreshold.getValue()
    }

    /**
     * Set split keyboard threshold.
     */
    fun setSplitKeyboardThreshold(threshold: Int) {
        keyboardPrefs.splitKeyboardThreshold.setValue(threshold)
    }

    /**
     * Get split keyboard gap percentage.
     */
    fun getSplitGapPercent(): Float {
        return (keyboardPrefs.splitKeyboardGapPercent.getValue().coerceIn(GAP_MIN, GAP_MAX) / 100f)
    }

    /**
     * Set split keyboard gap percentage.
     */
    fun setSplitGapPercent(gap: Int) {
        keyboardPrefs.splitKeyboardGapPercent.setValue(gap.coerceIn(GAP_MIN, GAP_MAX))
    }

    /**
     * Get recommended threshold based on device type.
     */
    fun getRecommendedThreshold(): Pair<Int, String> {
        val deviceType = getDeviceInfo().deviceType
        return when (deviceType) {
            DeviceType.TABLET -> 420 to "split_keyboard_recommend_reason_tablet"
            DeviceType.FOLDABLE -> {
                val info = getDeviceInfo()
                if (info.hasMultipleScreens) {
                    val minSmallestWidth = info.screenConfigs.minOfOrNull { it.smallestWidthDp } ?: 440
                    440 to "split_keyboard_recommend_reason_foldable_multi"
                } else {
                    440 to "split_keyboard_recommend_reason_foldable"
                }
            }
            DeviceType.LARGE_PHONE -> 520 to "split_keyboard_recommend_reason_large_phone"
            DeviceType.SMALL_PHONE -> 500 to "split_keyboard_recommend_reason_small_phone"
            DeviceType.PHONE -> 470 to "split_keyboard_recommend_reason_phone"
        }
    }

    /**
     * Get recommended gap based on device type.
     */
    fun getRecommendedGap(): Int {
        return when (getDeviceInfo().deviceType) {
            DeviceType.TABLET -> 25
            DeviceType.FOLDABLE -> 20
            else -> 18
        }
    }

    /**
     * Notify all listeners of state change.
     */
    internal fun notifyListeners(shouldSplit: Boolean) {
        listeners.forEach { it.onSplitStateChanged(shouldSplit) }
    }

    /**
     * Companion object for singleton access.
     */
    companion object {
        private const val TAG = "SplitKeyboardState"
        private const val DEFAULT_THRESHOLD = 470
        private const val DEFAULT_GAP = 20
        private const val GAP_MIN = 5
        private const val GAP_MAX = 60

        @Volatile
        private var instance: SplitKeyboardStateManager? = null

        /**
         * Initialize the manager (must be called before use).
         */
        fun init(context: Context) {
            if (instance == null) {
                synchronized(SplitKeyboardStateManager::class) {
                    if (instance == null) {
                        instance = SplitKeyboardStateManager(context.applicationContext)
                    }
                }
            }
        }

        /**
         * Get the singleton instance.
         */
        fun getInstance(): SplitKeyboardStateManager {
            return instance ?: throw IllegalStateException(
                "SplitKeyboardStateManager not initialized. Call init() first."
            )
        }

        /**
         * Check if manager is initialized.
         */
        fun isInitialized(): Boolean = instance != null
    }
}
