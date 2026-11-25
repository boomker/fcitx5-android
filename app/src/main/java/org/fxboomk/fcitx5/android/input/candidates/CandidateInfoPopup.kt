/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.input.candidates

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme

internal class CandidateInfoPopup(
    private val anchor: View,
    private val card: CandidateCardContent,
    private val theme: Theme,
    private val onCommit: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val ctx = anchor.context
    private val density = ctx.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)

    private val detailRows = card.characters.map(::characterRow)
    private val readingRow = card.phraseReading?.let {
        if (card.wubiCode == null) it else ctx.getString(R.string.candidate_card_pinyin, it)
    }
    private val wubiRow = card.wubiCode?.let {
        ctx.getString(R.string.candidate_card_wubi_code, it.ifEmpty { "—" })
    }
    private val exportText = buildList {
        add(card.text)
        readingRow?.let(::add)
        wubiRow?.let(::add)
        detailRows.forEach { row ->
            for (index in 0 until row.childCount) {
                add((row.getChildAt(index) as TextView).text.toString())
            }
        }
    }.joinToString("\n")

    private val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL

            addView(TextView(ctx).apply {
                text = card.text
                textSize = 34f
                minHeight = dp(54)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(theme.genericActiveForegroundColor)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            readingRow?.let { reading ->
                addView(TextView(ctx).apply {
                    text = reading
                    textSize = 18f
                    setTextColor(theme.popupTextColor)
                })
            }
            wubiRow?.let { code ->
                addView(TextView(ctx).apply {
                    text = code
                    textSize = 18f
                    setTextColor(theme.popupTextColor)
                })
            }

            detailRows.forEach { addView(it) }
        })
    }

    private val content = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(theme.popupBackgroundColor)
        }
        elevation = dp(8).toFloat()

        // Under a height constraint only the scrolling area shrinks; the actions stay visible.
        addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ))

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(button(R.string.candidate_card_commit) { onCommit(exportText) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button(R.string.cancel, ::dismiss), LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button(R.string.candidate_card_copy) { onCopy(exportText) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private val window = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isTouchable = true
        isOutsideTouchable = true
        isClippingEnabled = true
        animationStyle = 0
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setOnDismissListener(onDismiss)
    }

    val isShowing: Boolean get() = window.isShowing

    // Offsets use physical screen coordinates, so LEFT is intentional in both LTR and RTL.
    @SuppressLint("RtlHardcoded")
    fun show() {
        if (!anchor.isAttachedToWindow) return
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (frame.isEmpty) return
        val margin = dp(8)
        val width = (frame.width() - 2 * margin).coerceAtLeast(1)
        val minY = frame.top + margin
        val maxY = frame.bottom - margin
        val availableHeight = (maxY - minY).coerceAtLeast(1)
        val heightLimit = maxOf(availableHeight * 2 / 3, dp(144)).coerceAtMost(availableHeight)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightLimit, View.MeasureSpec.AT_MOST)
        )
        window.width = width
        window.height = content.measuredHeight

        val (anchorX, anchorY) = IntArray(2).also(anchor::getLocationOnScreen)
        val anchorBottom = anchorY + anchor.height
        val lastTop = (maxY - window.height).coerceAtLeast(minY)
        val above = anchorY - margin - window.height
        val below = anchorBottom + margin
        val top = when {
            above in minY..lastTop -> above
            below in minY..lastTop -> below
            else -> above.coerceIn(minY, lastTop)
        }
        window.showAsDropDown(anchor, frame.left + margin - anchorX, top - anchorBottom, Gravity.TOP or Gravity.LEFT)
    }

    fun dismiss() {
        window.dismiss()
    }

    private fun characterRow(detail: CandidateCardContent.CharacterDetail) = LinearLayout(ctx).apply {
        val (character, reading, data, wubiCode) = detail
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(6), dp(4), dp(6))
        addView(TextView(ctx).apply {
            text = if (reading == null || card.text == character) character else ctx.getString(
                R.string.candidate_card_character_reading,
                character,
                reading
            )
            textSize = 22f
            setTextColor(theme.popupTextColor)
            includeFontPadding = false
        })
        if (wubiCode != null && card.characters.size > 1) {
            addView(TextView(ctx).apply {
                text = ctx.getString(R.string.candidate_card_wubi_code, wubiCode.ifEmpty { "—" })
                textSize = 15f
                setTextColor(theme.candidateCommentColor)
            })
        }
        if (data != null) {
            addView(TextView(ctx).apply {
                text = if (data.strokeCount > 0) {
                    ctx.getString(R.string.candidate_card_radical_count, data.radical, data.strokeCount)
                } else {
                    ctx.getString(R.string.candidate_card_radical, data.radical)
                }
                textSize = 15f
                setTextColor(theme.candidateCommentColor)
            })
            if (data.parts.isNotEmpty()) {
                val partReadings = data.parts.map { card.partReadings[it] ?: "—" }
                addView(TextView(ctx).apply {
                    val parts = data.parts.joinToString(" ")
                    text = if (partReadings.any { it != "—" }) {
                        ctx.getString(R.string.candidate_card_parts_readings, parts, partReadings.joinToString(" "))
                    } else {
                        ctx.getString(R.string.candidate_card_parts, parts)
                    }
                    textSize = 15f
                    setTextColor(theme.candidateCommentColor)
                })
            }
            if (data.strokeOrder.isNotEmpty()) {
                addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.candidate_card_order, data.strokeOrder)
                    textSize = 15f
                    setTextColor(theme.candidateCommentColor)
                })
            }
        }
        addView(TextView(ctx).apply {
            text = candidateUnicodeDescription(ctx, character)
            textSize = 15f
            setTextColor(theme.candidateCommentColor)
        })
    }

    private fun button(label: Int, click: () -> Unit) = TextView(ctx).apply {
        text = ctx.getString(label)
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(theme.genericActiveForegroundColor)
        background = AppCompatResources.getDrawable(ctx, android.R.drawable.list_selector_background)
        setOnClickListener { click() }
    }
}
