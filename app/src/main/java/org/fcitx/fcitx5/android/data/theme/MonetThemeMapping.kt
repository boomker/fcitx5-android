/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * 系统动态颜色资源 ID 枚举
 * 包含 Android 12+ (SDK >= 31) 支持的所有 Material You 动态颜色资源
 */
@Serializable
enum class SystemColorResourceId(val resourceId: String, val minSdk: Int = 31) {
    // Primary colors
    SYSTEM_PRIMARY_0("system_primary_0", 34),
    SYSTEM_PRIMARY_1("system_primary_1", 34),
    SYSTEM_PRIMARY_2("system_primary_2", 34),
    SYSTEM_PRIMARY_3("system_primary_3", 34),
    SYSTEM_PRIMARY_4("system_primary_4", 34),
    SYSTEM_PRIMARY_5("system_primary_5", 34),
    SYSTEM_PRIMARY_6("system_primary_6", 34),
    SYSTEM_PRIMARY_7("system_primary_7", 34),
    SYSTEM_PRIMARY_8("system_primary_8", 34),
    SYSTEM_PRIMARY_9("system_primary_9", 34),
    SYSTEM_PRIMARY_10("system_primary_10", 34),
    SYSTEM_PRIMARY_11("system_primary_11", 34),
    SYSTEM_PRIMARY_12("system_primary_12", 34),
    
    // On Primary colors
    SYSTEM_ON_PRIMARY_0("system_on_primary_0", 34),
    SYSTEM_ON_PRIMARY_1("system_on_primary_1", 34),
    SYSTEM_ON_PRIMARY_2("system_on_primary_2", 34),
    SYSTEM_ON_PRIMARY_3("system_on_primary_3", 34),
    SYSTEM_ON_PRIMARY_4("system_on_primary_4", 34),
    SYSTEM_ON_PRIMARY_5("system_on_primary_5", 34),
    SYSTEM_ON_PRIMARY_6("system_on_primary_6", 34),
    SYSTEM_ON_PRIMARY_7("system_on_primary_7", 34),
    SYSTEM_ON_PRIMARY_8("system_on_primary_8", 34),
    SYSTEM_ON_PRIMARY_9("system_on_primary_9", 34),
    SYSTEM_ON_PRIMARY_10("system_on_primary_10", 34),
    SYSTEM_ON_PRIMARY_11("system_on_primary_11", 34),
    SYSTEM_ON_PRIMARY_12("system_on_primary_12", 34),
    
    // Primary Container colors
    SYSTEM_PRIMARY_CONTAINER_0("system_primary_container_0", 34),
    SYSTEM_PRIMARY_CONTAINER_1("system_primary_container_1", 34),
    SYSTEM_PRIMARY_CONTAINER_2("system_primary_container_2", 34),
    SYSTEM_PRIMARY_CONTAINER_3("system_primary_container_3", 34),
    SYSTEM_PRIMARY_CONTAINER_4("system_primary_container_4", 34),
    SYSTEM_PRIMARY_CONTAINER_5("system_primary_container_5", 34),
    SYSTEM_PRIMARY_CONTAINER_6("system_primary_container_6", 34),
    SYSTEM_PRIMARY_CONTAINER_7("system_primary_container_7", 34),
    SYSTEM_PRIMARY_CONTAINER_8("system_primary_container_8", 34),
    SYSTEM_PRIMARY_CONTAINER_9("system_primary_container_9", 34),
    SYSTEM_PRIMARY_CONTAINER_10("system_primary_container_10", 34),
    SYSTEM_PRIMARY_CONTAINER_11("system_primary_container_11", 34),
    SYSTEM_PRIMARY_CONTAINER_12("system_primary_container_12", 34),
    
