/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.manager

import kotlinx.serialization.json.JsonObject
import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.data.DataManager
import org.fxboomk.fcitx5.android.daemon.FcitxConnection

/**
 * SubMode manager for handling keyboard layout submode-related logic.
 *
 * Features:
 * - Extract submode labels from layout data
 * - Extract submode labels from Fcitx status area menu
 * - Resolve current input method state and available submode list
 */
class SubModeManager(
    private val fcitxConnection: FcitxConnection,
    private val allImesProvider: () -> Array<InputMethodEntry>,
    private val entries: Map<String, List<List<Map<String, Any?>>>>
) {

    private val allImesFromJson: Array<InputMethodEntry>
        get() = allImesProvider()

    companion object {
        /** 中州韵插件是否已随当前应用加载（插件按签名与构建类型配对，debug 应用只配 .debug 插件） */
        fun isRimePluginLoaded(): Boolean =
            DataManager.getLoadedPlugins().any { it.name == "rime" }
    }

    /**
     * SubMode state data class
     *
     * @property currentIme Current input method entry
     * @property labels List of all available submode labels
     */
    data class SubModeState(
        val currentIme: InputMethodEntry?,
        val labels: List<String>
    )

    /**
     * Extract submode labels from layout data
     *
     * @param layoutName Layout name
     * @return List of submode labels
     */
    fun extractSubModeLabelsFromLayout(layoutName: String): List<String> {
        // Collect from submode layout keys
        val labels = linkedSetOf<String>()

        entries.keys.forEach { key ->
            if (key.startsWith("$layoutName:")) {
                val subModeLabel = key.substringAfter("$layoutName:")
                if (subModeLabel.isNotEmpty() && subModeLabel != "default") {
                    labels.add(subModeLabel)
                }
            }
        }

        // 从 displayText 收集
        val rows = entries[layoutName] ?: return labels.toList()

        rows.forEach { row ->
            row.forEach { key ->
                when (val displayText = key["displayText"]) {
                    is JsonObject -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode.trim()
                            if (normalized.isNotEmpty() && normalized != "default") {
                                labels.add(normalized)
                            }
                        }
                    }
                    is Map<*, *> -> {
                        displayText.keys.forEach { mode ->
                            val normalized = mode?.toString()?.trim().orEmpty()
                            if (normalized.isNotEmpty() && normalized != "default") {
                                labels.add(normalized)
                            }
                        }
                    }
                }
            }
        }

        return labels.toList()
    }

    /**
     * Resolve submode state
     *
     * @param layoutName Layout name
     * @param layoutLabels Submode labels from layout
     * @return SubMode state
     */
    fun resolveSubModeState(layoutName: String, layoutLabels: List<String>): SubModeState {
        val (currentIme, fcitxLabels) = fetchCurrentImeAndSubModeLabels(layoutName)
        val labels = (fcitxLabels + layoutLabels)
            .map { it.trim() }
            .distinct()
            .filter { it.isNotBlank() }
        return SubModeState(currentIme, labels)
    }

    /**
     * Check if current layout is Rime input method
     *
     * @param layoutName Layout name
     * @return Whether it is Rime
     */
    fun isCurrentLayoutRime(layoutName: String): Boolean {
        val ime = allImesFromJson.firstOrNull {
            it.uniqueName == layoutName || it.displayName == layoutName
        }
        val uniqueName = ime?.uniqueName ?: layoutName
        val displayName = ime?.displayName ?: layoutName
        return uniqueName.contains("rime", ignoreCase = true) ||
            displayName.contains("rime", ignoreCase = true)
    }

    /**
     * Fetch current input method entry and submode labels
     *
     * @param layoutName Layout name
     * @return Pair(Current input method entry, submode labels list)
     */
    internal fun fetchCurrentImeAndSubModeLabels(layoutName: String): Pair<InputMethodEntry?, List<String>> {
        return runCatching {
            fcitxConnection.runImmediately {
                val targetImeUniqueName = resolveTargetImeUniqueName(layoutName)
                val rimeLabels = if (isCurrentLayoutRime(layoutName)) {
                    RimeSchemaResolver.readLabels()
                } else {
                    emptyList()
                }

                // A deployed Rime schema list is authoritative for available schemes and
                // does not require changing the user's active input method. Only preserve
                // the current label when it belongs to that list; the active IME may still
                // be an unrelated engine while the user edits a Rime layout.
                if (rimeLabels.isNotEmpty()) {
                    val ime = runCatching { currentIme() }.getOrElse {
                        android.util.Log.w("SubModeManager", "Failed to get current IME, using cached")
                        inputMethodEntryCached
                    }
                    val currentLabel = ime.subMode.label.ifEmpty { ime.subMode.name }.trim()
                    val labels = (listOf(currentLabel.takeIf { it in rimeLabels }) + rimeLabels)
                        .filterNotNull()
                        .distinct()
                    return@runImmediately ime to labels
                }

                // 读取目标输入法的方案列表必须先把它激活为 Fcitx 当前输入法，但这纯粹是为了
                // 读取状态区数据，绝不能改变用户实际正在使用的输入法。先记下进入本函数时真正
                // 处于活动状态的输入法，无论后续走哪条分支（含空结果提前返回、重试、异常），
                // 都在 finally 中还原，保证本函数对活动输入法零净改动。
                val originalImeUniqueName = runCatching { currentIme() }
                    .getOrElse { inputMethodEntryCached }
                    .uniqueName
                    .takeIf { it.isNotBlank() }

                try {
                    if (targetImeUniqueName != null) {
                        runCatching { activateIme(targetImeUniqueName) }.onFailure { e ->
                            android.util.Log.w("SubModeManager", "Failed to activate IME: $targetImeUniqueName", e)
                        }
                    }

                    val ime = runCatching { currentIme() }.getOrElse {
                        android.util.Log.w("SubModeManager", "Failed to get current IME, using cached")
                        inputMethodEntryCached
                    }
                    val currentLabel = ime.subMode.label.ifEmpty { ime.subMode.name }.trim()

                    var actions = runCatching {
                        statusArea()
                    }.onFailure { e ->
                        android.util.Log.w("SubModeManager", "Failed to get status area, using cached", e)
                    }.getOrNull() ?: statusAreaActionsCached

                    var fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)

                    if (fromStatusMenu.isEmpty() && targetImeUniqueName != null) {
                        android.util.Log.d("SubModeManager", "First attempt returned empty, retrying IME activation")
                        runCatching { activateIme(targetImeUniqueName) }.onFailure { e ->
                            android.util.Log.w("SubModeManager", "Failed to activate IME on retry: $targetImeUniqueName", e)
                        }
                        actions = runCatching {
                            statusArea()
                        }.onFailure { e ->
                            android.util.Log.w("SubModeManager", "Failed to get status area on retry", e)
                        }.getOrNull() ?: statusAreaActionsCached
                        fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)
                    }

                    if (fromStatusMenu.isEmpty()) {
                        android.util.Log.d("SubModeManager", "Second attempt returned empty, trying focusOutIn")
                        runCatching { focusOutIn() }.onFailure { e ->
                            android.util.Log.w("SubModeManager", "Failed to execute focusOutIn", e)
                        }
                        actions = runCatching {
                            statusArea()
                        }.onFailure { e ->
                            android.util.Log.w("SubModeManager", "Failed to get status area after focusOutIn", e)
                        }.getOrNull() ?: statusAreaActionsCached
                        fromStatusMenu = extractLabelsFromStatusArea(actions, currentLabel)
                    }

                    val baseLabels = when {
                        fromStatusMenu.isNotEmpty() -> fromStatusMenu
                        else -> {
                            android.util.Log.d("SubModeManager", "No submode labels found for layout: $layoutName")
                            emptyList()
                        }
                    }

                    val labels = baseLabels.toMutableList().apply {
                        if (currentLabel.isNotEmpty() && currentLabel !in this) add(0, currentLabel)
                    }.distinct()

                    ime to labels
                } finally {
                    // 还原进入前的活动输入法：仅当确实激活了与原输入法不同的目标时才切回，
                    // 避免对未发生切换的场景（targetImeUniqueName 为空，如布局名未解析出输入法）
                    // 发出多余的 activateIme 调用。拿不到原输入法名时也跳过。
                    if (targetImeUniqueName != null &&
                        originalImeUniqueName != null &&
                        originalImeUniqueName != targetImeUniqueName
                    ) {
                        runCatching { activateIme(originalImeUniqueName) }.onFailure { e ->
                            android.util.Log.w("SubModeManager", "Failed to restore original IME: $originalImeUniqueName", e)
                        }
                    }
                }
            }
        }.onFailure { e ->
            android.util.Log.e("SubModeManager", "Failed to fetch current IME and submode labels for layout: $layoutName", e)
        }.getOrElse {
            null to emptyList()
        }
    }

    /**
     * Resolve target IME unique name
     *
     * @param layoutName Layout name
     * @return Target IME unique name
     */
    private fun resolveTargetImeUniqueName(layoutName: String): String? {
        return layoutName
            .let { layout ->
                allImesFromJson.firstOrNull {
                    it.uniqueName == layout || it.displayName == layout
                }?.uniqueName
            }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Extract submode labels from status area menu
     *
     * @param actions Status area actions list
     * @param currentLabel Current submode label
     * @return Submode labels list
     */
    private fun extractLabelsFromStatusArea(
        actions: Array<Action>,
        currentLabel: String
    ): List<String> {
        return SubModeMenuResolver.extractLabels(actions, currentLabel)
    }

    /**
     * SubMode menu resolver
     */
    private object SubModeMenuResolver {
        /**
         * Extract submode labels from status area actions
         *
         * @param actions Status area actions list
         * @param currentLabel Current submode label
         * @return Submode labels list
         */
        fun extractLabels(
            actions: Array<Action>,
            currentLabel: String
        ): List<String> {
            return pickSchemeMenu(actions, currentLabel)
                ?.let { takeItemsBeforeSeparator(it) }
                ?.drop(1) // The first scheme menu item is a pseudo entry (e.g. English mode), exclude by position.
                ?.mapNotNull { toMenuLabel(it) }
                .orEmpty()
        }

        /**
         * Pick scheme menu
         *
         * @param actions Status area actions list
         * @param currentLabel Current submode label
         * @return Scheme menu list
         */
        private fun pickSchemeMenu(
            actions: Array<Action>,
            currentLabel: String
        ): List<Action>? {
            val topMenus = actions.mapNotNull { action ->
                action.menu?.toList()?.takeIf { it.isNotEmpty() }
            }
            if (topMenus.isEmpty()) return null

            val byCurrentLabel = topMenus.firstOrNull { menu ->
                menu.any { toMenuLabel(it) == currentLabel }
            }
            if (byCurrentLabel != null) return byCurrentLabel

            val withSeparator = topMenus.firstOrNull { menu ->
                val schemePart = takeItemsBeforeSeparator(menu)
                schemePart.size >= 2
            }
            if (withSeparator != null) return withSeparator

            return topMenus.firstOrNull()
        }

        /**
         * Take items before separator
         *
         * @param items Actions list
         * @return Items before separator
         */
        private fun takeItemsBeforeSeparator(
            items: List<Action>
        ): List<Action> {
            val separatorIndex = items.indexOfFirst { it.isSeparator }
            val head = if (separatorIndex >= 0) items.subList(0, separatorIndex) else items
            return head.filterNot { it.isSeparator }
        }

        /**
         * Convert to menu label
         *
         * @param action Action
         * @return Menu label
         */
        private fun toMenuLabel(action: Action): String? =
            action.shortText.ifEmpty { action.longText }.ifEmpty { action.name }.trim().takeIf { it.isNotEmpty() }
    }
}
