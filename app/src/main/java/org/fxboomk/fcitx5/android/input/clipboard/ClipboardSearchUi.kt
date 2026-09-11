/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.preedit.PreeditUi
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardSearchUi(override val ctx: Context, private val theme: Theme) : Ui {

    private var renderedCursor = 0

    val backButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_back_24, theme).apply {
        contentDescription = ctx.getString(R.string.back_to_keyboard)
    }

    val clearButton = ToolButton(ctx, R.drawable.ic_baseline_close_24, theme).apply {
        contentDescription = ctx.getString(R.string.clear)
        visibility = View.INVISIBLE
    }

    val pinButton = ToolButton(ctx, R.drawable.ic_outline_push_pin_24, theme)

    val dragHandle = textView {
        text = ctx.getString(R.string.clipboard_search_title)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.altKeyTextColor)
    }

    private val categoryButtons = linkedMapOf(
        ClipboardSearchCategory.All to createCategoryButton(R.string.clipboard_category_all),
        ClipboardSearchCategory.Favorites to createCategoryButton(R.string.clipboard_category_favorites),
        ClipboardSearchCategory.Local to createCategoryButton(R.string.clipboard_category_local),
        ClipboardSearchCategory.Remote to createCategoryButton(R.string.clipboard_search_category_remote),
        ClipboardSearchCategory.Media to createCategoryButton(R.string.clipboard_search_category_media)
    )

    private val categoryBar = horizontalLayout {
        setPaddingDp(8, 3, 8, 5)
        categoryButtons.forEach { (_, button) ->
            add(button, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    val recyclerView = recyclerView {
        addItemDecoration(SpacesItemDecoration(dp(4)))
        clipToPadding = false
        setPaddingDp(4, 0, 4, 4)
        visibility = View.GONE
    }

    private val messageView = textView {
        gravity = gravityCenter
        textSize = 14f
        isClickable = false
        setPaddingDp(16, 8, 16, 8)
        setTextColor(theme.altKeyTextColor)
    }

    private val queryText = textView {
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setHorizontallyScrolling(true)
        setTextColor(theme.keyTextColor)
    }

    private val cursorSpan by lazy {
        PreeditUi.CursorSpan(ctx, theme.keyTextColor, queryText.paint.fontMetricsInt)
    }

    private val searchIcon = imageView {
        imageDrawable = drawable(R.drawable.ic_baseline_search_24)?.apply {
            setTint(theme.altKeyTextColor)
        }
        contentDescription = ctx.getString(R.string.search)
        setPaddingDp(10, 10, 10, 10)
    }

    private val inputBar = horizontalLayout {
        gravity = Gravity.CENTER_VERTICAL
        setPaddingDp(4, 0, 4, 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(theme.keyBackgroundColor)
        }
        add(searchIcon, lParams(dp(40), dp(40)))
        add(queryText, lParams(0, dp(44)) { weight = 1f })
        add(clearButton, lParams(dp(40), dp(40)))
    }

    val panel = verticalLayout {
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(theme.barColor)
        }
        clipToOutline = true
        elevation = dp(8).toFloat()
        add(horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            add(backButton, lParams(dp(40), dp(40)))
            add(dragHandle, lParams(0, dp(40)) { weight = 1f })
            add(pinButton, lParams(dp(40), dp(40)))
        }, lParams(matchParent, dp(40)))
        add(categoryBar, lParams(matchParent, dp(40)))
        add(frameLayout {
            add(recyclerView, FrameLayout.LayoutParams(matchParent, matchParent))
            add(messageView, FrameLayout.LayoutParams(matchParent, matchParent))
        }, lParams(matchParent, 0) { weight = 1f })
        add(inputBar, lParams(matchParent, wrapContent) {
            setMargins(dp(8), dp(4), dp(8), dp(8))
        })
    }

    override val root = frameLayout {
        add(panel, FrameLayout.LayoutParams(matchParent, matchParent).apply {
            setMargins(dp(6), dp(4), dp(6), dp(30))
        })
    }

    private fun createCategoryButton(textRes: Int) = textView {
        gravity = gravityCenter
        text = ctx.getString(textRes)
        textSize = 13f
        setPaddingDp(4, 0, 4, 0)
        background = categoryBackground(selected = false)
        setTextColor(theme.keyTextColor)
    }

    private fun categoryBackground(selected: Boolean) = RippleDrawable(
        ColorStateList.valueOf(theme.keyPressHighlightColor),
        GradientDrawable().apply {
            cornerRadius = ctx.dp(16).toFloat()
            setColor(if (selected) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
        },
        GradientDrawable().apply {
            cornerRadius = ctx.dp(16).toFloat()
            setColor(Color.WHITE)
        }
    )

    fun setOnCategorySelectedListener(listener: (ClipboardSearchCategory) -> Unit) {
        categoryButtons.forEach { (category, button) ->
            button.setOnClickListener {
                setSelectedCategory(category)
                listener(category)
            }
        }
    }

    fun setSelectedCategory(category: ClipboardSearchCategory) {
        categoryButtons.forEach { (buttonCategory, button) ->
            val selected = buttonCategory == category
            button.background = categoryBackground(selected)
            button.setTextColor(if (selected) theme.accentKeyTextColor else theme.keyTextColor)
        }
    }

    fun setPinned(pinned: Boolean) {
        pinButton.setIcon(
            if (pinned) R.drawable.ic_baseline_push_pin_24 else R.drawable.ic_outline_push_pin_24
        )
        pinButton.setActive(pinned)
        pinButton.contentDescription = ctx.getString(
            if (pinned) R.string.clipboard_search_unpin else R.string.clipboard_search_pin
        )
    }

    fun setOnCursorPositionedListener(listener: (Int) -> Unit) {
        queryText.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                queryText.requestFocus()
                val layout = queryText.layout ?: return@setOnTouchListener true
                val line = layout.getLineForVertical(
                    (event.y - queryText.totalPaddingTop + queryText.scrollY).toInt()
                )
                val visualOffset = layout.getOffsetForHorizontal(
                    line,
                    event.x - queryText.totalPaddingLeft + queryText.scrollX
                )
                listener(if (visualOffset > renderedCursor) visualOffset - 1 else visualOffset)
                view.performClick()
            }
            true
        }
    }

    fun showMessage(text: String) {
        messageView.text = text
        messageView.gravity = Gravity.CENTER
        messageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        messageView.isVisible = true
        recyclerView.isVisible = false
        recyclerView.setPadding(ctx.dp(4), 0, ctx.dp(4), ctx.dp(4))
    }

    fun showResults(status: String) {
        messageView.text = status
        messageView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        messageView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ctx.dp(40),
            Gravity.TOP
        )
        messageView.isVisible = true
        recyclerView.isVisible = true
        recyclerView.setPadding(ctx.dp(4), ctx.dp(40), ctx.dp(4), ctx.dp(4))
    }

    fun renderInput(state: ClipboardSearchInputState) {
        clearButton.visibility = if (state.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        val cursor = state.displayCursor.coerceIn(0, state.text.length)
        renderedCursor = cursor
        queryText.text = buildSpannedString {
            append(state.text, 0, cursor)
            append('|')
            setSpan(cursorSpan, cursor, cursor + 1, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            append(state.text, cursor, state.text.length)
            if (state.text.isEmpty()) {
                val hintStart = length
                append(ctx.getString(R.string.clipboard_search_hint))
                setSpan(
                    ForegroundColorSpan(theme.altKeyTextColor),
                    hintStart,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        queryText.requestFocus()
    }
}
