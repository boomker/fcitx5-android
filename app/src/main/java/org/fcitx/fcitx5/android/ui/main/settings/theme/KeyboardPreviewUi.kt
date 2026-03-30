/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.preview.PreviewInputMethodEntry
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.imageDrawable

class KeyboardPreviewUi(override val ctx: Context, val theme: Theme) : Ui {

    var intrinsicWidth: Int = -1
        private set

    var intrinsicHeight: Int = -1
        private set

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val keyboardHeightPercent by keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape by keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding by keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape by keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding by keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape by keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (ctx.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }
            return ctx.dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (ctx.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }
            return ctx.dp(value)
        }

    private val navbarBackground = ThemeManager.prefs.navbarBackground
    private val keyBorder by ThemeManager.prefs.keyBorder

    private val navbarBkgChangeListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        recalculateSize()
    }

    private val bkg = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val barHeight = ctx.dp(40)
    private val fakeKawaiiBar = view(::View)

    private var keyboardWidth = -1
    private var keyboardHeight = -1
    private var sizeScale = 1f
    private lateinit var fakeKeyboardWindow: TextKeyboard
    private var currentTheme: Theme? = null
    private var isUpdatingTheme = false

    private val fakeInputView = constraintLayout {
        add(bkg, lParams {
            centerInParent()
        })
        add(fakeKawaiiBar, lParams(matchConstraints, dp(40)) {
            topOfParent()
            centerHorizontally()
        })
    }

    override val root = object : FrameLayout(ctx) {
        init {
            add(fakeInputView, lParams())
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            recalculateSize()
            onSizeMeasured?.invoke(intrinsicWidth, intrinsicHeight)
            navbarBackground.registerOnChangeListener(navbarBkgChangeListener)
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            recalculateSize()
        }

        override fun onDetachedFromWindow() {
            navbarBackground.unregisterOnChangeListener(navbarBkgChangeListener)
            super.onDetachedFromWindow()
        }
    }

    var onSizeMeasured: ((Int, Int) -> Unit)? = null

    private fun keyboardWindowAspectRatio(): Pair<Int, Int> {
        val resources = ctx.resources
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels
        val hPercent = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
            else -> keyboardHeightPercent
        }
        return w to (h * hPercent / 100)
    }

    init {
        val (w, h) = keyboardWindowAspectRatio()
        keyboardWidth = w
        keyboardHeight = h
        setTheme(theme)
        // Apply initial size scale
        recalculateSize()
    }

    fun recalculateSize() {
        val (baseW, baseH) = keyboardWindowAspectRatio()
        val scale = sizeScale.coerceIn(0.35f, 1f)
        keyboardWidth = (baseW * scale).toInt().coerceAtLeast(1)
        keyboardHeight = (baseH * scale).toInt().coerceAtLeast(1)
        fakeKeyboardWindow.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = keyboardHeight
            horizontalMargin = keyboardSidePaddingPx
        }
        intrinsicWidth = keyboardWidth
        // KawaiiBar height + WindowManager view height
        intrinsicHeight = barHeight + keyboardHeight
        // extra bottom padding
        intrinsicHeight += keyboardBottomPaddingPx
        // windowInsets navbar padding
        if (navbarBackground.getValue() == NavbarBackground.Full) {
            ViewCompat.getRootWindowInsets(root)?.also {
                // IME window has different navbar height when system navigation in "gesture navigation" mode
                // thus the inset from Activity root window is unreliable
                if (it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom > 0 ||
                    // in case navigation hint was hidden ...
                    it.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom > 0
                ) {
                    intrinsicHeight += ctx.navbarFrameHeight()
                }
            }
        }
        // fakeInputView size should match the calculated intrinsic size
        fakeInputView.updateLayoutParams<FrameLayout.LayoutParams> {
            width = intrinsicWidth
            height = intrinsicHeight
        }
    }

    fun setSizeScale(scale: Float) {
        val clamped = scale.coerceIn(0.35f, 1f)
        if (sizeScale == clamped) return
        sizeScale = clamped
        recalculateSize()
        // Also adjust text scale to match the keyboard size scale
        // This ensures text doesn't look too large when keyboard is scaled down
        if (this::fakeKeyboardWindow.isInitialized) {
            fakeKeyboardWindow.setTextScale(sizeScale)
        }
    }

    fun setBackground(drawable: Drawable) {
        bkg.imageDrawable = drawable
    }

    fun setTheme(theme: Theme, background: Drawable? = null, forceRefresh: Boolean = false) {
        // Prevent re-entrant calls that could cause infinite loops
        if (isUpdatingTheme) return
        
        val sameTheme = currentTheme != null && currentTheme == theme

        setBackground(background ?: theme.backgroundDrawable(keyBorder))

        // First-time setup: create new keyboard view
        if (!this::fakeKeyboardWindow.isInitialized) {
            isUpdatingTheme = true
            fakeKeyboardWindow = TextKeyboard(ctx, theme)
            currentTheme = theme

            // Match KawaiiBar behavior: use barColor for Builtin themes without border
            fakeKawaiiBar.backgroundColor = if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor

            fakeInputView.apply {
                add(fakeKeyboardWindow, lParams(matchConstraints, keyboardHeight) {
                    below(fakeKawaiiBar)
                    centerHorizontally(keyboardSidePaddingPx)
                })
            }

            fakeKeyboardWindow.post {
                fakeKeyboardWindow.onAttach()
                fakeKeyboardWindow.onInputMethodUpdate(PreviewInputMethodEntry.create())
                fakeKeyboardWindow.setTextScale(sizeScale)
                fakeKeyboardWindow.requestLayout()
                fakeKeyboardWindow.invalidate()
                isUpdatingTheme = false
            }
        } else {
            // Update KawaiiBar background color
            fakeKawaiiBar.backgroundColor = if (!keyBorder && theme is Theme.Builtin) theme.barColor else theme.backgroundColor

            fakeKeyboardWindow.post {
                try {
                    isUpdatingTheme = true
                    if (forceRefresh || sameTheme) {
                        // Config changed: rebuild layout
                        // refreshStyle() reads latest config from ThemeManager.prefs
                        fakeKeyboardWindow.refreshStyle()
                    } else {
                        // Theme changed: update colors without rebuilding
                        currentTheme = theme
                        fakeKeyboardWindow.updateTheme(theme)
                    }
                } finally {
                    isUpdatingTheme = false
                }
            }
        }
    }
}