    // On Primary Container colors
    SYSTEM_ON_PRIMARY_CONTAINER_0("system_on_primary_container_0", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_1("system_on_primary_container_1", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_2("system_on_primary_container_2", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_3("system_on_primary_container_3", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_4("system_on_primary_container_4", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_5("system_on_primary_container_5", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_6("system_on_primary_container_6", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_7("system_on_primary_container_7", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_8("system_on_primary_container_8", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_9("system_on_primary_container_9", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_10("system_on_primary_container_10", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_11("system_on_primary_container_11", 34),
    SYSTEM_ON_PRIMARY_CONTAINER_12("system_on_primary_container_12", 34),
    
    // Secondary colors
    SYSTEM_SECONDARY_0("system_secondary_0", 34),
    SYSTEM_SECONDARY_1("system_secondary_1", 34),
    SYSTEM_SECONDARY_2("system_secondary_2", 34),
    SYSTEM_SECONDARY_3("system_secondary_3", 34),
    SYSTEM_SECONDARY_4("system_secondary_4", 34),
    SYSTEM_SECONDARY_5("system_secondary_5", 34),
    SYSTEM_SECONDARY_6("system_secondary_6", 34),
    SYSTEM_SECONDARY_7("system_secondary_7", 34),
    SYSTEM_SECONDARY_8("system_secondary_8", 34),
    SYSTEM_SECONDARY_9("system_secondary_9", 34),
    SYSTEM_SECONDARY_10("system_secondary_10", 34),
    SYSTEM_SECONDARY_11("system_secondary_11", 34),
    SYSTEM_SECONDARY_12("system_secondary_12", 34),
    
    // On Secondary colors
    SYSTEM_ON_SECONDARY_0("system_on_secondary_0", 34),
    SYSTEM_ON_SECONDARY_1("system_on_secondary_1", 34),
    SYSTEM_ON_SECONDARY_2("system_on_secondary_2", 34),
    SYSTEM_ON_SECONDARY_3("system_on_secondary_3", 34),
    SYSTEM_ON_SECONDARY_4("system_on_secondary_4", 34),
    SYSTEM_ON_SECONDARY_5("system_on_secondary_5", 34),
    SYSTEM_ON_SECONDARY_6("system_on_secondary_6", 34),
    SYSTEM_ON_SECONDARY_7("system_on_secondary_7", 34),
    SYSTEM_ON_SECONDARY_8("system_on_secondary_8", 34),
    SYSTEM_ON_SECONDARY_9("system_on_secondary_9", 34),
    SYSTEM_ON_SECONDARY_10("system_on_secondary_10", 34),
    SYSTEM_ON_SECONDARY_11("system_on_secondary_11", 34),
    SYSTEM_ON_SECONDARY_12("system_on_secondary_12", 34),
    
    // Secondary Container colors
    SYSTEM_SECONDARY_CONTAINER_0("system_secondary_container_0", 34),
    SYSTEM_SECONDARY_CONTAINER_1("system_secondary_container_1", 34),
    SYSTEM_SECONDARY_CONTAINER_2("system_secondary_container_2", 34),
    SYSTEM_SECONDARY_CONTAINER_3("system_secondary_container_3", 34),
    SYSTEM_SECONDARY_CONTAINER_4("system_secondary_container_4", 34),
    SYSTEM_SECONDARY_CONTAINER_5("system_secondary_container_5", 34),
    SYSTEM_SECONDARY_CONTAINER_6("system_secondary_container_6", 34),
    SYSTEM_SECONDARY_CONTAINER_7("system_secondary_container_7", 34),
    SYSTEM_SECONDARY_CONTAINER_8("system_secondary_container_8", 34),
    SYSTEM_SECONDARY_CONTAINER_9("system_secondary_container_9", 34),
    SYSTEM_SECONDARY_CONTAINER_10("system_secondary_container_10", 34),
    SYSTEM_SECONDARY_CONTAINER_11("system_secondary_container_11", 34),
    SYSTEM_SECONDARY_CONTAINER_12("system_secondary_container_12", 34),
    
    // On Secondary Container colors
    SYSTEM_ON_SECONDARY_CONTAINER_0("system_on_secondary_container_0", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_1("system_on_secondary_container_1", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_2("system_on_secondary_container_2", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_3("system_on_secondary_container_3", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_4("system_on_secondary_container_4", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_5("system_on_secondary_container_5", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_6("system_on_secondary_container_6", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_7("system_on_secondary_container_7", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_8("system_on_secondary_container_8", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_9("system_on_secondary_container_9", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_10("system_on_secondary_container_10", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_11("system_on_secondary_container_11", 34),
    SYSTEM_ON_SECONDARY_CONTAINER_12("system_on_secondary_container_12", 34),
    
    // Tertiary colors
    SYSTEM_TERTIARY_0("system_tertiary_0", 34),
    SYSTEM_TERTIARY_1("system_tertiary_1", 34),
    SYSTEM_TERTIARY_2("system_tertiary_2", 34),
    SYSTEM_TERTIARY_3("system_tertiary_3", 34),
    SYSTEM_TERTIARY_4("system_tertiary_4", 34),
    SYSTEM_TERTIARY_5("system_tertiary_5", 34),
    SYSTEM_TERTIARY_6("system_tertiary_6", 34),
    SYSTEM_TERTIARY_7("system_tertiary_7", 34),
    SYSTEM_TERTIARY_8("system_tertiary_8", 34),
    SYSTEM_TERTIARY_9("system_tertiary_9", 34),
    SYSTEM_TERTIARY_10("system_tertiary_10", 34),
    SYSTEM_TERTIARY_11("system_tertiary_11", 34),
    SYSTEM_TERTIARY_12("system_tertiary_12", 34),
    
    // On Tertiary colors
    SYSTEM_ON_TERTIARY_0("system_on_tertiary_0", 34),
    SYSTEM_ON_TERTIARY_1("system_on_tertiary_1", 34),
    SYSTEM_ON_TERTIARY_2("system_on_tertiary_2", 34),
    SYSTEM_ON_TERTIARY_3("system_on_tertiary_3", 34),
    SYSTEM_ON_TERTIARY_4("system_on_tertiary_4", 34),
    SYSTEM_ON_TERTIARY_5("system_on_tertiary_5", 34),
    SYSTEM_ON_TERTIARY_6("system_on_tertiary_6", 34),
    SYSTEM_ON_TERTIARY_7("system_on_tertiary_7", 34),
    SYSTEM_ON_TERTIARY_8("system_on_tertiary_8", 34),
    SYSTEM_ON_TERTIARY_9("system_on_tertiary_9", 34),
    SYSTEM_ON_TERTIARY_10("system_on_tertiary_10", 34),
    SYSTEM_ON_TERTIARY_11("system_on_tertiary_11", 34),
    SYSTEM_ON_TERTIARY_12("system_on_tertiary_12", 34),
    
    // Tertiary Container colors
    SYSTEM_TERTIARY_CONTAINER_0("system_tertiary_container_0", 34),
    SYSTEM_TERTIARY_CONTAINER_1("system_tertiary_container_1", 34),
    SYSTEM_TERTIARY_CONTAINER_2("system_tertiary_container_2", 34),
    SYSTEM_TERTIARY_CONTAINER_3("system_tertiary_container_3", 34),
    SYSTEM_TERTIARY_CONTAINER_4("system_tertiary_container_4", 34),
    SYSTEM_TERTIARY_CONTAINER_5("system_tertiary_container_5", 34),
    SYSTEM_TERTIARY_CONTAINER_6("system_tertiary_container_6", 34),
    SYSTEM_TERTIARY_CONTAINER_7("system_tertiary_container_7", 34),
    SYSTEM_TERTIARY_CONTAINER_8("system_tertiary_container_8", 34),
    SYSTEM_TERTIARY_CONTAINER_9("system_tertiary_container_9", 34),
    SYSTEM_TERTIARY_CONTAINER_10("system_tertiary_container_10", 34),
    SYSTEM_TERTIARY_CONTAINER_11("system_tertiary_container_11", 34),
    SYSTEM_TERTIARY_CONTAINER_12("system_tertiary_container_12", 34),
    
    // On Tertiary Container colors
    SYSTEM_ON_TERTIARY_CONTAINER_0("system_on_tertiary_container_0", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_1("system_on_tertiary_container_1", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_2("system_on_tertiary_container_2", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_3("system_on_tertiary_container_3", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_4("system_on_tertiary_container_4", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_5("system_on_tertiary_container_5", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_6("system_on_tertiary_container_6", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_7("system_on_tertiary_container_7", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_8("system_on_tertiary_container_8", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_9("system_on_tertiary_container_9", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_10("system_on_tertiary_container_10", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_11("system_on_tertiary_container_11", 34),
    SYSTEM_ON_TERTIARY_CONTAINER_12("system_on_tertiary_container_12", 34),
    
    // Error colors
    SYSTEM_ERROR_0("system_error_0", 34),
    SYSTEM_ERROR_1("system_error_1", 34),
    SYSTEM_ERROR_2("system_error_2", 34),
    SYSTEM_ERROR_3("system_error_3", 34),
    SYSTEM_ERROR_4("system_error_4", 34),
    SYSTEM_ERROR_5("system_error_5", 34),
    SYSTEM_ERROR_6("system_error_6", 34),
    SYSTEM_ERROR_7("system_error_7", 34),
    SYSTEM_ERROR_8("system_error_8", 34),
    SYSTEM_ERROR_9("system_error_9", 34),
    SYSTEM_ERROR_10("system_error_10", 34),
    SYSTEM_ERROR_11("system_error_11", 34),
    SYSTEM_ERROR_12("system_error_12", 34),
    
    // On Error colors
    SYSTEM_ON_ERROR_0("system_on_error_0", 34),
    SYSTEM_ON_ERROR_1("system_on_error_1", 34),
    SYSTEM_ON_ERROR_2("system_on_error_2", 34),
    SYSTEM_ON_ERROR_3("system_on_error_3", 34),
    SYSTEM_ON_ERROR_4("system_on_error_4", 34),
    SYSTEM_ON_ERROR_5("system_on_error_5", 34),
    SYSTEM_ON_ERROR_6("system_on_error_6", 34),
    SYSTEM_ON_ERROR_7("system_on_error_7", 34),
    SYSTEM_ON_ERROR_8("system_on_error_8", 34),
    SYSTEM_ON_ERROR_9("system_on_error_9", 34),
    SYSTEM_ON_ERROR_10("system_on_error_10", 34),
    SYSTEM_ON_ERROR_11("system_on_error_11", 34),
    SYSTEM_ON_ERROR_12("system_on_error_12", 34),
    
    // Error Container colors
    SYSTEM_ERROR_CONTAINER_0("system_error_container_0", 34),
    SYSTEM_ERROR_CONTAINER_1("system_error_container_1", 34),
    SYSTEM_ERROR_CONTAINER_2("system_error_container_2", 34),
    SYSTEM_ERROR_CONTAINER_3("system_error_container_3", 34),
    SYSTEM_ERROR_CONTAINER_4("system_error_container_4", 34),
    SYSTEM_ERROR_CONTAINER_5("system_error_container_5", 34),
    SYSTEM_ERROR_CONTAINER_6("system_error_container_6", 34),
    SYSTEM_ERROR_CONTAINER_7("system_error_container_7", 34),
    SYSTEM_ERROR_CONTAINER_8("system_error_container_8", 34),
    SYSTEM_ERROR_CONTAINER_9("system_error_container_9", 34),
    SYSTEM_ERROR_CONTAINER_10("system_error_container_10", 34),
    SYSTEM_ERROR_CONTAINER_11("system_error_container_11", 34),
    SYSTEM_ERROR_CONTAINER_12("system_error_container_12", 34),
    
    // On Error Container colors
    SYSTEM_ON_ERROR_CONTAINER_0("system_on_error_container_0", 34),
    SYSTEM_ON_ERROR_CONTAINER_1("system_on_error_container_1", 34),
    SYSTEM_ON_ERROR_CONTAINER_2("system_on_error_container_2", 34),
    SYSTEM_ON_ERROR_CONTAINER_3("system_on_error_container_3", 34),
    SYSTEM_ON_ERROR_CONTAINER_4("system_on_error_container_4", 34),
    SYSTEM_ON_ERROR_CONTAINER_5("system_on_error_container_5", 34),
    SYSTEM_ON_ERROR_CONTAINER_6("system_on_error_container_6", 34),
    SYSTEM_ON_ERROR_CONTAINER_7("system_on_error_container_7", 34),
    SYSTEM_ON_ERROR_CONTAINER_8("system_on_error_container_8", 34),
    SYSTEM_ON_ERROR_CONTAINER_9("system_on_error_container_9", 34),
    SYSTEM_ON_ERROR_CONTAINER_10("system_on_error_container_10", 34),
    SYSTEM_ON_ERROR_CONTAINER_11("system_on_error_container_11", 34),
    SYSTEM_ON_ERROR_CONTAINER_12("system_on_error_container_12", 34),
    
    // Surface colors
    SYSTEM_SURFACE_0("system_surface_0", 34),
    SYSTEM_SURFACE_1("system_surface_1", 34),
    SYSTEM_SURFACE_2("system_surface_2", 34),
    SYSTEM_SURFACE_3("system_surface_3", 34),
    SYSTEM_SURFACE_4("system_surface_4", 34),
    SYSTEM_SURFACE_5("system_surface_5", 34),
    SYSTEM_SURFACE_6("system_surface_6", 34),
    SYSTEM_SURFACE_7("system_surface_7", 34),
    SYSTEM_SURFACE_8("system_surface_8", 34),
    SYSTEM_SURFACE_9("system_surface_9", 34),
    SYSTEM_SURFACE_10("system_surface_10", 34),
    SYSTEM_SURFACE_11("system_surface_11", 34),
    SYSTEM_SURFACE_12("system_surface_12", 34),
    
    // On Surface colors
    SYSTEM_ON_SURFACE_0("system_on_surface_0", 34),
    SYSTEM_ON_SURFACE_1("system_on_surface_1", 34),
    SYSTEM_ON_SURFACE_2("system_on_surface_2", 34),
    SYSTEM_ON_SURFACE_3("system_on_surface_3", 34),
    SYSTEM_ON_SURFACE_4("system_on_surface_4", 34),
    SYSTEM_ON_SURFACE_5("system_on_surface_5", 34),
    SYSTEM_ON_SURFACE_6("system_on_surface_6", 34),
    SYSTEM_ON_SURFACE_7("system_on_surface_7", 34),
    SYSTEM_ON_SURFACE_8("system_on_surface_8", 34),
    SYSTEM_ON_SURFACE_9("system_on_surface_9", 34),
    SYSTEM_ON_SURFACE_10("system_on_surface_10", 34),
    SYSTEM_ON_SURFACE_11("system_on_surface_11", 34),
    SYSTEM_ON_SURFACE_12("system_on_surface_12", 34),
    
    // Surface Container colors
    SYSTEM_SURFACE_CONTAINER("system_surface_container", 34),
    SYSTEM_SURFACE_CONTAINER_LOW("system_surface_container_low", 34),
    SYSTEM_SURFACE_CONTAINER_LOWEST("system_surface_container_lowest", 34),
    SYSTEM_SURFACE_CONTAINER_HIGH("system_surface_container_high", 34),
    SYSTEM_SURFACE_CONTAINER_HIGHEST("system_surface_container_highest", 34),
    
    // Surface Bright/Dim
    SYSTEM_SURFACE_BRIGHT("system_surface_bright", 34),
    SYSTEM_SURFACE_DIM("system_surface_dim", 34),
    
    // On Surface (base)
    SYSTEM_ON_SURFACE("system_on_surface", 34),

    // On Surface Variant
    SYSTEM_ON_SURFACE_VARIANT("system_on_surface_variant", 31),
    
    // Neutral colors (SDK 31+)
    SYSTEM_NEUTRAL1_0("system_neutral1_0", 31),
    SYSTEM_NEUTRAL1_10("system_neutral1_10", 31),
    SYSTEM_NEUTRAL1_50("system_neutral1_50", 31),
    SYSTEM_NEUTRAL1_100("system_neutral1_100", 31),
    SYSTEM_NEUTRAL1_200("system_neutral1_200", 31),
    SYSTEM_NEUTRAL1_300("system_neutral1_300", 31),
    SYSTEM_NEUTRAL1_400("system_neutral1_400", 31),
    SYSTEM_NEUTRAL1_500("system_neutral1_500", 31),
    SYSTEM_NEUTRAL1_600("system_neutral1_600", 31),
    SYSTEM_NEUTRAL1_700("system_neutral1_700", 31),
    SYSTEM_NEUTRAL1_800("system_neutral1_800", 31),
    SYSTEM_NEUTRAL1_900("system_neutral1_900", 31),
    SYSTEM_NEUTRAL1_1000("system_neutral1_1000", 31),
    
    SYSTEM_NEUTRAL2_0("system_neutral2_0", 31),
    SYSTEM_NEUTRAL2_10("system_neutral2_10", 31),
    SYSTEM_NEUTRAL2_50("system_neutral2_50", 31),
    SYSTEM_NEUTRAL2_100("system_neutral2_100", 31),
    SYSTEM_NEUTRAL2_200("system_neutral2_200", 31),
    SYSTEM_NEUTRAL2_300("system_neutral2_300", 31),
    SYSTEM_NEUTRAL2_400("system_neutral2_400", 31),
    SYSTEM_NEUTRAL2_500("system_neutral2_500", 31),
    SYSTEM_NEUTRAL2_600("system_neutral2_600", 31),
    SYSTEM_NEUTRAL2_700("system_neutral2_700", 31),
    SYSTEM_NEUTRAL2_800("system_neutral2_800", 31),
    SYSTEM_NEUTRAL2_900("system_neutral2_900", 31),
    SYSTEM_NEUTRAL2_1000("system_neutral2_1000", 31),
    
    // Accent colors (SDK 31+)
    SYSTEM_ACCENT1_0("system_accent1_0", 31),
    SYSTEM_ACCENT1_10("system_accent1_10", 31),
    SYSTEM_ACCENT1_50("system_accent1_50", 31),
    SYSTEM_ACCENT1_100("system_accent1_100", 31),
    SYSTEM_ACCENT1_200("system_accent1_200", 31),
    SYSTEM_ACCENT1_300("system_accent1_300", 31),
    SYSTEM_ACCENT1_400("system_accent1_400", 31),
    SYSTEM_ACCENT1_500("system_accent1_500", 31),
    SYSTEM_ACCENT1_600("system_accent1_600", 31),
    SYSTEM_ACCENT1_700("system_accent1_700", 31),
    SYSTEM_ACCENT1_800("system_accent1_800", 31),
    SYSTEM_ACCENT1_900("system_accent1_900", 31),
    SYSTEM_ACCENT1_1000("system_accent1_1000", 31),
    
    SYSTEM_ACCENT2_0("system_accent2_0", 31),
    SYSTEM_ACCENT2_10("system_accent2_10", 31),
    SYSTEM_ACCENT2_50("system_accent2_50", 31),
    SYSTEM_ACCENT2_100("system_accent2_100", 31),
    SYSTEM_ACCENT2_200("system_accent2_200", 31),
    SYSTEM_ACCENT2_300("system_accent2_300", 31),
    SYSTEM_ACCENT2_400("system_accent2_400", 31),
    SYSTEM_ACCENT2_500("system_accent2_500", 31),
    SYSTEM_ACCENT2_600("system_accent2_600", 31),
    SYSTEM_ACCENT2_700("system_accent2_700", 31),
    SYSTEM_ACCENT2_800("system_accent2_800", 31),
    SYSTEM_ACCENT2_900("system_accent2_900", 31),
    SYSTEM_ACCENT2_1000("system_accent2_1000", 31),
    
    SYSTEM_ACCENT3_0("system_accent3_0", 31),
    SYSTEM_ACCENT3_10("system_accent3_10", 31),
    SYSTEM_ACCENT3_50("system_accent3_50", 31),
    SYSTEM_ACCENT3_100("system_accent3_100", 31),
    SYSTEM_ACCENT3_200("system_accent3_200", 31),
    SYSTEM_ACCENT3_300("system_accent3_300", 31),
    SYSTEM_ACCENT3_400("system_accent3_400", 31),
    SYSTEM_ACCENT3_500("system_accent3_500", 31),
    SYSTEM_ACCENT3_600("system_accent3_600", 31),
    SYSTEM_ACCENT3_700("system_accent3_700", 31),
    SYSTEM_ACCENT3_800("system_accent3_800", 31),
    SYSTEM_ACCENT3_900("system_accent3_900", 31),
    SYSTEM_ACCENT3_1000("system_accent3_1000", 31),
    
    // Base colors for SDK 34+ (without light/dark suffix)
    SYSTEM_PRIMARY("system_primary", 34),
    SYSTEM_ON_PRIMARY("system_on_primary", 34),
    SYSTEM_SECONDARY_CONTAINER("system_secondary_container", 34),

    // Light/Dark suffix for SDK 34+
    SYSTEM_PRIMARY_LIGHT("system_primary_light", 34),
    SYSTEM_PRIMARY_DARK("system_primary_dark", 34),
    SYSTEM_ON_PRIMARY_LIGHT("system_on_primary_light", 34),
    SYSTEM_ON_PRIMARY_DARK("system_on_primary_dark", 34),
    SYSTEM_SECONDARY_CONTAINER_LIGHT("system_secondary_container_light", 34),
    SYSTEM_SECONDARY_CONTAINER_DARK("system_secondary_container_dark", 34),
    SYSTEM_ON_SURFACE_VARIANT_LIGHT("system_on_surface_variant_light", 34),
    SYSTEM_ON_SURFACE_VARIANT_DARK("system_on_surface_variant_dark", 34),
    SYSTEM_SURFACE_CONTAINER_LIGHT("system_surface_container_light", 34),
    SYSTEM_SURFACE_CONTAINER_DARK("system_surface_container_dark", 34),
    SYSTEM_SURFACE_CONTAINER_HIGH_LIGHT("system_surface_container_high_light", 34),
    SYSTEM_SURFACE_CONTAINER_HIGH_DARK("system_surface_container_high_dark", 34),
    SYSTEM_SURFACE_CONTAINER_HIGHEST_LIGHT("system_surface_container_highest_light", 34),
    SYSTEM_SURFACE_CONTAINER_HIGHEST_DARK("system_surface_container_highest_dark", 34),
    SYSTEM_SURFACE_BRIGHT_LIGHT("system_surface_bright_light", 34),
    SYSTEM_SURFACE_BRIGHT_DARK("system_surface_bright_dark", 34),
    SYSTEM_ON_SURFACE_LIGHT("system_on_surface_light", 34),
    SYSTEM_ON_SURFACE_DARK("system_on_surface_dark", 34);
    
    companion object {
        /**
         * 获取当前 SDK 支持的所有颜色资源
         */
        fun getAvailableForSdk(sdkVersion: Int): List<SystemColorResourceId> {
            return entries.filter { it.minSdk <= sdkVersion }
        }
        
        /**
         * 从资源名称创建 SystemColorResourceId
         */
        fun fromResourceName(name: String): SystemColorResourceId? {
            return entries.find { it.resourceId == name }
        }
    }
}

/**
 * Monet 主题颜色映射
 * 将 Theme 的 21 种颜色属性映射到系统动态颜色资源
 */
@Serializable
data class MonetThemeMapping(
    val backgroundColor: SystemColorResourceId,
    val barColor: SystemColorResourceId,
    val keyboardColor: SystemColorResourceId,
    val keyBackgroundColor: SystemColorResourceId,
    val keyTextColor: SystemColorResourceId,
    val candidateTextColor: SystemColorResourceId,
    val candidateLabelColor: SystemColorResourceId,
    val candidateCommentColor: SystemColorResourceId,
    val altKeyBackgroundColor: SystemColorResourceId,
    val altKeyTextColor: SystemColorResourceId,
    val accentKeyBackgroundColor: SystemColorResourceId,
    val accentKeyTextColor: SystemColorResourceId,
    val keyPressHighlightColor: SystemColorResourceId,
    val keyShadowColor: SystemColorResourceId,
    val popupBackgroundColor: SystemColorResourceId,
    val popupTextColor: SystemColorResourceId,
    val spaceBarColor: SystemColorResourceId,
    val dividerColor: SystemColorResourceId,
    val clipboardEntryColor: SystemColorResourceId,
    val genericActiveBackgroundColor: SystemColorResourceId,
    val genericActiveForegroundColor: SystemColorResourceId
) {
    companion object {
        /**
         * 创建默认的 Monet 映射（与原有 ThemeMonet 行为一致）
         */
        fun createDefault(isDark: Boolean): MonetThemeMapping {
            // 根据 SDK 版本选择合适的默认映射
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // SDK 34+ 使用明确 light/dark 资源，避免受当前系统深浅色模式影响
                if (isDark) {
                    MonetThemeMapping(
                        backgroundColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_DARK,
                        barColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_HIGH_DARK,
                        keyboardColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_DARK,
                        keyBackgroundColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_DARK,
                        keyTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_DARK,
                        candidateTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_DARK,
                        candidateLabelColor = SystemColorResourceId.SYSTEM_ON_SURFACE_DARK,
                        candidateCommentColor = SystemColorResourceId.SYSTEM_ON_SURFACE_VARIANT_DARK,
                        altKeyBackgroundColor = SystemColorResourceId.SYSTEM_SECONDARY_CONTAINER_DARK,
                        altKeyTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_VARIANT_DARK,
                        accentKeyBackgroundColor = SystemColorResourceId.SYSTEM_PRIMARY_DARK,
                        accentKeyTextColor = SystemColorResourceId.SYSTEM_ON_PRIMARY_DARK,
                        keyPressHighlightColor = SystemColorResourceId.SYSTEM_ON_SURFACE_DARK,
                        keyShadowColor = SystemColorResourceId.SYSTEM_NEUTRAL1_0,
                        popupBackgroundColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_DARK,
                        popupTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_DARK,
                        spaceBarColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_DARK,
                        dividerColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_DARK,
                        clipboardEntryColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_DARK,
                        genericActiveBackgroundColor = SystemColorResourceId.SYSTEM_PRIMARY_DARK,
                        genericActiveForegroundColor = SystemColorResourceId.SYSTEM_ON_PRIMARY_DARK
                    )
                } else {
                    MonetThemeMapping(
                        backgroundColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_LIGHT,
                        barColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_HIGHEST_LIGHT,
                        keyboardColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_LIGHT,
                        keyBackgroundColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_LIGHT,
                        keyTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_LIGHT,
                        candidateTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_LIGHT,
                        candidateLabelColor = SystemColorResourceId.SYSTEM_ON_SURFACE_LIGHT,
                        candidateCommentColor = SystemColorResourceId.SYSTEM_ON_SURFACE_VARIANT_LIGHT,
                        altKeyBackgroundColor = SystemColorResourceId.SYSTEM_SECONDARY_CONTAINER_LIGHT,
                        altKeyTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_VARIANT_LIGHT,
                        accentKeyBackgroundColor = SystemColorResourceId.SYSTEM_PRIMARY_LIGHT,
                        accentKeyTextColor = SystemColorResourceId.SYSTEM_ON_PRIMARY_LIGHT,
                        keyPressHighlightColor = SystemColorResourceId.SYSTEM_ON_SURFACE_LIGHT,
                        keyShadowColor = SystemColorResourceId.SYSTEM_NEUTRAL1_0,
                        popupBackgroundColor = SystemColorResourceId.SYSTEM_SURFACE_CONTAINER_LIGHT,
                        popupTextColor = SystemColorResourceId.SYSTEM_ON_SURFACE_LIGHT,
                        spaceBarColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_LIGHT,
                        dividerColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_LIGHT,
                        clipboardEntryColor = SystemColorResourceId.SYSTEM_SURFACE_BRIGHT_LIGHT,
                        genericActiveBackgroundColor = SystemColorResourceId.SYSTEM_PRIMARY_LIGHT,
                        genericActiveForegroundColor = SystemColorResourceId.SYSTEM_ON_PRIMARY_LIGHT
                    )
                }
            } else {
                // SDK 31-33 使用近似颜色
                MonetThemeMapping(
                    backgroundColor = SystemColorResourceId.SYSTEM_NEUTRAL1_50,
                    barColor = SystemColorResourceId.SYSTEM_NEUTRAL2_100,
                    keyboardColor = SystemColorResourceId.SYSTEM_NEUTRAL1_50,
                    keyBackgroundColor = SystemColorResourceId.SYSTEM_NEUTRAL1_10,
                    keyTextColor = SystemColorResourceId.SYSTEM_NEUTRAL1_900,
                    candidateTextColor = SystemColorResourceId.SYSTEM_NEUTRAL1_900,
                    candidateLabelColor = SystemColorResourceId.SYSTEM_NEUTRAL1_900,
                    candidateCommentColor = SystemColorResourceId.SYSTEM_ACCENT2_700,
                    altKeyBackgroundColor = SystemColorResourceId.SYSTEM_ACCENT2_100,
                    altKeyTextColor = SystemColorResourceId.SYSTEM_ACCENT2_700,
                    accentKeyBackgroundColor = SystemColorResourceId.SYSTEM_ACCENT1_600,
                    accentKeyTextColor = SystemColorResourceId.SYSTEM_ACCENT1_0,
                    keyPressHighlightColor = SystemColorResourceId.SYSTEM_NEUTRAL1_900,
                    keyShadowColor = SystemColorResourceId.SYSTEM_NEUTRAL1_0,
                    popupBackgroundColor = SystemColorResourceId.SYSTEM_NEUTRAL1_50,
                    popupTextColor = SystemColorResourceId.SYSTEM_NEUTRAL1_900,
                    spaceBarColor = SystemColorResourceId.SYSTEM_NEUTRAL1_10,
                    dividerColor = SystemColorResourceId.SYSTEM_NEUTRAL1_10,
                    clipboardEntryColor = SystemColorResourceId.SYSTEM_NEUTRAL1_10,
                    genericActiveBackgroundColor = SystemColorResourceId.SYSTEM_ACCENT1_600,
                    genericActiveForegroundColor = SystemColorResourceId.SYSTEM_ACCENT1_0
                )
            }
        }
    }
}
