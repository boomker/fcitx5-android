/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.preview

import timber.log.Timber

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.config.ConfigProvider
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.MemoryConfigProvider
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import splitties.dimensions.dp
import java.io.File

internal fun resolvePreviewRows(
    entries: Map<String, List<List<Map<String, Any?>>>>,
    layoutName: String,
    subModeKey: String?,
    fallbackRows: List<List<Map<String, Any?>>>? = null
): List<List<Map<String, Any?>>>? = subModeKey?.let { entries[it] }
    ?: entries[layoutName]
    // 双拼方案回退到“双拼”父级输入法布局
    ?: ShuangpinLayoutKeys.parentBaseLayoutKey(layoutName)?.let { entries[it] }
    // 未定制专属布局的输入法回退到出厂 26 键布局，
    // 而非可被用户定制的 English（default）布局，避免布局污染
    ?: fallbackRows

/** Match runtime inheritance without letting one orientation mask the other. */
internal fun resolvePreviewHeightOverrides(
    entries: Map<String, List<List<Map<String, Any?>>>>,
    layoutName: String,
    subModeLabel: String?,
    heightProvider: (String) -> LayoutHeightPercentOverrides?,
    profileHeight: LayoutHeightPercentOverrides? = null
): LayoutHeightPercentOverrides {
    val child = subModeLabel?.takeIf { it.isNotEmpty() }
        ?.let { "$layoutName:$it" }
        ?.takeIf { it in entries }
        ?.let(heightProvider)
    val base = layoutName.takeIf { it in entries }?.let(heightProvider)
    // 双拼方案未存专属布局时，沿父级“双拼”父级输入法布局继承高度
    val parent = if (layoutName !in entries) {
        ShuangpinLayoutKeys.parentBaseLayoutKey(layoutName)
            ?.takeIf { it in entries }
            ?.let(heightProvider)
    } else null
    // An explicit base opts out of global-default layout metadata, even if it
    // does not override height. Only virtual parents / child-only IMEs inherit it.
    val default = if (layoutName !in entries && parent == null) {
        LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY.takeIf { it in entries }?.let(heightProvider)
    } else null
    return LayoutHeightPercentOverrides(
        portrait = child?.portrait ?: base?.portrait ?: parent?.portrait
            ?: default?.portrait ?: profileHeight?.portrait,
        landscape = child?.landscape ?: base?.landscape ?: parent?.landscape
            ?: default?.landscape ?: profileHeight?.landscape
    )
}

internal fun buildPreviewSubModeMap(
    entries: Map<String, List<List<Map<String, Any?>>>>,
    layoutName: String,
    subModeKey: String?,
    currentRows: List<List<Map<String, Any?>>>,
    previewSubModeLabel: String?
): MutableMap<String, JsonElement> {
    val subModeMap = mutableMapOf<String, JsonElement>()
    val currentRowsArray = JsonArray(
        previewRowsUsingMacroLabels(currentRows).map(LayoutJsonUtils::rowToJsonElement)
    )

    if (subModeKey != null && entries.containsKey(subModeKey)) {
        subModeMap[previewSubModeLabel ?: "default"] = currentRowsArray
        val defaultRows = entries[layoutName]
            ?: ShuangpinLayoutKeys.parentBaseLayoutKey(layoutName)?.let { entries[it] }
            ?: entries[LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY]
        defaultRows?.let {
            subModeMap["default"] = JsonArray(
                previewRowsUsingMacroLabels(it).map(LayoutJsonUtils::rowToJsonElement)
            )
        }
    } else {
        subModeMap["default"] = currentRowsArray
    }

    return subModeMap
}

