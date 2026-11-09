/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.editing

import kotlin.math.abs

internal class GestureMovementAccumulator(
    private val horizontalStep: Float,
    private val verticalStep: Float,
    private val density: Float = 1f,
) {
    data class Movement(val horizontal: Int = 0, val vertical: Int = 0)

    private var active = false
    private var lastX = 0f
    private var lastY = 0f
    private var remainingX = 0f
    private var remainingY = 0f
    private var lastEventTimeMs: Long? = null

    init {
        require(horizontalStep.isFinite() && horizontalStep > 0f)
        require(verticalStep.isFinite() && verticalStep > 0f)
        require(density.isFinite() && density > 0f)
    }

    fun start(x: Float, y: Float, eventTimeMs: Long? = null) {
        cancel()
        active = true
        lastX = x
        lastY = y
        lastEventTimeMs = eventTimeMs
    }

    fun move(x: Float, y: Float, eventTimeMs: Long? = null): Movement {
        if (!active) return Movement()
        val deltaX = x - lastX
        val deltaY = y - lastY
        val elapsedMs = if (eventTimeMs != null && lastEventTimeMs != null) {
            eventTimeMs - checkNotNull(lastEventTimeMs)
        } else {
            0L
        }
        if (elapsedMs > 0L) {
            // A direction change starts a fresh fractional step, without the old direction's boost.
            if (deltaX * remainingX < 0f) remainingX = 0f
            if (deltaY * remainingY < 0f) remainingY = 0f
        }
        remainingX += deltaX * acceleration(deltaX, elapsedMs)
        remainingY += deltaY * acceleration(deltaY, elapsedMs)
        lastX = x
        lastY = y
        lastEventTimeMs = eventTimeMs

        val horizontal = remainingX / horizontalStep
        val vertical = remainingY / verticalStep
        return when {
            abs(horizontal) >= 1f && abs(horizontal) >= abs(vertical) -> {
                val steps = horizontal.toInt()
                remainingX -= steps * horizontalStep
                // Discard minor perpendicular drift; the next movement may change axis.
                remainingY = 0f
                Movement(horizontal = steps)
            }
            abs(vertical) >= 1f -> {
                val steps = vertical.toInt()
                remainingY -= steps * verticalStep
                remainingX = 0f
                Movement(vertical = steps)
            }
            else -> Movement()
        }
    }

    private fun acceleration(distance: Float, elapsedMs: Long): Float {
        if (elapsedMs <= 0L) return 1f
        val speedDpPerSecond = abs(distance) * 1000.0 / elapsedMs / density
        // Keep slow movement precise and increase continuously to a bounded fast-swipe gain.
        val progress = ((speedDpPerSecond - 300.0) / 1200.0).coerceIn(0.0, 1.0)
        return (1.0 + 3.0 * progress).toFloat()
    }

    fun cancel() {
        active = false
        remainingX = 0f
        remainingY = 0f
        lastEventTimeMs = null
    }
}
