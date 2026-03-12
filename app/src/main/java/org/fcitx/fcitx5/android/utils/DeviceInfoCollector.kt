/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Display

/**
 * Device type enumeration.
 */
enum class DeviceType {
    SMALL_PHONE,      // Small phone (<400dp)
    PHONE,            // Standard phone (400-500dp)
    LARGE_PHONE,      // Large phone (≥500dp)
    FOLDABLE,         // Foldable device
    TABLET            // Tablet (≥600dp)
}

/**
 * Device information collector.
 */
object DeviceInfoCollector {

    /**
     * Multi-screen configuration information (for foldable devices).
     */
    data class ScreenConfig(
        val smallestWidthDp: Int,
        val screenWidthDp: Int,
        val screenHeightDp: Int,
        val densityDpi: Int,
        val orientation: Int
    )

    data class DeviceInfo(
        val deviceType: DeviceType,
        val smallestWidthDp: Int,
        val isFoldable: Boolean,
        val modelName: String,
        val keyboardWidthDp: Int,
        val orientation: Int,
        // Foldable multi-screen information
        val screenConfigs: List<ScreenConfig> = emptyList(),
        val hasMultipleScreens: Boolean = false
    )

    /**
     * Collect current device information.
     */
    fun collect(context: Context): DeviceInfo {
        val config = context.resources.configuration
        val smallestWidthDp = config.smallestScreenWidthDp
        val isFoldable = detectFoldable(context)
        val modelName = Build.MODEL
        val keyboardWidthDp = calculateKeyboardWidthDp(context)
        val orientation = config.orientation
        val screenConfigs = collectScreenConfigs(context)

        val deviceType = classifyDevice(smallestWidthDp, isFoldable)

        return DeviceInfo(
            deviceType = deviceType,
            smallestWidthDp = smallestWidthDp,
            isFoldable = isFoldable,
            modelName = modelName,
            keyboardWidthDp = keyboardWidthDp,
            orientation = orientation,
            screenConfigs = screenConfigs,
            hasMultipleScreens = screenConfigs.size > 1
        )
    }

    /**
     * Collect all screen configurations (for foldable inner/outer screen detection).
     */
    private fun collectScreenConfigs(context: Context): List<ScreenConfig> {
        val configs = mutableListOf<ScreenConfig>()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            ?: return configs

        // Android 10+ supports multiple displays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val displays = displayManager.displays
            displays.forEach { display ->
                // Only collect primary display and foldable secondary display
                if (display.displayId == Display.DEFAULT_DISPLAY ||
                    display.flags and android.view.Display.FLAG_PRIVATE != 0
                ) {
                    val metrics = android.util.DisplayMetrics()
                    display.getRealMetrics(metrics)

                    val widthDp = (metrics.widthPixels / metrics.density).toInt()
                    val heightDp = (metrics.heightPixels / metrics.density).toInt()
                    val smallestWidth = minOf(widthDp, heightDp)

                    configs.add(
                        ScreenConfig(
                            smallestWidthDp = smallestWidth,
                            screenWidthDp = widthDp,
                            screenHeightDp = heightDp,
                            densityDpi = metrics.densityDpi,
                            orientation = if (widthDp > heightDp) Configuration.ORIENTATION_LANDSCAPE
                                         else Configuration.ORIENTATION_PORTRAIT
                        )
                    )
                }
            }
        }

        // If no multi-screen info collected, at least add current screen
        if (configs.isEmpty()) {
            val config = context.resources.configuration
            configs.add(
                ScreenConfig(
                    smallestWidthDp = config.smallestScreenWidthDp,
                    screenWidthDp = config.screenWidthDp,
                    screenHeightDp = config.screenHeightDp,
                    densityDpi = config.densityDpi,
                    orientation = config.orientation
                )
            )
        }

        return configs.distinctBy { it.smallestWidthDp to it.densityDpi }
    }

    /**
     * Classify device based on screen width and foldable status.
     */
    private fun classifyDevice(smallestWidthDp: Int, isFoldable: Boolean): DeviceType {
        return when {
            // Tablet
            smallestWidthDp >= 600 -> DeviceType.TABLET

            // Foldable
            isFoldable -> DeviceType.FOLDABLE

            // Large phone
            smallestWidthDp >= 500 -> DeviceType.LARGE_PHONE

            // Small phone
            smallestWidthDp < 400 -> DeviceType.SMALL_PHONE

            // Standard phone
            else -> DeviceType.PHONE
        }
    }

    /**
     * Detect if device is foldable.
     *
     * Uses multiple detection methods:
     * 1. System feature (android.hardware.screen.portable)
     * 2. Multiple screen configurations (inner/outer screens)
     * 3. Device model name keywords
     */
    private fun detectFoldable(context: Context): Boolean {
        // Detect via system feature
        if (context.packageManager.hasSystemFeature("android.hardware.screen.portable")) {
            return true
        }

        // Detect via multiple screen configurations (foldable devices typically have inner/outer screens)
        val screenConfigs = collectScreenConfigs(context)
        if (screenConfigs.size > 1) {
            // Multiple screens with significantly different smallest widths indicate foldable
            val smallestWidths = screenConfigs.map { it.smallestWidthDp }.sorted()
            val widthDifference = smallestWidths.zipWithNext().any { (a, b) -> kotlin.math.abs(b - a) >= 100 }
            if (widthDifference) {
                return true
            }
        }

        // Detect via device model name (common foldable device keywords)
        val model = Build.MODEL.lowercase()
        val foldableKeywords = listOf("fold", "flip", "flex", "multi vision")
        return foldableKeywords.any { model.contains(it) }
    }

    /**
     * Calculate current keyboard width (dp).
     */
    private fun calculateKeyboardWidthDp(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels
        // Simplified calculation; actual keyboard width may be slightly smaller due to padding
        return (screenWidthPx / displayMetrics.density).toInt()
    }

    /**
     * Calculate keyboard width (dp) with side padding consideration.
     *
     * @param context Android context
     * @param sidePaddingDp Side padding in dp (use 0 for no side padding)
     * @return Keyboard width in dp
     */
    fun calculateKeyboardWidthDp(context: Context, sidePaddingDp: Int): Int {
        val displayMetrics = context.resources.displayMetrics
        val screenWidthPx = displayMetrics.widthPixels
        val sidePaddingPx = (sidePaddingDp * displayMetrics.density).toInt()
        val keyboardWidthPx = (screenWidthPx - sidePaddingPx * 2).coerceAtLeast(0)
        return (keyboardWidthPx / displayMetrics.density).toInt()
    }

    /**
     * Calculate keyboard width (dp) with orientation-aware side padding.
     *
     * @param context Android context
     * @param sidePaddingDpPortrait Side padding in dp for portrait orientation
     * @param sidePaddingDpLandscape Side padding in dp for landscape orientation
     * @return Keyboard width in dp
     */
    fun calculateKeyboardWidthDp(
        context: Context,
        sidePaddingDpPortrait: Int,
        sidePaddingDpLandscape: Int
    ): Int {
        val orientation = context.resources.configuration.orientation
        val sidePaddingDp = when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> sidePaddingDpLandscape
            else -> sidePaddingDpPortrait
        }
        return calculateKeyboardWidthDp(context, sidePaddingDp)
    }
}
