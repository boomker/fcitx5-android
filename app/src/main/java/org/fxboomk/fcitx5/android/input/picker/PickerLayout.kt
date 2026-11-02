/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.keyboard.KeyActionListener
import org.fxboomk.fcitx5.android.input.popup.PopupActionListener
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add

/**
 * 符号面板的外层布局：**面板独占整个键盘区域**。
 *
 * 这里刻意不放底部嵌入式键盘行。早期版本沿用旧结构在底部保留了一行
 * `ABC , <切换> 空格 . 回车`，但符号面板的按键本身是「点一下即时上屏」，
 * 空格/回车/逗号在面板里没有意义，那行只会白白吃掉约 1/4 的键盘高度。
 *
 * 键盘级操作只有 ⌨ 与 ⌫ 两枚小键，位置随面板风格而定（左侧分组栏时在左栏底部，
 * 顶部分组栏风格时分居顶栏两端，见 [SymbolPanelUi] 与 [SymbolPanelStyle]）；
 * **面板之间的切换属于布局按键的配置**，不在这里硬编码。
 */
@SuppressLint("ViewConstructor")
class PickerLayout(
    context: Context,
    theme: Theme,
    catalogType: SymbolCatalogType,
    columns: Int,
    textSize: Float,
    policy: PickerPolicy,
    keyActionListener: KeyActionListener,
    popupActionListener: PopupActionListener
) : ConstraintLayout(context) {

    val symbolPanel = SymbolPanelUi(
        ctx = context,
        theme = theme,
        catalogType = catalogType,
        columns = columns,
        textSize = textSize,
        policy = policy,
        keyActionListener = keyActionListener,
        popupActionListener = popupActionListener
    )

    init {
        symbolPanel.root.id = View.generateViewId()
        add(symbolPanel.root, lParams(matchConstraints, matchConstraints) {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
    }
}
