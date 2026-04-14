/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.unset
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.parentId
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.existingOrNewId
import splitties.views.imageResource
import splitties.views.padding
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

abstract class KeyView(
    ctx: Context,
    var theme: Theme,
    val def: KeyDef.Appearance,
    horizontalGapScale: Float = 1f
) :
    CustomGestureView(ctx) {

    val bordered: Boolean
    val borderStroke: Boolean
    val rippled: Boolean
    val radius: Float
    val hMargin: Int
    val vMargin: Int

    init {
        val prefs = ThemeManager.prefs
        bordered = prefs.keyBorder.getValue()
        borderStroke = prefs.keyBorderStroke.getValue()
        rippled = prefs.keyRippleEffect.getValue()
        radius = dp(prefs.keyRadius.getValue().toFloat())
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hMarginPref =
            if (landscape) prefs.keyHorizontalMarginLandscape else prefs.keyHorizontalMargin
        val vMarginPref =
            if (landscape) prefs.keyVerticalMarginLandscape else prefs.keyVerticalMargin
        val hScale = horizontalGapScale.coerceIn(0.5f, 1f)
        val hMarginValue = (hMarginPref.getValue().toFloat() * hScale).roundToInt().coerceAtLeast(0)
        hMargin = if (def.margin) dp(hMarginValue) else 0
        vMargin = if (def.margin) dp(vMarginPref.getValue()) else 0
    }

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false
    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun invalidateCachedBounds() {
        boundsValid = false
    }

    /**
     * KeyView content left margin, in percentage of parent width
     */
    @FloatRange(0.0, 1.0)
    var layoutMarginLeft = 0f

    /**
     * KeyView content right margin, in percentage of parent width
     */
    @FloatRange(0.0, 1.0)
    var layoutMarginRight = 0f

    /**
     * [KeyView] contains 2 parts: `TouchEventView` and `AppearanceView`.
     *
     * `TouchEventView` is the outer [CustomGestureView] that handles touch events.
     *
     * `AppearanceView` in the inner [ConstraintLayout], it can be smaller than its parent,
     * and holds the [bounds] for popup.
     */
    protected val appearanceView = constraintLayout {
        // sync any state from parent
        isDuplicateParentStateEnabled = true
    }

    init {
        // trigger setEnabled(true)
        isEnabled = true
        isClickable = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = View.generateViewId()
            tag = def.viewId
        }
        // key border
        if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            val defaultBkgColor = when (def.variant) {
                Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
                Variant.Alternative -> theme.altKeyBackgroundColor
                Variant.Accent -> theme.accentKeyBackgroundColor
            }
            val bkgColor = resolveBackgroundColor(theme, defaultBkgColor)
            val borderOrShadowWidth = dp(1)
            // background: key border
            appearanceView.background = if (borderStroke) borderedKeyBackgroundDrawable(
                bkgColor, resolveShadowColor(theme),
                radius, borderOrShadowWidth, hMargin, vMargin
            ) else shadowedKeyBackgroundDrawable(
                bkgColor, resolveShadowColor(theme),
                radius, borderOrShadowWidth, hMargin, vMargin
            )
            // foreground: press highlight or ripple
            setupPressHighlight()
        } else {
            // normal press highlight for keys without special background
            // special background is handled in `onSizeChanged()`
            if (def.border != Border.Special) {
                setupPressHighlight()
            }
        }
        add(appearanceView, lParams(matchParent, matchParent))
    }

    private fun resolveMonetColor(resourceName: String?): Int? {
        val name = resourceName?.takeIf { it.isNotBlank() } ?: return null
        val colorResId = context.resources.getIdentifier(name, "color", "android")
        if (colorResId == 0) return null
        return runCatching { context.getColor(colorResId) }.getOrNull()
    }

    private fun resolveColorOverride(staticColor: Int?, monetResourceName: String?): Int? {
        return resolveMonetColor(monetResourceName) ?: staticColor
    }

    protected fun resolveBackgroundColor(theme: Theme, defaultColor: Int): Int {
        return resolveColorOverride(def.backgroundColor, def.backgroundColorMonet) ?: defaultColor
    }

    protected fun resolveShadowColor(theme: Theme): Int {
        return resolveColorOverride(def.shadowColor, def.shadowColorMonet) ?: theme.keyShadowColor
    }

    protected fun resolveTextColor(defaultColor: Int): Int {
        return resolveColorOverride(def.textColor, def.textColorMonet) ?: defaultColor
    }

    protected fun resolveAltTextColor(defaultColor: Int): Int {
        return resolveColorOverride(def.altTextColor, def.altTextColorMonet) ?: defaultColor
    }

    private fun setupPressHighlight(mask: Drawable? = null) {
        appearanceView.foreground = if (rippled) {
            RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor), null,
                // ripple should be masked with an opaque color
                mask ?: highlightMaskDrawable(Color.WHITE)
            )
        } else if (bordered && borderStroke) {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    borderedKeyBackgroundDrawable(
                        Color.TRANSPARENT, resolveShadowColor(theme),
                        radius, dp(2), hMargin, vMargin
                    )
                )
            }
        } else {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    // use mask drawable as highlight directly
                    mask ?: highlightMaskDrawable(theme.keyPressHighlightColor)
                )
            }
        }
    }

    private fun highlightMaskDrawable(@ColorInt color: Int): Drawable {
        return if (bordered) insetRadiusDrawable(hMargin, vMargin, radius, color)
        else InsetDrawable(ColorDrawable(color), hMargin, vMargin, hMargin, vMargin)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        appearanceView.alpha = if (enabled) 1f else styledFloat(android.R.attr.disabledAlpha)
    }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { appearanceView.getLocationInWindow(it) }
        cachedBounds.set(x, y, x + appearanceView.width, y + appearanceView.height)
        boundsValid = true
    }

    open fun setTextScale(scale: Float) {
        // default implementation does nothing
    }

    protected open fun onAppearanceLayoutChanged(width: Int, height: Int) {
        // default implementation does nothing
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        boundsValid = false
        if (layoutMarginLeft != 0f || layoutMarginRight != 0f) {
            val w = right - left
            val h = bottom - top
            val layoutWidth = (w * (1f - layoutMarginLeft - layoutMarginRight)).roundToInt()
            appearanceView.updateLayoutParams<LayoutParams> {
                leftMargin = (w * layoutMarginLeft).roundToInt()
                rightMargin = (w * layoutMarginRight).roundToInt()
            }
            // sets `measuredWidth` and `measuredHeight` of `AppearanceView`
            // https://developer.android.com/guide/topics/ui/how-android-draws#measure
            appearanceView.measure(
                MeasureSpec.makeMeasureSpec(layoutWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
        super.onLayout(changed, left, top, right, bottom)
        onAppearanceLayoutChanged(appearanceView.width, appearanceView.height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (bordered) return
        when (def.viewId) {
            R.id.button_space -> {
                val bkgRadius = dp(3f)
                val minHeight = dp(26)
                val hInset = dp(10)
                val vInset = if (h < minHeight) 0 else min((h - minHeight) / 2, dp(16))
                appearanceView.background = insetRadiusDrawable(
                    hInset, vInset, bkgRadius, resolveBackgroundColor(theme, theme.spaceBarColor)
                )
                // InsetDrawable sets padding to container view; remove padding to prevent text from bing clipped
                appearanceView.padding = 0
                // apply press highlight for background area
                setupPressHighlight(
                    insetRadiusDrawable(
                        hInset, vInset, bkgRadius,
                        if (rippled) Color.WHITE else theme.keyPressHighlightColor
                    )
                )
            }
            R.id.button_return -> {
                val drawableSize = min(min(w, h), dp(35))
                val hInset = (w - drawableSize) / 2
                val vInset = (h - drawableSize) / 2
                appearanceView.background = insetOvalDrawable(
                    hInset, vInset, resolveBackgroundColor(theme, theme.accentKeyBackgroundColor)
                )
                appearanceView.padding = 0
                setupPressHighlight(
                    insetOvalDrawable(
                        hInset, vInset, if (rippled) Color.WHITE else theme.keyPressHighlightColor
                    )
                )
            }
        }
    }

    /**
     * Update theme without rebuilding view
     */
    open fun updateTheme(newTheme: Theme) {
        theme = newTheme

        // Update key background (only when bordered)
        if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            val defaultBkgColor = when (def.variant) {
                Variant.Normal, Variant.AltForeground -> newTheme.keyBackgroundColor
                Variant.Alternative -> newTheme.altKeyBackgroundColor
                Variant.Accent -> newTheme.accentKeyBackgroundColor
            }
            val bkgColor = resolveBackgroundColor(newTheme, defaultBkgColor)
            val borderOrShadowWidth = dp(1)
            appearanceView.background = if (borderStroke) borderedKeyBackgroundDrawable(
                bkgColor, resolveShadowColor(newTheme),
                radius, borderOrShadowWidth, hMargin, vMargin
            ) else shadowedKeyBackgroundDrawable(
                bkgColor, resolveShadowColor(newTheme),
                radius, borderOrShadowWidth, hMargin, vMargin
            )
        }
        // Update press highlight for all keys
        setupPressHighlight()

        // Update special backgrounds for spaceBar and returnKey
        val w = appearanceView.width
        val h = appearanceView.height
        if (w > 0 && h > 0) {
            onSizeChanged(w, h, w, h)
        }
    }
}

@SuppressLint("ViewConstructor")
open class TextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.Text,
    horizontalGapScale: Float = 1f
) :
    KeyView(ctx, theme, def, horizontalGapScale) {
    private val baseMainTextSizeSp: Float = when (def.viewId) {
        R.id.button_space -> def.textSize
        R.id.button_layout_switch -> def.textSize
        else -> org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "key_main_font", def.textSize
        )
    }

    val mainText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        background = null
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMainTextSizeSp)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        // Set font key for batch setting in BaseKeyboard.reloadLayout()
        fontKey = "key_main_font"
        setTypeface(typeface, def.textStyle)
        setTextColor(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            if (def.viewId == R.id.button_space) {
                val insetPadding = dp(10)
                mainText.setPadding(insetPadding + hMargin, 0, insetPadding + hMargin, 0)
                add(mainText, lParams(matchParent, wrapContent) {
                    centerInParent()
                })
            } else {
                mainText.setPadding(hMargin, 0, hMargin, 0)
                add(mainText, lParams(matchParent, wrapContent) {
                    centerInParent()
                })
            }
        }
    }

    override fun setTextScale(scale: Float) {
        if (def is KeyDef.Appearance.Text) {
            mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseMainTextSizeSp * scale)
            mainText.requestLayout()
        }
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        mainText.setTextColor(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> newTheme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
    }
}

