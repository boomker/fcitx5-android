/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.input.keyboard.keyboardHeightPercentOverride
import org.fxboomk.fcitx5.android.input.keyboard.resolveTextKeyboardLayout
import splitties.dimensions.dp

/** Static keyboard images keep preview keys from dispatching input or swallowing swipes. */
internal class KeyboardArrangementPickerUi(
    context: Context,
    private val theme: Theme,
    private val onSelected: (InputMethodEntry, ShuangpinKeyboardLayout) -> Unit,
) : HorizontalScrollView(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var ime: InputMethodEntry? = null
    private var previewsDirty = true
    private var previewSize = 0 to 0
    internal val cards = linkedMapOf<ShuangpinKeyboardLayout, FrameLayout>()
    private val checkMarks = linkedMapOf<ShuangpinKeyboardLayout, ImageView>()

    init {
        setPadding(dp(12), dp(12), dp(12), dp(16))
        clipToPadding = false
        isHorizontalScrollBarEnabled = true
        setBackgroundColor(theme.barColor)
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun show(ime: InputMethodEntry) {
        this.ime = ime
        previewsDirty = true
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val viewportWidth = MeasureSpec.getSize(widthMeasureSpec)
        val viewportHeight = MeasureSpec.getSize(heightMeasureSpec)
        val size = viewportWidth to viewportHeight
        if (ime != null && viewportWidth > 0 && viewportHeight > paddingTop + paddingBottom + dp(48) &&
            (previewsDirty || previewSize != size)
        ) {
            // Install cards before measuring the scroll content, including on the first frame.
            renderPreviews(viewportWidth, viewportHeight)
            previewSize = size
            previewsDirty = false
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun renderPreviews(viewportWidth: Int, viewportHeight: Int) {
        val currentIme = ime ?: return
        clearPreviews()
        // Leave part of the next card visible so horizontal browsing is discoverable.
        val cardWidth = (viewportWidth - paddingLeft - paddingRight - dp(32)).coerceAtLeast(dp(96))
        val cardHeight = viewportHeight - paddingTop - paddingBottom
        ShuangpinKeyboardLayout.entries.forEach { layout ->
            val label = context.getString(if (layout == ShuangpinKeyboardLayout.Compact18) {
                R.string.shuangpin_keyboard_layout_compact18
            } else R.string.shuangpin_keyboard_layout_full)
            val card = FrameLayout(context).apply {
                contentDescription = label
                isFocusable = true
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onSelected(currentIme, layout) }
                accessibilityDelegate = object : AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        AccessibilityNodeInfoCompat.wrap(info).setChecked(
                            if (host.isSelected) AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                            else AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                        )
                    }
                }
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageBitmap(renderKeyboard(currentIme, layout, viewportWidth))
            }
            card.addView(image, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                bottomMargin = dp(32)
            })
            card.addView(TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(theme.keyTextColor)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(32), Gravity.BOTTOM))
            val check = ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_check_24)
                setColorFilter(theme.genericActiveForegroundColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(theme.genericActiveBackgroundColor)
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(check, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
            cards[layout] = card
            checkMarks[layout] = check
            content.addView(card, LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                marginEnd = dp(12)
            })
        }
        val selected = ShuangpinKeyboardLayouts.read(currentIme.uniqueName)
        setSelected(selected)
        post {
            if (isAttachedToWindow && ime == currentIme) {
                cards[selected]?.let { scrollTo(it.left, 0) }
            }
        }
    }

    fun setSelected(layout: ShuangpinKeyboardLayout) {
        cards.forEach { (variant, card) ->
            val selected = variant == layout
            card.isSelected = selected
            card.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(theme.keyBackgroundColor)
                if (selected) setStroke(dp(2), theme.genericActiveBackgroundColor)
            }
            checkMarks.getValue(variant).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    fun clearPreviews() {
        previewsDirty = true
        content.removeAllViews()
        cards.clear()
        checkMarks.clear()
    }

    private fun renderKeyboard(
        ime: InputMethodEntry,
        layout: ShuangpinKeyboardLayout,
        previewWidth: Int,
    ): Bitmap {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = AppPrefs.getInstance().keyboard
        val heightPercent = TextKeyboard.textLayoutJson?.let { json ->
            resolveTextKeyboardLayout(
                json, ime.uniqueName, ime.displayName, ime.subMode.label,
                subModeName = ime.subMode.name, layoutVariant = layout,
            )?.keyboardHeightPercentOverride(landscape)
        } ?: if (landscape) prefs.keyboardHeightPercentLandscape.getValue()
        else prefs.keyboardHeightPercent.getValue()
        val previewHeight = (resources.displayMetrics.heightPixels * heightPercent / 100).coerceAtLeast(1)
        // Only the keyboard body is rendered: no toolbar or navigation bar.
        val keyboard = TextKeyboard(context, theme, ime).apply {
            layoutVariantOverride = layout
            background = theme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
            // Keep a neutral local Caps state so a locked real keyboard cannot
            // turn the Compact18 preview into its temporary uppercase 26-key layout.
            onInputMethodUpdate(ime)
        }
        keyboard.measure(
            MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY),
        )
        keyboard.layout(0, 0, previewWidth, previewHeight)
        return Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888).also {
            keyboard.draw(Canvas(it))
        }
    }
}
