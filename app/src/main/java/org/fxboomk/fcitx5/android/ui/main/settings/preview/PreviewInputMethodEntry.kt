/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.preview

import org.fxboomk.fcitx5.android.core.InputMethodEntry

object PreviewInputMethodEntry {

    // Rime 引擎在项目内的稳定身份：空格键标识字符、addon 名与状态图标。
    // 与 StatusAreaWindow / KeyboardWindow / CommonKeyActionListener 中的判定保持一致。
    private const val RIME_LAYOUT_NAME = "rime"
    private const val RIME_LABEL = "㞢"
    private const val RIME_ADDON = "rime"
    private const val RIME_ICON = "fcitx-rime"

    fun create(
        layoutName: String = "Preview",
        subModeLabel: String? = null,
        base: InputMethodEntry? = null,
        displayName: String? = null
    ): InputMethodEntry {
        val selectedSubModeLabel = subModeLabel?.trim().orEmpty()
        val isRimeLayout = layoutName.equals(RIME_LAYOUT_NAME, ignoreCase = true)
        return base
            ?.copy(
                uniqueName = layoutName,
                name = displayName?.takeIf { it.isNotBlank() } ?: layoutName,
                // 预览身份沿用当前活动输入法，但编辑 Rime 输入方案时必须使用 Rime 引擎标识，
                // 否则会错误继承当前输入法（如内置双拼 label="双"）的标签。
                label = if (isRimeLayout) RIME_LABEL else base.label,
                addon = if (isRimeLayout) RIME_ADDON else base.addon,
                icon = if (isRimeLayout) RIME_ICON else base.icon,
                subMode = if (selectedSubModeLabel.isNotEmpty()) {
                    base.subMode.copy(
                        label = selectedSubModeLabel,
                        name = selectedSubModeLabel
                    )
                } else {
                    base.subMode.copy(
                        name = "",
                        label = "",
                        icon = ""
                    )
                }
            )
            ?: InputMethodEntry(layoutName).let { entry ->
                if (isRimeLayout) {
                    entry.copy(label = RIME_LABEL, addon = RIME_ADDON, icon = RIME_ICON)
                } else {
                    entry
                }
            }
    }
}
