/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.DefaultConfigProvider
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp
import java.io.File

/**
 * 键盘预览管理器，负责键盘布局的预览功能。
 * 
 * 主要功能：
 * - [updatePreview] - 更新键盘预览
 * - [clear] - 清除预览键盘
 * 
 * 工作原理：
 * 1. 创建临时 JSON 文件存储当前布局
 * 2. 临时替换 ConfigProvider 指向临时文件
 * 3. 加载 TextKeyboard 进行预览
 * 4. 恢复原始 ConfigProvider 并删除临时文件
 * 
 * 使用示例：
 * ```kotlin
 * val previewManager = KeyboardPreviewManager(context, container, entries)
 * previewManager.updatePreview(layoutName, subModeLabel, fcitxConnection)
 * ```
 */
class KeyboardPreviewManager(
    private val context: Context,
    private val previewContainer: ViewGroup,
    private val entries: Map<String, List<List<Map<String, Any?>>>>
) {
    private var previewKeyboard: TextKeyboard? = null

    /**
     * 更新键盘预览。
     *
     * @param layoutName 布局名称
     * @param previewSubModeLabel 子模式标签，null 表示默认
     * @param fcitxConnection Fcitx 连接，用于获取当前输入法
     */
    fun updatePreview(
        layoutName: String,
        previewSubModeLabel: String?,
        fcitxConnection: FcitxConnection
    ) {
        previewContainer.removeAllViews()

        // Try to load submode-specific layout first
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
        val rows = subModeKey?.let { entries[it] } ?: entries[layoutName] ?: return

        // Remove old keyboard view
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }

        // Create a temporary layout file for preview
        val tempFile = File(context.cacheDir, "temp_layout.json")

        // Build submode map with all available submodes for this layout
        val subModeMap = buildSubModeMap(layoutName, subModeKey, rows, previewSubModeLabel)

        val tempJson = JsonObject(mapOf(layoutName to JsonObject(subModeMap)))
        tempFile.writeText(Json.encodeToString(tempJson))

        // Temporarily replace the layout file and reload
        val provider = ConfigProviders.provider
        val tempProvider = PreviewConfigProvider(tempFile, provider)

        ConfigProviders.provider = tempProvider
        TextKeyboard.clearCachedKeyDefLayouts()

        // Save the original IME state to restore later
        val originalIme = TextKeyboard.ime

        try {
            createKeyboardPreview(layoutName, previewSubModeLabel, fcitxConnection)
        } catch (e: Exception) {
            showError(e.message ?: "Unknown error")
        } finally {
            // Restore original provider and IME state
            ConfigProviders.provider = DefaultConfigProvider
            TextKeyboard.clearCachedKeyDefLayouts()
            TextKeyboard.ime = originalIme
            tempFile.delete()
        }
    }

    /**
     * Build submode map for temporary JSON file.
     */
    private fun buildSubModeMap(
        layoutName: String,
        subModeKey: String?,
        currentRows: List<List<Map<String, Any?>>>,
        previewSubModeLabel: String?
    ): MutableMap<String, JsonElement> {
        val subModeMap = mutableMapOf<String, JsonElement>()

        val currentRowsArray = JsonArray(currentRows.map { row ->
            JsonArray(row.map { key ->
                JsonObject(key.entries.associate { (k, v) ->
                    k to LayoutJsonUtils.convertToJsonProperty(v)
                })
            })
        })

        if (subModeKey != null && entries.containsKey(subModeKey)) {
            // Editing a submode layout - add it with its label
            subModeMap[previewSubModeLabel ?: "default"] = currentRowsArray
            // Also add default layout if it exists (for fallback)
            val defaultRows = entries[layoutName]
            if (defaultRows != null) {
                val defaultRowsArray = JsonArray(defaultRows.map { row ->
                    JsonArray(row.map { key ->
                        JsonObject(key.entries.associate { (k, v) ->
                            k to LayoutJsonUtils.convertToJsonProperty(v)
                        })
                    })
                })
                subModeMap["default"] = defaultRowsArray
            }
        } else {
            // Editing default layout
            subModeMap["default"] = currentRowsArray
        }

        return subModeMap
    }

    /**
     * Create keyboard preview view.
     */
    private fun createKeyboardPreview(
        layoutName: String,
        previewSubModeLabel: String?,
        fcitxConnection: FcitxConnection
    ) {
        val theme = ThemeManager.activeTheme
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()

        // Set preview container background color to match theme
        previewContainer.setBackgroundColor(
            if (keyBorder) theme.backgroundColor else theme.keyboardColor
        )

        previewKeyboard = TextKeyboard(context, theme).apply {
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            // Get keyboard height percentage from preferences
            val keyboardPrefs = AppPrefs.getInstance().keyboard
            val heightPercent = keyboardPrefs.keyboardHeightPercent.getValue()
            val keyboardHeight = (screenHeight * heightPercent / 100).toInt()

            // Get keyboard side and bottom padding from preferences
            val sidePadding = keyboardPrefs.keyboardSidePadding.getValue()
            val bottomPadding = keyboardPrefs.keyboardBottomPadding.getValue()
            val sidePaddingPx = (sidePadding * displayMetrics.density).toInt()
            val bottomPaddingPx = (bottomPadding * displayMetrics.density).toInt()

            val layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeight
            )
            previewContainer.addView(this, layoutParams)

            onAttach()

            // Get current IME and create preview IME
            val currentIme = runCatching {
                fcitxConnection.runImmediately { inputMethodEntryCached }
            }.getOrNull()

            val selectedSubModeLabel = previewSubModeLabel?.trim().orEmpty()
            val previewIme = currentIme
                ?.copy(
                    uniqueName = layoutName,
                    name = layoutName,
                    subMode = if (selectedSubModeLabel.isNotEmpty()) {
                        currentIme.subMode.copy(
                            label = selectedSubModeLabel,
                            name = selectedSubModeLabel
                        )
                    } else {
                        currentIme.subMode.copy(
                            name = "",
                            label = "",
                            icon = ""
                        )
                    }
                )
                ?: InputMethodEntry(layoutName)

            onInputMethodUpdate(previewIme)
            setTextScale(1.0f)
            refreshStyle()
            requestLayout()
            invalidate()
        }
    }

    /**
     * Show error message in preview container.
     */
    private fun showError(message: String) {
        previewContainer.removeAllViews()
        val errorText = TextView(context).apply {
            text = context.getString(R.string.text_keyboard_layout_preview_error, message)
            textSize = 12f
            setTextColor(Color.RED)
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        }
        previewContainer.addView(errorText)
    }

    /**
     * 清除预览键盘。
     */
    fun clear() {
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }
    }

    /**
     * Temporary config provider for preview.
     */
    private class PreviewConfigProvider(
        private val tempFile: File,
        private val delegate: ConfigProvider
    ) : ConfigProvider {
        override fun textKeyboardLayoutFile(): File? = tempFile
        override fun popupPresetFile(): File? = delegate.popupPresetFile()
        override fun fontsetFile(): File? = delegate.fontsetFile()
        override fun buttonsLayoutConfigFile(): File? = delegate.buttonsLayoutConfigFile()
        override fun writeFontsetPathMap(pathMap: Map<String, List<String>>): Result<File> =
            delegate.writeFontsetPathMap(pathMap)
    }
}

/**
 * Extension function to convert dp to pixels.
 */
private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
