/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import android.app.Dialog
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import org.fxboomk.fcitx5.android.R
import java.util.function.Consumer
import timber.log.Timber

/**
 * A non-interactive blur surface behind the measured keyboard, rather than behind the fullscreen
 * IME decor. The parent window continues to own input, insets and its show/hide animation.
 *
 * Call [dispose] when replacing the input view. Calls and callbacks must remain on the UI thread.
 */
internal class KeyboardGlassWindow(
    private val anchor: View,
    private val cornerRadii: () -> FloatArray,
    private val blurRadiusPx: () -> Int,
    private val onBlurAvailabilityChanged: (Boolean) -> Unit
) {
    private var controller: BlurController? = null

    fun attach() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onBlurAvailabilityChanged(false)
            return
        }
        if (controller != null) return
        controller = BlurController(anchor, cornerRadii, blurRadiusPx, onBlurAvailabilityChanged)
            .also { it.attach() }
    }

    fun dispose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) controller?.dispose()
        controller = null
    }

    /** Also call when the parent window becomes hidden and no longer schedules a draw. */
    fun refresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) controller?.refresh()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class BlurController(
        private val anchor: View,
        private val cornerRadii: () -> FloatArray,
        private val blurRadiusPx: () -> Int,
        private val notifyAvailability: (Boolean) -> Unit
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val manager = anchor.context.getSystemService(WindowManager::class.java)
        private val position = IntArray(2)
        private var observer: ViewTreeObserver? = null
        private var availabilityListener: Consumer<Boolean>? = null
        private var generation = 0
        private var observing = false
        private var refreshing = false
        private var disposed = false
        private var systemBlurEnabled = false
        private var reportedAvailable = false
        private var dialog: Dialog? = null
        private var dialogToken: IBinder? = null
        private var rejectedToken: IBinder? = null
        private var lastFrame: Frame? = null

        private data class Frame(
            val x: Int,
            val y: Int,
            val width: Int,
            val height: Int,
            val blurRadius: Int,
            val radii: List<Float>
        )

        fun attach() {
            anchor.addOnAttachStateChangeListener(this)
            if (anchor.isAttachedToWindow) onViewAttachedToWindow(anchor)
        }

        override fun onViewAttachedToWindow(view: View) {
            if (disposed) return
            observer = anchor.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            observeAvailability()
            refresh()
        }

        override fun onViewDetachedFromWindow(view: View) {
            stopObserving()
            dismissSurface()
            rejectedToken = null
        }

        override fun onPreDraw(): Boolean {
            refresh()
            return true
        }

        private fun observeAvailability() {
            if (observing) return
            val currentGeneration = ++generation
            val listener = Consumer<Boolean> { enabled ->
                if (disposed || currentGeneration != generation) return@Consumer
                systemBlurEnabled = enabled
                // Registration is permitted to invoke its initial callback synchronously.
                if (!refreshing) refresh()
            }
            availabilityListener = listener
            observing = true
            refreshing = true
            try {
                manager.addCrossWindowBlurEnabledListener(anchor.context.mainExecutor, listener)
            } catch (error: RuntimeException) {
                // A vendor implementation may reject this otherwise optional visual feature.
                observing = false
                availabilityListener = null
                systemBlurEnabled = false
                Timber.w(error, "Unable to observe keyboard background blur")
            } finally {
                refreshing = false
            }
        }

        fun refresh() {
            if (disposed || refreshing) return
            refreshing = true
            try {
                val token = anchor.windowToken
                if (!systemBlurEnabled || !anchor.isAttachedToWindow || !anchor.isShown ||
                    anchor.windowVisibility != View.VISIBLE || token == null ||
                    anchor.width <= 0 || anchor.height <= 0
                ) {
                    dismissSurface()
                    return
                }
                if (dialogToken != token) dismissSurface()
                if (rejectedToken == token) return
                // LAYOUT_IN_SCREEN places this subwindow in display coordinates. Window-local
                // coordinates would count a non-edge-to-edge parent's status-bar inset twice.
                anchor.getLocationOnScreen(position)
                val frame = Frame(
                    position[0], position[1], anchor.width, anchor.height,
                    blurRadiusPx().coerceAtLeast(0),
                    cornerRadii().copyOf(8).map { radius ->
                        if (radius.isFinite()) radius.coerceAtLeast(0f) else 0f
                    }
                )
                try {
                    if (dialog == null) createSurface(token, frame)
                    else if (lastFrame != frame) updateSurface(frame)
                    reportAvailable(dialog?.isShowing == true)
                } catch (error: WindowManager.BadTokenException) {
                    rejectSurface(token, error)
                } catch (error: IllegalArgumentException) {
                    rejectSurface(token, error)
                } catch (error: SecurityException) {
                    rejectSurface(token, error)
                }
            } finally {
                refreshing = false
            }
        }

        private fun createSurface(token: IBinder, frame: Frame) {
            val surface = Dialog(anchor.context, R.style.Theme_KeyboardGlass)
            surface.requestWindowFeature(Window.FEATURE_NO_TITLE)
            surface.setCancelable(false)
            surface.setContentView(View(anchor.context))
            val window = checkNotNull(surface.window)
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // The IME's inset frame can still describe the old keyboard position while
                    // the keyboard is moving via translation. Do not clamp this bounded surface
                    // back into that frame; its measured size and position define the blur area.
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            window.attributes = window.attributes.apply {
                this.token = token
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                windowAnimations = 0
                title = WINDOW_TITLE
                dimAmount = 0f
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                x = frame.x
                y = frame.y
                width = frame.width
                height = frame.height
            }
            // Assign before show so failure cleanup also covers partially attached dialogs.
            dialog = surface
            dialogToken = token
            updateSurface(frame)
            surface.show()
            // Dialog.show may replace width/height based on its theme's floating-window defaults.
            updateSurface(frame)
        }

        private fun updateSurface(frame: Frame) {
            val window = dialog?.window ?: return
            window.attributes = window.attributes.apply {
                x = frame.x
                y = frame.y
                width = frame.width
                height = frame.height
            }
            window.setBackgroundDrawable(BlurBackground(frame.radii.toFloatArray()))
            window.setBackgroundBlurRadius(frame.blurRadius)
            lastFrame = frame
        }

        private fun rejectSurface(token: IBinder, error: RuntimeException) {
            rejectedToken = token
            dismissSurface()
            Timber.w(error, "Unable to show keyboard background blur")
        }

        private fun reportAvailable(available: Boolean) {
            if (reportedAvailable == available) return
            reportedAvailable = available
            notifyAvailability(available)
        }

        private fun dismissSurface() {
            val surface = dialog
            dialog = null
            dialogToken = null
            lastFrame = null
            try {
                surface?.window?.setBackgroundBlurRadius(0)
                surface?.dismiss()
            } catch (error: IllegalArgumentException) {
                // The parent token can disappear before the detach notification is delivered.
                Timber.d(error, "Keyboard blur parent was already removed")
            } finally {
                reportAvailable(false)
            }
        }

        private fun stopObserving() {
            generation++
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            availabilityListener?.let { listener ->
                if (observing) {
                    try {
                        manager.removeCrossWindowBlurEnabledListener(listener)
                    } catch (error: RuntimeException) {
                        Timber.w(error, "Unable to remove keyboard background blur listener")
                    }
                }
            }
            availabilityListener = null
            observing = false
            systemBlurEnabled = false
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            anchor.removeOnAttachStateChangeListener(this)
            stopObserving()
            dismissSurface()
            rejectedToken = null
        }
    }

    internal companion object {
        const val WINDOW_TITLE = "Fcitx keyboard glass"
    }

    /**
     * DecorView derives native blur clipping from a single round-rect outline radius. A general
     * corner path would give it radius zero, spilling blur into the top corners. Using the largest
     * corner keeps native blur inside the visible keyboard; the bottom can be slightly less blurred
     * when only the top corners are rounded. The keyboard itself retains its exact eight radii.
     */
    private class BlurBackground(radii: FloatArray) : GradientDrawable() {
        private val blurCornerRadius = radii.maxOrNull() ?: 0f

        init {
            setColor(Color.TRANSPARENT)
            cornerRadii = radii
        }

        override fun getOutline(outline: Outline) {
            val radius = blurCornerRadius.coerceAtMost(minOf(bounds.width(), bounds.height()) / 2f)
            outline.setRoundRect(bounds, radius)
            outline.alpha = 0f
        }
    }
}
