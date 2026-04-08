/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.setPaddingDp
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.SystemColorResourceId
import org.fxboomk.fcitx5.android.data.theme.ThemeMonet

/**
 * 系统动态颜色资源选择器 Dialog
 * 用于在 Monet 主题编辑界面中选择系统动态颜色资源
 */
object SystemColorResourcePickerDialog {
    
    interface OnColorResourceSelectedListener {
        fun onColorResourceSelected(resourceId: SystemColorResourceId)
    }
    
    /**
     * 显示颜色资源选择器 Dialog
     * @param context 上下文
     * @param currentResource 当前选中的颜色资源
     * @param listener 选择回调
     */
    fun show(
        context: Context,
        currentResource: SystemColorResourceId,
        listener: OnColorResourceSelectedListener
    ) {
        // 检查系统是否支持动态颜色
        if (!ThemeMonet.supportsCustomMappingEditor(context)) {
            return
        }
        
        val availableResources = SystemColorResourceId.getAvailableForSdk(Build.VERSION.SDK_INT)
        
        // 创建带预览的颜色列表
        val colorItems = availableResources.mapNotNull { resource ->
            val colorResId = context.resources.getIdentifier(resource.resourceId, "color", "android")
            if (colorResId != 0) {
                try {
                    val color = context.getColor(colorResId)
                    resource to color
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
        
        val currentIndex = colorItems.indexOfFirst { it.first == currentResource }
            .takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(context)
            .setTitle(R.string.monet_editor_select_color_resource)
            .setSingleChoiceItems(
                ColorPreviewAdapter(context, colorItems.map { it.first }, colorItems.map { it.second }),
                currentIndex
            ) { dialog, which ->
                listener.onColorResourceSelected(colorItems[which].first)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class ColorPreviewAdapter(
        private val context: Context,
        private val resources: List<SystemColorResourceId>,
        private val colors: List<Int>
    ) : BaseAdapter() {
        override fun getCount(): Int = resources.size
        override fun getItem(position: Int): Any = resources[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPaddingDp(12, 10, 12, 10)
                val colorPreview = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20))
                }
                val nameText = TextView(context).apply {
                    setTextColor(context.styledColor(android.R.attr.textColorPrimary))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = context.dp(12)
                    }
                }
                addView(colorPreview)
                addView(nameText)
                tag = RowHolder(colorPreview, nameText)
            }
            val holder = row.tag as RowHolder
            holder.colorPreview.backgroundColor = colors[position]
            holder.nameText.text = resources[position].resourceId.removePrefix("system_").replace("_", " ")
            return row
        }
    }

    private data class RowHolder(val colorPreview: View, val nameText: TextView)
}
