/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.popup

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.predict.AiCandidateAction
import splitties.dimensions.dp

/** Keeps the original button's touch stream while the finger selects an action above it. */
internal class SlideActionMenu(
    private val anchor: ToolButton,
    private val overlay: FrameLayout,
    private val theme: Theme,
    private val onSelected: (AiCandidateAction) -> Unit,
) {
    private val context = anchor.context
    private val actions = AiCandidateAction.entries
    private val cellSize = context.dp(48)
    private val inset = context.dp(4)
    private var focusedIndex = -1
    private var moved = false
    private var dismissed = false
    private val location = IntArray(2)
    private val labels = actions.map { action ->
        TextView(context).apply {
            text = action.label
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(theme.popupTextColor)
            contentDescription = context.getString(action.titleRes)
            // Click remains available to accessibility services after a long-click.
            setOnClickListener { finish(action) }
        }
    }
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(inset, inset, inset, inset)
        background = background(theme.popupBackgroundColor)
        elevation = context.dp(6).toFloat()
        labels.forEach { addView(it, LinearLayout.LayoutParams(cellSize, cellSize)) }
    }
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            // Detach may be walking an ancestor's child array. Clean up the
            // gesture now, then remove any remaining bubble after that walk.
            dismiss(removeRoot = false)
            Handler(Looper.getMainLooper()).post {
                if (root.parent === overlay) overlay.removeView(root)
            }
        }
    }

    fun show() {
        val width = cellSize * actions.size + inset * 2
        val height = cellSize + inset * 2
        anchor.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]
        overlay.getLocationOnScreen(location)
        val left = (anchorX - location[0] + (anchor.width - width) / 2)
            .coerceIn(0, (overlay.width - width).coerceAtLeast(0))
        val top = (anchorY - location[1] - height - context.dp(8)).coerceAtLeast(0)
        anchor.addOnAttachStateChangeListener(detachListener)
        root.addOnAttachStateChangeListener(detachListener)
        overlay.addView(root, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
        })
        anchor.parent?.requestDisallowInterceptTouchEvent(true)
        anchor.onHeldTouchEvent = ::onTouchEvent
    }

    private fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            dismiss()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                moved = true
                focusAt(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> {
                if (moved) focusAt(event.rawX, event.rawY)
                finish(actions.getOrNull(focusedIndex))
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> dismiss()
        }
        return true
    }

    private fun focusAt(x: Float, y: Float) {
        root.getLocationOnScreen(location)
        val localX = x - location[0]
        val top = location[1]
        anchor.getLocationOnScreen(location)
        // Project the finger's horizontal position onto the candidate row, even
        // while it stays on the held button or crosses the gap below the bubble.
        if (localX < 0 || localX >= root.width || y < top || y >= location[1] + anchor.height) {
            markFocus(-1)
            dismiss()
            return
        }
        val index = ((localX - inset).coerceAtLeast(0f) / cellSize).toInt().coerceAtMost(actions.lastIndex)
        markFocus(index)
    }

    private fun markFocus(index: Int) {
        if (index == focusedIndex) return
        focusedIndex = index
        labels.forEachIndexed { i, label ->
            label.isSelected = i == index
            label.background = if (i == index) background(theme.genericActiveBackgroundColor) else null
            label.setTextColor(if (i == index) theme.genericActiveForegroundColor else theme.popupTextColor)
        }
    }

    private fun finish(action: AiCandidateAction?) {
        if (dismissed) return
        dismiss()
        action?.let(onSelected)
    }

    fun dismiss() = dismiss(removeRoot = true)

    private fun dismiss(removeRoot: Boolean) {
        if (dismissed) return
        dismissed = true
        anchor.onHeldTouchEvent = null
        anchor.cancelGestures()
        anchor.parent?.requestDisallowInterceptTouchEvent(false)
        anchor.removeOnAttachStateChangeListener(detachListener)
        root.removeOnAttachStateChangeListener(detachListener)
        if (removeRoot) overlay.removeView(root)
    }

    private fun background(color: Int) = GradientDrawable().apply {
        cornerRadius = context.dp(12).toFloat()
        setColor(color)
    }
}
