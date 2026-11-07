/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js.app

import android.content.Context
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatTextView
import android.widget.TextView
import kotlin.math.hypot

/** Drag the title/empty space, while edits, selection menus and buttons keep their gestures. */
@SuppressLint("ViewConstructor")
internal class JsAppPanelLayout(context: Context, private val moveBy: (Float, Float) -> Unit) : FrameLayout(context) {
    var maximumHeight = Int.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var canDrag = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    init { isClickable = true }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                canDrag = !interactiveAt(this, event.x, event.y)
                dragging = false
                downX = event.rawX; downY = event.rawY
                lastX = downX; lastY = downY
            }
            MotionEvent.ACTION_MOVE -> if (canDrag && !dragging && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                // ViewGroup sends this first intercepted move as CANCEL to the old child.
                // Apply its displacement now so even a short drag moves immediately.
                dragging = true
                moveBy(event.rawX - lastX, event.rawY - lastY)
                lastX = event.rawX; lastY = event.rawY
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { canDrag = false; dragging = false }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canDrag) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.rawX - downX, event.rawY - downY) > slop) dragging = true
                if (dragging) {
                    moveBy(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) performClick()
                canDrag = false; dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { canDrag = false; dragging = false }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumHeight
            else minOf(MeasureSpec.getSize(heightMeasureSpec), maximumHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun interactiveAt(view: View, x: Float, y: Float): Boolean {
        if (view !== this && (view is EditText || view is Button || view.isClickable ||
                (view is TextView && view !is JsAppPanelTitle))) return true
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            val cx = x + view.scrollX - child.x
            val cy = y + view.scrollY - child.y
            if (cx >= 0 && cy >= 0 && cx < child.width && cy < child.height)
                return interactiveAt(child, cx, cy)
        }
        return false
    }
}

/** Only the heading starts a drag; body text must allow the scroll view to handle swipes. */
internal class JsAppPanelTitle(context: Context) : AppCompatTextView(context)
