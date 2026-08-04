/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.resolveThemeColorToken
import splitties.dimensions.dp
import splitties.resources.styledColor

internal object ThemeColorTokenPicker {

    val tokens = listOf(
        "backgroundColor",
        "barColor",
        "keyboardColor",
        "keyBackgroundColor",
        "keyTextColor",
        "candidateTextColor",
        "candidateLabelColor",
        "candidateCommentColor",
        "altKeyBackgroundColor",
        "altKeyTextColor",
        "accentKeyBackgroundColor",
        "accentKeyTextColor",
        "keyPressHighlightColor",
        "keyShadowColor",
        "popupBackgroundColor",
        "popupTextColor",
        "spaceBarColor",
        "dividerColor",
        "clipboardEntryColor",
        "genericActiveBackgroundColor",
        "genericActiveForegroundColor",
    )

    fun formatTokenName(token: String): String {
        return token.replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }

    class PreviewAdapter(
        private val context: Context,
        private val tokens: List<String>,
        private val theme: Theme,
        private val singleLineLabel: Boolean = false,
    ) : BaseAdapter() {

        override fun getCount(): Int = tokens.size

        override fun getItem(position: Int): Any = tokens[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                val colorPreview = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(context.dp(20), context.dp(20))
                }
                val nameText = TextView(context).apply {
                    setTextColor(context.styledColor(android.R.attr.textColorPrimary))
                    textSize = 14f
                    if (singleLineLabel) {
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply {
                            marginStart = context.dp(12)
                        }
                    } else {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            marginStart = context.dp(12)
                        }
                    }
                }
                addView(colorPreview)
                addView(nameText)
                tag = RowHolder(colorPreview, nameText)
            }
            val holder = row.tag as RowHolder
            val token = tokens[position]
            holder.colorPreview.background = GradientDrawable().apply {
                cornerRadius = context.dp(4).toFloat()
                setColor(resolveThemeColorToken(theme, token) ?: Color.TRANSPARENT)
            }
            holder.nameText.text = formatTokenName(token)
            return row
        }
    }

    private data class RowHolder(
        val colorPreview: View,
        val nameText: TextView,
    )
}
