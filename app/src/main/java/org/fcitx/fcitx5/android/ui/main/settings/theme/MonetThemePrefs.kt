/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.MonetThemeMapping
import org.fcitx.fcitx5.android.utils.appContext

/**
 * Monet 主题映射偏好设置存储
 * 用于保存用户对 Monet 主题颜色映射的自定义配置
 */
object MonetThemePrefs {
    
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    /**
     * 获取指定主题名称的映射配置
     * @param themeName 主题名称（如 "MonetLight", "MonetDark"）
     * @return 映射配置，如果不存在则返回 null
     */
    fun getMapping(themeName: String): MonetThemeMapping? {
        val jsonStr = prefs.getString(getKeyForTheme(themeName), null) ?: return null
        return try {
            json.decodeFromString<MonetThemeMapping>(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 保存主题映射配置
     * @param themeName 主题名称
     * @param mapping 映射配置
     */
    fun saveMapping(themeName: String, mapping: MonetThemeMapping) {
        val jsonStr = json.encodeToString(mapping)
        prefs.edit().putString(getKeyForTheme(themeName), jsonStr).apply()
    }
    
    /**
     * 删除主题映射配置
     * @param themeName 主题名称
     */
    fun deleteMapping(themeName: String) {
        prefs.edit().remove(getKeyForTheme(themeName)).apply()
    }
    
    /**
     * 检查是否存在指定主题的映射配置
     */
    fun hasMapping(themeName: String): Boolean {
        return prefs.contains(getKeyForTheme(themeName))
    }
    
    /**
     * 获取所有已保存映射配置的主题名称
     */
    fun getAllMappedThemeNames(): Set<String> {
        return prefs.all.keys.mapNotNull { key ->
            if (key.startsWith(KEY_PREFIX)) {
                key.removePrefix(KEY_PREFIX)
            } else {
                null
            }
        }.toSet()
    }
    
    /**
     * 清除所有映射配置
     */
    fun clearAllMappings() {
        prefs.edit().clear().apply()
    }
    
    private fun getKeyForTheme(themeName: String): String {
        return "$KEY_PREFIX$themeName"
    }
    
    private const val PREFS_NAME = "monet_theme_mappings"
    private const val KEY_PREFIX = "mapping_"
}
