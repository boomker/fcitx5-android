/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.res.Configuration
import org.fcitx.fcitx5.android.utils.DeviceInfoCollector
import org.fcitx.fcitx5.android.utils.DeviceType

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
     */
    fun getDeviceInfo(): DeviceInfoCollector.DeviceInfo {
        if (cachedDeviceInfo == null || orientationChanged()) {
            cachedDeviceInfo = DeviceInfoCollector.collect(context)
            lastOrientation = context.resources.configuration.orientation
        }
        return cachedDeviceInfo!!
    }

    /**
     * Check if orientation has changed since last cache.
     */
    private fun orientationChanged(): Boolean {
        val currentOrientation = context.resources.configuration.orientation
        return currentOrientation != lastOrientation
    }

    /**
     * Calculate current keyboard width (dp) with orientation-aware side padding.
     */
    fun calculateKeyboardWidthDp(): Int {
        return DeviceInfoCollector.calculateKeyboardWidthDp(
            context,
            keyboardPrefs.keyboardSidePadding.getValue(),
            keyboardPrefs.keyboardSidePaddingLandscape.getValue()
        )
    }

    /**
     * Determine whether to use split keyboard.
     */
    fun shouldUseSplitKeyboard(): Boolean {
        // If switch is off → never split
        if (!keyboardPrefs.splitKeyboardEnabled.getValue()) {
            return false
        }

        // If switch is on → determine based on keyboard width and threshold
        val keyboardWidthDp = calculateKeyboardWidthDp()
        val threshold = keyboardPrefs.splitKeyboardThreshold.getValue()
        return keyboardWidthDp >= threshold
    }

    /**
     * Get split keyboard gap percentage.
     */
    fun getSplitGapPercent(): Float {
        return (keyboardPrefs.splitKeyboardGapPercent.getValue().coerceIn(5, 60) / 100f)
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
