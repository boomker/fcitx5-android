/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.isVisible
import org.fxboomk.fcitx5.android.core.CandidateAction
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.font.FontProviders
import splitties.dimensions.dp

internal fun preeditSyllableActions(
    tabs: Array<CandidateAction>,
    enabled: Boolean,
): List<CandidateAction> {
    if (!enabled) return emptyList()
    return tabs.filter { action ->
        action.id >= 0 && !action.isSeparator &&
            action.text.isNotEmpty() && action.text.all { it in 'a'..'z' || it == 'ü' || it == ':' }
    }
}

/** The current native syllable actions, with the same IDs as the expanded candidate panel. */
@SuppressLint("ViewConstructor")
internal class SyllableStrip(context: Context, private val theme: Theme) : HorizontalScrollView(context) {
    var onSelect: ((CandidateAction, FcitxEvent.InputPanelEvent.Data) -> Unit)? = null

    private val items = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private var shownActions = emptyList<CandidateAction>()
    private var shownPanel: FcitxEvent.InputPanelEvent.Data? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(items, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        isVisible = false
    }

    fun update(panel: FcitxEvent.InputPanelEvent.Data, enabled: Boolean) {
        val actions = preeditSyllableActions(panel.tabs, enabled && panel.preedit.isNotEmpty())
        if (shownPanel === panel && shownActions == actions) return
        shownPanel = panel
        shownActions = actions
        // A pressed or queued click belongs to the previous candidate list.
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            items.dispatchTouchEvent(it)
            it.recycle()
        }
        items.cancelPendingInputEvents()
        for (index in 0 until items.childCount) {
            items.getChildAt(index).setOnClickListener(null)
        }
        items.removeAllViews()
        isVisible = actions.isNotEmpty()
        actions.forEach { action -> items.addView(item(action, panel)) }
        scrollTo(0, 0)
    }

    private fun item(action: CandidateAction, panel: FcitxEvent.InputPanelEvent.Data) =
        object : AppCompatTextView(context) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = Button::class.java.name
                info.isCheckable = action.isCheckable
                info.isChecked = action.isChecked
            }
        }.apply {
            text = action.text
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = context.dp(40)
            setPadding(context.dp(8), 0, context.dp(8), 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, FontProviders.getFontSize("preedit_font", 16f))
            typeface = FontProviders.resolveTypeface("preedit_font", typeface)
            setTextColor(theme.keyTextColor)
            isActivated = action.isChecked
            isSelected = action.isChecked
            isFocusable = true
            contentDescription = action.text
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(theme.keyPressHighlightColor))
                addState(intArrayOf(android.R.attr.state_activated), ColorDrawable(theme.genericActiveBackgroundColor))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                if (shownPanel === panel && action in shownActions) onSelect?.invoke(action, panel)
            }
        }
}