@SuppressLint("ViewConstructor")
class AltTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.AltText,
    horizontalGapScale: Float = 1f
) :
    TextKeyView(ctx, theme, def, horizontalGapScale) {
    private enum class AltTextLayoutMode {
        TopRight,
        Bottom,
        Hidden
    }

    private val baseAltTextSizeSp = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
        "key_alt_font", 10.666667f
    )
    private var lastLayoutMode: AltTextLayoutMode? = null

    val altText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp)
        // Set font key for batch setting in BaseKeyboard.reloadLayout()
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            add(altText, lParams(0, wrapContent))
        }
        applyLayout()
    }

    override fun setTextScale(scale: Float) {
        super.setTextScale(scale)
        altText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp * scale)
        altText.requestLayout()
        lastLayoutMode = null
        applyLayout()
    }

    private fun applyTopRightAltTextPosition() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            topMargin = 0
            bottomToTop = unset
            // set
            topToTop = parentId
            bottomToBottom = parentId
        }
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            width = 0
            bottomToBottom = unset; bottomMargin = 0
            // set
            topToTop = parentId; topMargin = vMargin
            leftToLeft = parentId; leftMargin = hMargin
            rightToRight = parentId; rightMargin = hMargin
        }
        altText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun applyBottomAltTextPosition() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            bottomToBottom = unset
            // set
            topToTop = parentId; topMargin = vMargin
            bottomToTop = altText.existingOrNewId
        }
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            width = 0
            topToTop = unset; topMargin = 0
            leftMargin = hMargin
            rightMargin = hMargin
            // set
            leftToLeft = parentId
            rightToRight = parentId
            bottomToBottom = parentId; bottomMargin = vMargin + dp(2)
        }
        altText.gravity = Gravity.CENTER
    }

    private fun applyNoAltTextPosition() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            topMargin = 0
            bottomToTop = unset
            // set
            topToTop = parentId
            bottomToBottom = parentId
        }
        altText.visibility = View.GONE
        altText.gravity = Gravity.CENTER
    }

    private fun resolveLayoutMode(keyHeight: Int): AltTextLayoutMode {
        val pref = ThemeManager.prefs.punctuationPosition.getValue()
        if (pref == PunctuationPosition.None) return AltTextLayoutMode.Hidden

        val preferred = when (pref) {
            PunctuationPosition.TopRight -> AltTextLayoutMode.TopRight
            PunctuationPosition.Bottom -> AltTextLayoutMode.Bottom
            PunctuationPosition.None -> AltTextLayoutMode.Hidden
        }
        if (keyHeight <= 0) return preferred

        val contentHeight = keyHeight - vMargin * 2
        val mainHeight = mainText.paint.run { fontMetrics.bottom - fontMetrics.top }
        val altHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
        // Keep a small guard gap but avoid over-conservative fallback to top-right.
        val compactMinHeight = max(mainHeight, altHeight + dp(1))
        val stackedMinHeight = mainHeight + altHeight + dp(1)

        return when (preferred) {
            AltTextLayoutMode.Bottom -> when {
                contentHeight >= stackedMinHeight -> AltTextLayoutMode.Bottom
                // Keep bottom layout in the compact zone if it was already selected.
                // This prevents top/bottom jitter when measured height fluctuates near threshold.
                contentHeight >= compactMinHeight && lastLayoutMode == AltTextLayoutMode.Bottom ->
                    AltTextLayoutMode.Bottom
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.TopRight -> when {
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.Hidden -> AltTextLayoutMode.Hidden
        }
    }

    private fun applyLayout(keyHeight: Int = appearanceView.height) {
        val mode = resolveLayoutMode(keyHeight)
        if (mode == lastLayoutMode) return
        lastLayoutMode = mode
        when (mode) {
            AltTextLayoutMode.Bottom -> applyBottomAltTextPosition()
            AltTextLayoutMode.TopRight -> applyTopRightAltTextPosition()
            AltTextLayoutMode.Hidden -> applyNoAltTextPosition()
        }
    }

    fun shouldTriggerAltBySwipe(totalY: Int, fallback: SwipeSymbolDirection): Boolean {
        if (totalY == 0) return false
        return when (lastLayoutMode ?: resolveLayoutMode(appearanceView.height)) {
            AltTextLayoutMode.Bottom -> totalY > 0
            AltTextLayoutMode.TopRight -> totalY < 0
            AltTextLayoutMode.Hidden -> fallback.checkY(totalY)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        lastLayoutMode = null
        applyLayout()
    }

    override fun onAppearanceLayoutChanged(width: Int, height: Int) {
        applyLayout(height)
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        altText.setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
        lastLayoutMode = null
        applyLayout()
    }
}

@SuppressLint("ViewConstructor")
class ImageKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.Image,
    horizontalGapScale: Float = 1f
) :
    KeyView(ctx, theme, def, horizontalGapScale) {
    val img = imageView { configure(theme, def.src, def.variant) }.apply {
        imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            add(img, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        img.imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> newTheme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
    }
}

private fun ImageView.configure(theme: Theme, @DrawableRes src: Int, variant: Variant) = apply {
    isClickable = false
    isFocusable = false
    imageTintList = ColorStateList.valueOf(
        when (variant) {
            Variant.Normal -> theme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
            Variant.Accent -> theme.accentKeyTextColor
        }
    )
    imageResource = src
}

@SuppressLint("ViewConstructor")
class ImageTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.ImageText,
    horizontalGapScale: Float = 1f
) :
    TextKeyView(ctx, theme, def, horizontalGapScale) {
    val img = imageView {
        configure(theme, def.src, def.variant)
        imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            add(img, lParams(dp(13), dp(13)))
        }
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            bottomToBottom = parentId
            bottomMargin = vMargin + dp(4)
            topToTop = unset
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            topToTop = parentId
        }
        updateMargins(resources.configuration.orientation)
    }

    private fun updateMargins(orientation: Int) {
        when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = vMargin + dp(2)
                }
                img.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = vMargin + dp(4)
                }
            }
            else -> {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = vMargin + dp(4)
                }
                img.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = vMargin + dp(8)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        updateMargins(newConfig.orientation)
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        img.imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> newTheme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
    }
}
