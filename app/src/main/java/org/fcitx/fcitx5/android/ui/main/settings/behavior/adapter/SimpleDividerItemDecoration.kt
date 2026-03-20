/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp
import splitties.resources.styledColor

/**
 * 简单的分割线装饰，无动画问题。
 * 用于在 RecyclerView 的行之间绘制分割线。
 */
class SimpleDividerItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val dividerHeight = context.dp(1)
    private val paint = android.graphics.Paint().apply {
        color = context.styledColor(android.R.attr.colorControlNormal)
        alpha = 90 // 0.35 * 255
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight

        // 不在最后一项（添加行按钮）后绘制分割线
        val childCount = parent.childCount - 1
        for (i in 0 until childCount) {
            val child = parent.getChildAt(i)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val top = child.bottom + params.bottomMargin
            val bottom = top + dividerHeight
            c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        }
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        // 不为最后一项（添加行按钮）添加偏移
        val position = parent.getChildAdapterPosition(view)
        val adapter = parent.adapter
        if (adapter != null && position == adapter.itemCount - 1) {
            outRect.set(0, 0, 0, 0)
        } else {
            outRect.set(0, 0, 0, dividerHeight)
        }
    }
}
