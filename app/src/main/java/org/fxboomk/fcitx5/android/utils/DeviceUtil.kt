/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2023 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.utils

import android.os.Build

object DeviceUtil {

    val isMIUI: Boolean by lazy {
        getSystemProperty("ro.miui.ui.version.name").isNotEmpty()
    }

    /**
     * https://www.cnblogs.com/qixingchao/p/15899405.html
     * 增强对早期鸿蒙（Android 9 base）设备的识别
     */
    val isHMOS: Boolean by lazy {
        val hwScVersion = getSystemProperty("hw_sc.build.platform.version")
        val harmonyVersion = getSystemProperty("hw_sc.build.os.version")
        val display = Build.DISPLAY.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        val emuiVersion = getSystemProperty("ro.build.version.emui").lowercase()
        val release = getSystemProperty("ro.build.version.release").lowercase()
        val isHuaweiFamily = manufacturer.contains("huawei") || manufacturer.contains("honor") || brand.contains("huawei") || brand.contains("honor")
        val keywords = listOf("harmony", "ark", "hongmeng")
        val hasKeyword = keywords.any { k -> display.contains(k) || emuiVersion.contains(k) || model.contains(k) }
        hwScVersion.isNotEmpty() ||
            harmonyVersion.isNotEmpty() ||
            (isHuaweiFamily && (display.contains("harmony") || emuiVersion.contains("harmony"))) ||
            // 兼容早期 Android 9 base 鸿蒙
            (isHuaweiFamily && release == "9" && hasKeyword)
    }

    /**
     * https://stackoverflow.com/questions/60122037/how-can-i-detect-samsung-one-ui
     */
    val isSamsungOneUI: Boolean by lazy {
        try {
            val semPlatformInt = Build.VERSION::class.java
                .getDeclaredField("SEM_PLATFORM_INT")
                .getInt(null)
            semPlatformInt > 90000
        } catch (e: Exception) {
            false
        }
    }

    val isVivoOriginOS: Boolean by lazy {
        getSystemProperty("ro.vivo.os.version").isNotEmpty()
    }

    val isHonorMagicOS: Boolean by lazy {
        getSystemProperty("ro.magic.systemversion").isNotEmpty()
    }

    val isFlyme: Boolean by lazy {
        Build.DISPLAY.lowercase().contains("flyme")
    }

}
