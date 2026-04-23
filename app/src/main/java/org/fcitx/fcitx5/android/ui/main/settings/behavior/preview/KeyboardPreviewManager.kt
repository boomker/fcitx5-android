/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.DefaultConfigProvider
import org.fcitx.fcitx5.android.input.config.MemoryConfigProvider
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp
import java.io.File

/**
 * Keyboard preview manager, responsible for previewing keyboard layouts.
 *
 * Main functions:
 * - [updatePreview] - Update keyboard preview
 * - [clear] - Clear preview keyboard
 *
 * How it works:
 * 1. Build in-memory JSON to store current layout
 * 2. Temporarily replace ConfigProvider with PreviewConfigProvider (provides in-memory JSON)
 * 3. Load TextKeyboard for preview (reads from in-memory JSON, no disk I/O)
 * 4. Restore original ConfigProvider
 *
 * Usage example:
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
    private val previewBlurMask by lazy { PreviewKeyBlurMaskView(context) }

    /**
     * Update keyboard preview.
     *
     * @param layoutName Layout name
     * @param previewSubModeLabel Submode label, null for default
     * @param fcitxConnection Fcitx connection for getting current input method
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

        val theme = ThemeManager.activeTheme
        val keyBorder = ThemeManager.prefs.keyBorder.getValue()
        previewContainer.background = theme.backgroundDrawable(keyBorder)
        previewBlurMask.bindKeyboard(null)

        // Remove old keyboard view
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }

        // Build submode map with all available submodes for this layout
        val subModeMap = buildSubModeMap(layoutName, subModeKey, rows, previewSubModeLabel)

        val tempJson = JsonObject(mapOf(layoutName to JsonObject(subModeMap)))

        // Temporarily replace the layout file and reload
        val provider = ConfigProviders.provider
        val tempProvider = PreviewConfigProvider(tempJson, provider)

        ConfigProviders.provider = tempProvider
        TextKeyboard.clearCachedKeyDefLayouts()

        // Save the original IME state to restore later
        val originalIme = TextKeyboard.ime

        try {
            createKeyboardPreview(layoutName, previewSubModeLabel, fcitxConnection)
        } catch (e: Exception) {
            android.util.Log.e("KeyboardPreview", "Failed to create keyboard preview for layout: $layoutName, submode: $previewSubModeLabel", e)
            showError(e.message ?: "Unknown error")
        } finally {
            // Restore original provider and IME state
            ConfigProviders.provider = DefaultConfigProvider
            TextKeyboard.clearCachedKeyDefLayouts()
            TextKeyboard.ime = originalIme
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

        previewKeyboard = TextKeyboard(context, theme).apply {
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            // Get keyboard height percentage from preferences
            val keyboardPrefs = AppPrefs.getInstance().keyboard
            val heightPercent = keyboardPrefs.keyboardHeightPercent.getValue()
            val keyboardHeight = screenHeight * heightPercent / 100

            // Get keyboard side and bottom padding from preferences
            val sidePadding = keyboardPrefs.keyboardSidePadding.getValue()
            val bottomPadding = keyboardPrefs.keyboardBottomPadding.getValue()
            val sidePaddingPx = (sidePadding * displayMetrics.density).toInt()
            val bottomPaddingPx = (bottomPadding * displayMetrics.density).toInt()

            val layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                keyboardHeight
            )
            previewContainer.addView(
                previewBlurMask,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    keyboardHeight
                )
            )
            previewContainer.addView(this, layoutParams)

            onAttach()

            // Get current IME and create preview IME
            val currentIme = runCatching {
                fcitxConnection.runImmediately { inputMethodEntryCached }
            }.getOrNull()

            val previewIme = PreviewInputMethodEntry.create(
                layoutName = layoutName,
                subModeLabel = previewSubModeLabel,
                base = currentIme
            )

            onInputMethodUpdate(previewIme)
            setTextScale(1.0f)
            refreshStyle()
            previewBlurMask.applyTheme(theme, ThemeManager.prefs.keyBorder.getValue())
            previewBlurMask.bindKeyboard(this)
            post { previewBlurMask.refreshMask(hierarchyChanged = true) }
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
     * Clear preview keyboard.
     */
    fun clear() {
        previewBlurMask.bindKeyboard(null)
        previewKeyboard?.let {
            previewContainer.removeView(it)
            previewKeyboard = null
        }
    }

    /**
     * Get preview keyboard as bitmap.
     * @return Bitmap of the preview keyboard, or null if no preview is available
     */
    fun getPreviewBitmap(): Bitmap? {
        val keyboard = previewKeyboard ?: return null

        val targetView = if (previewContainer.width > 0 && previewContainer.height > 0) {
            previewContainer
        } else {
            keyboard
        }
        val width = targetView.width
        val height = targetView.height
        if (width <= 0 || height <= 0) return null

        // Directly render the current view tree into bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        targetView.draw(canvas)

        return bitmap
    }

    /**
     * Temporary config provider for preview using in-memory JSON.
     */
    private class PreviewConfigProvider(
        private val tempJson: JsonObject,
        private val delegate: ConfigProvider
    ) : ConfigProvider {
        override fun textKeyboardLayoutFile(): File? = null
        override fun textKeyboardLayoutJson(): JsonObject = tempJson
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