private fun previewRowsUsingMacroLabels(
    rows: List<List<Map<String, Any?>>>
): List<List<Map<String, Any?>>> = rows.map { row ->
    row.map { key ->
        val label = key["label"] as? String
        if (key["type"] != "MacroKey" || label == null) {
            key
        } else {
            key.toMutableMap().apply { this["displayText"] = label }
        }
    }
}

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
    private val entries: Map<String, List<List<Map<String, Any?>>>>,
    private val layoutHeightPercentProvider: (String) -> LayoutHeightPercentOverrides? = { null }
) {
    // Separate profile fallback from per-entry metadata so it cannot mask base
    // or default heights. Keep the existing constructor/callback contract intact.
    internal var profileHeightPercentProvider: () -> LayoutHeightPercentOverrides? = { null }

    /**
     * 未定制专属布局时的出厂 26 键回退行（预览用），避免回退到可被用户
     * 定制的 English（default）布局造成布局污染。
     */
    internal var fallbackRowsProvider: () -> List<List<Map<String, Any?>>>? = { null }

    private var previewKeyboard: TextKeyboard? = null
    // 单层预览图层：背景、模糊遮罩、键盘都放进这个固定高度的 FrameLayout，
    // 无论外层容器是纵向 LinearLayout 还是 FrameLayout，预览高度都只等于一个键盘高度。
    private var previewFrame: FrameLayout? = null
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
        // 只移除本管理器创建的预览图层，不清空外层容器上可能存在的其他视图/背景。
        detachPreviewFrame()

        // Try to load submode-specific layout first
        val subModeKey = previewSubModeLabel?.let { "$layoutName:$it" }
        val rows = resolvePreviewRows(entries, layoutName, subModeKey, fallbackRowsProvider()) ?: return

        previewBlurMask.bindKeyboard(null)

        // Build submode map with all available submodes for this layout
        val subModeMap = buildSubModeMap(layoutName, subModeKey, rows, previewSubModeLabel)

        val tempJson = JsonObject(mapOf(layoutName to JsonObject(subModeMap)))

        // Temporarily replace the layout file and reload
        val provider = ConfigProviders.provider
        val tempProvider = PreviewConfigProvider(tempJson, provider)

        ConfigProviders.provider = tempProvider
        TextKeyboard.clearCachedKeyDefLayouts()

        try {
            createKeyboardPreview(layoutName, previewSubModeLabel, fcitxConnection)
        } catch (e: Exception) {
            Timber.tag("KeyboardPreview").e(e, "Failed to create keyboard preview for layout: $layoutName, submode: $previewSubModeLabel")
            showError(e.message ?: "Unknown error")
        } finally {
            // Restore the provider that was active before this preview. The editor
            // can be opened for a non-default profile, and replacing it with the
            // default provider here makes subsequent saves/readbacks target the
            // wrong layout source.
            ConfigProviders.provider = provider
            TextKeyboard.clearCachedKeyDefLayouts()
        }
    }

    /**
     * 实时更新预览键盘高度（不重建键盘，仅调整布局参数），用于高度滑杆拖动时的即时预览。
     *
     * @param heightPercent 键盘高度百分比（10-90）
     */
    fun updatePreviewHeight(heightPercent: Int) {
        val keyboard = previewKeyboard ?: return
        val frame = previewFrame ?: return
        val height = context.resources.displayMetrics.heightPixels *
            heightPercent.coerceIn(10, 90) / 100
        // 仅调整外层图层高度即可，模糊遮罩与键盘均为 MATCH_PARENT，会随之更新。
        frame.layoutParams?.height = height
        frame.requestLayout()
        keyboard.post { previewBlurMask.refreshMask(hierarchyChanged = true) }
    }

    /**
     * Build submode map for temporary JSON file.
     */
    private fun buildSubModeMap(
        layoutName: String,
        subModeKey: String?,
        currentRows: List<List<Map<String, Any?>>>,
        previewSubModeLabel: String?
    ): MutableMap<String, JsonElement> = buildPreviewSubModeMap(
        entries,
        layoutName,
        subModeKey,
        currentRows,
        previewSubModeLabel
    )

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

        previewKeyboard = TextKeyboard(context, theme).apply {
            // 预览中的字母键保持大写，与下方编辑器的按键标签视觉一致
            keepLettersUppercaseOverride = true
            preserveMacroDisplayCaseOverride = true
            val displayMetrics = context.resources.displayMetrics
            val screenHeight = displayMetrics.heightPixels

            val keyboardPrefs = AppPrefs.getInstance().keyboard
            val isLandscape = context.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            // 编辑专属子模式布局时优先取该子模式的高度覆写，未配置则回退父级输入法布局
            val layoutHeightOverride = resolvePreviewHeightOverrides(
                entries, layoutName, previewSubModeLabel,
                layoutHeightPercentProvider, profileHeightPercentProvider()
            )
            val heightPercent = if (isLandscape) {
                layoutHeightOverride.landscape
                    ?: keyboardPrefs.keyboardHeightPercentLandscape.getValue()
            } else {
                layoutHeightOverride.portrait
                    ?: keyboardPrefs.keyboardHeightPercent.getValue()
            }
            val keyboardHeight = screenHeight * heightPercent / 100

            // 构建固定高度的单层预览：背景绘制在这一层，模糊遮罩与键盘在其上重叠。
            // 这样背景仅覆盖键盘盘面本身，避免在纵向 LinearLayout 容器中因子视图堆叠
            // 而导致背景图上下溢出。
            val frame = FrameLayout(context).apply {
                background = theme.backgroundDrawable(keyBorder)
            }
            // previewBlurMask 是复用视图，重建前先从旧父容器摘除，避免重复添加异常。
            (previewBlurMask.parent as? ViewGroup)?.removeView(previewBlurMask)
            frame.addView(
                previewBlurMask,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            frame.addView(
                this,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            previewContainer.addView(
                frame,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    keyboardHeight
                )
            )
            previewFrame = frame

            onAttach()

            // Get current IME and create preview IME
            val currentIme = runCatching {
                fcitxConnection.runImmediately { inputMethodEntryCached }
            }.getOrNull()

            val previewIme = PreviewInputMethodEntry.create(
                layoutName = layoutName,
                subModeLabel = previewSubModeLabel,
                base = currentIme,
                // 空格等位置展示输入法名："default" 布局显示为 "English"
                displayName = LayoutJsonUtils.displayBaseLayoutName(layoutName)
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
        detachPreviewFrame()
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
        detachPreviewFrame()
    }

    /**
     * 从外层容器移除本管理器创建的预览图层，并复位内部引用。
     * previewBlurMask 为复用视图，会一并从图层中摘除以便下次重新添加。
     */
    private fun detachPreviewFrame() {
        (previewBlurMask.parent as? ViewGroup)?.removeView(previewBlurMask)
        previewFrame?.let {
            it.removeAllViews()
            previewContainer.removeView(it)
        }
        previewFrame = null
        previewKeyboard = null
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
