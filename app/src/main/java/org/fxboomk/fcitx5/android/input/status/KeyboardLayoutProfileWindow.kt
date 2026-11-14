/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.RadioButton
import android.widget.ScrollView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.config.ConfigChangeListener
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutProfileManagerActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.TextKeyboardLayoutProfileOrder
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams

internal class KeyboardLayoutProfileWindow(
    private val onProfileSelected: (() -> Unit)? = null,
) : InputWindow.ExtendedInputWindow<KeyboardLayoutProfileWindow>() {
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private var attached = false
    private val onLayoutChanged: ConfigChangeListener = { if (attached) renderProfiles() }

    private val grid by lazy { GridLayout(context).apply { columnCount = 2 } }
    private val view by lazy {
        ScrollView(context).apply {
            setBackgroundColor(theme.barColor)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipToPadding = false
            addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }
    private val barExtension by lazy {
        context.horizontalLayout {
            add(ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
                contentDescription = context.getString(R.string.text_keyboard_layout_manage_title)
                setOnClickListener {
                    context.startActivity(Intent(context, TextKeyboardLayoutProfileManagerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(TextKeyboardLayoutProfileManagerActivity.EXTRA_TARGET_PROFILE, currentProfile())
                    })
                }
            }, lParams(dp(40), dp(40)))
        }
    }

    override val title: String get() = context.getString(R.string.status_area_keyboard_layout)
    override fun onCreateView(): View = view
    override fun onCreateBarExtension(): View = barExtension

    override fun onAttached() {
        attached = true
        ConfigProviders.addTextKeyboardLayoutListener(onLayoutChanged)
        renderProfiles()
    }

    override fun onDetached() {
        attached = false
        ConfigProviders.removeTextKeyboardLayoutListener(onLayoutChanged)
    }

    private fun currentProfile(): String = UserConfigFiles.normalizeTextKeyboardLayoutProfile(
        AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue()
    ) ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE

    private fun renderProfiles() {
        val selected = currentProfile()
        grid.removeAllViews()
        TextKeyboardLayoutProfileOrder.ordered(selected).forEachIndexed { index, profile ->
            val active = profile == selected
            val foreground = if (active) theme.genericActiveForegroundColor else theme.keyTextColor
            val button = RadioButton(context).apply {
                tag = profile
                text = if (profile == UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) {
                    context.getString(R.string.default_)
                } else profile
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isChecked = active
                setTextColor(foreground)
                buttonTintList = ColorStateList.valueOf(foreground)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(if (active) theme.genericActiveBackgroundColor else theme.keyBackgroundColor)
                }
                setOnClickListener { selectProfile(profile) }
            }
            grid.addView(button, GridLayout.LayoutParams(
                GridLayout.spec(index / 2, GridLayout.FILL),
                GridLayout.spec(index % 2, GridLayout.FILL, 1f),
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                val margin = context.dp(4)
                setMargins(margin, margin, margin, margin)
            })
        }
    }

    internal var isSelectingProfile = false
        private set

    private fun selectProfile(profile: String) {
        val wm = windowManager
        if (profile != currentProfile()) {
            // The preference listener refreshes the layout and its size synchronously.
            isSelectingProfile = true
            try {
                AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.setValue(profile)
            } finally {
                isSelectingProfile = false
            }
        }
        onProfileSelected?.invoke()
        if (wm.currentWindowOrNull() !is KeyboardWindow) wm.attachWindow(KeyboardWindow)
    }
}
