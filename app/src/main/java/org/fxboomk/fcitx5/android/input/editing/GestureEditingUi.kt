/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.InputFeedbacks
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.config.KeyIconIds
import org.fxboomk.fcitx5.android.input.config.KeyIconThemeStore
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.ArrayDeque

internal class GestureEditingUi(
    private val context: Context,
    private val theme: Theme,
    mode: GestureEditingMode,
    private val ripple: Boolean,
    private val border: Boolean,
    private val radius: Float,
    onMovement: (GestureMovementAccumulator.Movement) -> Unit
) {
    private fun iconButton(label: Int, icon: Int) =
        TextEditingButton(context, theme, ripple, border, radius, altStyle = true).apply {
            setIcon(icon)
            contentDescription = context.getString(label)
            TooltipCompat.setTooltipText(this, contentDescription)
            minimumHeight = context.dp(48)
        }

    val exitButton = iconButton(R.string.gesture_editing_exit, R.drawable.ic_baseline_close_24)
    val actionsButton = iconButton(R.string.gesture_editing_actions, R.drawable.ic_baseline_more_horiz_24)
    val switchButton = iconButton(R.string.gesture_editing_switch, R.drawable.ic_baseline_select_all_24)
    val pasteButton = iconButton(android.R.string.paste, R.drawable.ic_baseline_content_paste_24)
    val deleteButton = iconButton(R.string.delete, R.drawable.ic_baseline_backspace_24).apply {
        soundEffect = InputFeedbacks.SoundEffect.Delete
    }

    val root = TouchpadView(context, onMovement, theme.accentKeyBackgroundColor).apply {
        val caption = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(24), context.dp(16), context.dp(24), context.dp(16))
            addView(TextView(context).apply {
                setText(
                    if (mode == GestureEditingMode.Selection) R.string.gesture_text_selection_mode
                    else R.string.gesture_cursor_mode
                )
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(theme.keyTextColor)
            })
            addView(TextView(context).apply {
                setText(R.string.gesture_editing_hint)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(theme.keyTextColor)
                alpha = 0.65f
                setPadding(0, context.dp(12), 0, 0)
            })
        }
        addView(caption, FrameLayout.LayoutParams(-1, -1).apply {
            bottomMargin = context.dp(48)
        })
        val controls = if (mode == GestureEditingMode.Selection) {
            listOf(exitButton, actionsButton, deleteButton)
        } else {
            listOf(exitButton, switchButton, pasteButton, deleteButton)
        }
        buttons = controls
        controls.forEach { button ->
            addView(button, FrameLayout.LayoutParams(context.dp(64), context.dp(48)).apply {
                gravity = Gravity.BOTTOM
            })
        }
    }

    fun refreshIcons() {
        val fallback = if (ThemeManager.prefs.gboardStyleSideKeys.getValue()) {
            R.drawable.ic_outline_backspace_24
        } else {
            R.drawable.ic_baseline_backspace_24
        }
        KeyIconThemeStore.resolveDrawableObject(root.context, KeyIconIds.BACKSPACE, fallback)
            ?.let(deleteButton::setIconDrawable)
    }

    @SuppressLint("ViewConstructor")
    internal class TouchpadView(
        context: Context,
        private val onMovement: (GestureMovementAccumulator.Movement) -> Unit,
        trailColor: Int,
    ) : FrameLayout(context) {
        var buttons: List<View> = emptyList()

        private val movement = GestureMovementAccumulator(
            context.dp(16).toFloat(), context.dp(32).toFloat(), context.resources.displayMetrics.density
        )
        private val trail = ArrayDeque<PointF>()
        private val trailPath = Path()
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val hsv = FloatArray(3)
            Color.colorToHSV(trailColor, hsv)
            color = if (hsv[1] >= 0.25f) trailColor or (0xff shl 24) else Color.rgb(38, 166, 154)
            style = Paint.Style.STROKE
            strokeWidth = context.dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var trailOpacity = 1f
        private var trailFade: ValueAnimator? = null
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val buttonBounds = Rect()
        private var activePointer = -1
        private var downX = 0f
        private var downY = 0f
        private var dragged = false
        private var pressedButton: View? = null

        init {
            isFocusable = false
        }

        fun cancelGesture(clearTrail: Boolean = true) {
            activePointer = -1
            pressedButton?.isPressed = false
            pressedButton = null
            dragged = false
            movement.cancel()
            if (clearTrail) {
                trailFade?.cancel()
                trailFade = null
                trail.clear()
                trailOpacity = 1f
                invalidate()
            }
        }

        private fun updateMovement(event: MotionEvent) {
            val index = event.findPointerIndex(activePointer)
            if (index < 0) return
            repeat(event.historySize) { history ->
                updateMovement(event.getHistoricalX(index, history), event.getHistoricalY(index, history),
                    event.getHistoricalEventTime(history))
            }
            updateMovement(event.getX(index), event.getY(index), event.eventTime)
        }

        private fun updateMovement(x: Float, y: Float, eventTime: Long) {
            if (!dragged && (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop)) {
                dragged = true
                pressedButton?.isPressed = false
            }
            addTrailPoint(x, y)
            val steps = movement.move(x, y, eventTime)
            if (steps.horizontal != 0 || steps.vertical != 0) onMovement(steps)
        }

        private fun addTrailPoint(x: Float, y: Float) {
            val last = trail.peekLast()
            if (last != null && last.x == x && last.y == y) return
            // Bound memory and drawing work during long strokes.
            if (trail.size == 256) trail.removeFirst()
            trail.addLast(PointF(x, y))
            invalidate()
        }

        private fun fadeTrail() {
            if (trail.isEmpty()) return
            trailFade = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 220
                addUpdateListener {
                    trailOpacity = it.animatedValue as Float
                    if (trailOpacity == 0f) trail.clear()
                    invalidate()
                }
                start()
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val head = trail.peekLast() ?: return
            trailPaint.alpha = (trailOpacity * 255).roundToInt()
            trailPath.rewind()
            trail.forEachIndexed { index, point ->
                if (index == 0) trailPath.moveTo(point.x, point.y) else trailPath.lineTo(point.x, point.y)
            }
            canvas.drawPath(trailPath, trailPaint)
            trailPaint.style = Paint.Style.FILL
            canvas.drawCircle(head.x, head.y, trailPaint.strokeWidth, trailPaint)
            trailPaint.style = Paint.Style.STROKE
        }

        // Receive the whole surface so a drag never activates a bottom button on release.
        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelGesture()
                    activePointer = event.getPointerId(0)
                    downX = event.x
                    downY = event.y
                    movement.start(downX, downY, event.eventTime)
                    addTrailPoint(downX, downY)
                    pressedButton = buttons.firstOrNull { button ->
                        button.getHitRect(buttonBounds)
                        button.isEnabled && buttonBounds.contains(downX.toInt(), downY.toInt())
                    }?.also {
                        it.isPressed = true
                        InputFeedbacks.hapticFeedback(it)
                        (it as? TextEditingButton)?.let { button -> InputFeedbacks.soundEffect(button.soundEffect) }
                    }
                }
                MotionEvent.ACTION_MOVE -> updateMovement(event)
                MotionEvent.ACTION_UP -> {
                    updateMovement(event)
                    val button = pressedButton
                    val wasTap = !dragged && activePointer == event.getPointerId(0)
                    cancelGesture(clearTrail = false)
                    fadeTrail()
                    if (wasTap && button != null) {
                        InputFeedbacks.hapticFeedback(button, longPress = true, keyUp = true)
                        button.performClick()
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> cancelGesture()
            }
            return true
        }

        override fun onDetachedFromWindow() {
            cancelGesture()
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            buttons.forEach { button ->
                button.layoutParams.width = minOf(context.dp(64), width / buttons.size)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            buttons.forEachIndexed { index, button ->
                val x = ((width - button.measuredWidth) * index.toFloat() / (buttons.size - 1)).roundToInt()
                button.layout(x, height - button.measuredHeight, x + button.measuredWidth, height)
            }
        }
    }
}
