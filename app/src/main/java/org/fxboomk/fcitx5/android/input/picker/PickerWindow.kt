/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.picker

import androidx.core.content.ContextCompat
import androidx.transition.Transition
import org.fxboomk.fcitx5.android.input.dependency.context
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyActionListener
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.popup.PopupAction
import org.fxboomk.fcitx5.android.input.popup.PopupActionListener
import org.fxboomk.fcitx5.android.input.popup.PopupComponent
import org.fxboomk.fcitx5.android.input.wm.EssentialWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

/**
 * 符号 / 表情 / 颜文字面板窗口。
 *
 * UI 沿用 Foxy 输入法（`com.fxliang.foxy`）的符号面板模型，**面板独占整个键盘区域**：
 * 分组栏可摆在左侧（新版）或顶部（旧版），由 [SymbolPanelStyle] 决定，
 * 见 `AppPrefs.symbols.symbolPanelStyle`。
 *
 * ### 为什么是 [InputWindow.SimpleInputWindow] 而不是 `ExtendedInputWindow`
 *
 * 本项目里 `ExtendedInputWindow` 会让工具栏（Kawaii Bar）整体切换到「标题栏」形态
 * （`KawaiiBarStateMachine.State.Title`），把主键盘那套按钮整片换掉。这不是想要的效果：
 * 用户要求**工具栏保持主键盘原样**，只把最左侧按钮换成返回箭头。
 *
 * 因此这里用普通窗口：窗口切换时工具栏不做任何形态切换，仍显示 `IdleUi`（主键盘工具栏），
 * 再由 `KawaiiBarComponent` 通过 `IdleUi.setBackToKeyboardMode` 只替换最左图标。
 *
 * 面板自身的键盘级操作（返回键盘 / 退格）只有两枚键，位置随面板风格而定，见 [SymbolPanelUi]；
 * **面板之间的跳转不在这里**，由布局按键的切换目标配置（见 [Key.layerTargetNames]）。
 */
class PickerWindow(
    override val key: Key,
    private val catalogType: SymbolCatalogType,
    private val columns: Int,
    private val textSize: Float,
    private val policy: PickerPolicy = DefaultPickerPolicy()
) : InputWindow.SimpleInputWindow<PickerWindow>(), EssentialWindow {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Kaomoji;

        companion object {
            /**
             * 颜文字面板的旧名。
             *
             * 2026-09-28 之前这个枚举叫 `Emoticon`，该名字**已经写进用户数据**：
             * 布局按键的 `subLabel`、宏的切层 `target`、`lastPickerType` 偏好。
             * 改名后必须继续能解析，否则存量用户的按键会变成「无效目标」而静默失效。
             */
            private const val LEGACY_KAOMOJI = "Emoticon"

            /**
             * 把布局文件 / 宏里保存的目标名解析成面板 [Key]。
             *
             * 命名沿用枚举名（`Symbol` / `Emoji` / `Kaomoji`），大小写不敏感；
             * 旧名 `Emoticon` 作为别名一并接受（见 [LEGACY_KAOMOJI]）。
             *
             * 返回 null 表示该目标不是符号面板，调用方应继续按文本层处理。
             */
            fun ofName(raw: String?): Key? {
                if (raw.isNullOrBlank()) return null
                val trimmed = raw.trim()
                if (trimmed.equals(LEGACY_KAOMOJI, ignoreCase = true)) return Kaomoji
                return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            }

            /**
             * 三个面板的层目标名，供布局编辑器的「切换目标」候选列表使用。
             *
             * 这几个名字是 [KeyAction.LayoutSwitchAction] 的合法目标，最终由
             * [KeyboardWindow.switchLayout] 路由到对应的 [PickerWindow]。
             */
            val layerTargetNames: List<String> = entries.map { it.name }
        }
    }

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    private lateinit var pickerLayout: PickerLayout

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> {
                // Switch to NumberKeyboard before attaching KeyboardWindow
                (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(it.act, remember = true)
                // The real switchLayout (detachCurrentLayout and attachLayout) in KeyboardWindow is postponed,
                // so we have to postpone attachWindow as well
                ContextCompat.getMainExecutor(context).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is KeyAction.PickerSwitchAction -> {
                // 交给 CommonKeyActionListener 统一处理窗口切换与偏好更新。
                commonKeyActionListener.listener.onKeyAction(it, source)
            }

            is KeyAction.FcitxKeyAction -> {
                // we want the behavior of CommitAction (commit the character as-is),
                // but don't want to include it in recently used list
                commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(it.act), source)
            }

            else -> {
                commonKeyActionListener.listener.onKeyAction(it, source)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        PopupActionListener {
            when (it) {
                is PopupAction.ShowKeyboardAction -> {
                    // 长按弹出键盘展示期间不要移动底层列表
                    pickerLayout.symbolPanel.scrollToTop()
                }
                else -> {}
            }
            popup.listener.onPopupAction(it)
        }
    }

    override fun onCreateView() = PickerLayout(
        context,
        theme,
        catalogType,
        columns,
        textSize,
        policy,
        keyActionListener,
        popupActionListener
    ).apply {
        pickerLayout = this
    }

    override fun onAttached() {
        pickerLayout.symbolPanel.apply {
            // 重建同时会按当前字体配置重新绑定可见项
            refresh()
            scrollToTop()
        }
    }

    override fun onDetached() {
        popup.dismissAll()
    }
}
