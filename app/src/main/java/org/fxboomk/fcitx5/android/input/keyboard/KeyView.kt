/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
@file:Suppress("DiscouragedApi")

package org.fxboomk.fcitx5.android.input.keyboard

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
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.UppercasePosition
import org.fxboomk.fcitx5.android.data.theme.resolveThemeColorReference
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fxboomk.fcitx5.android.utils.styledFloat
import org.fxboomk.fcitx5.android.utils.unset
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

enum class AltTextSwipeTarget {
    Primary,
    Secondary,
    Uppercase
}

interface SwipeHintAwareKeyView {
    fun selectAltTextSwipeTarget(totalY: Int): AltTextSwipeTarget?

    /**
     * The swipe target used when the swipe direction setting resolves to the
     * "second" label of this key. Defaults to [AltTextSwipeTarget.Secondary];
     * keys whose second label is the uppercase hint report [AltTextSwipeTarget.Uppercase].
     */
    fun secondarySwipeTarget(): AltTextSwipeTarget = AltTextSwipeTarget.Secondary
}

/**
 * Implemented by [KeyView] subclasses that render an [ImageView] icon (e.g. the
 * caps/shift key). Attaching a swipe label ("划动标签") to such a key changes its
 * concrete class from [ImageKeyView] to [ImageAltTextKeyView], so callers that
 * need to update the icon must look the key up by this interface rather than by
 * a concrete type, otherwise the labelled variant is missed and its icon never
 * refreshes.
 */
interface KeyViewWithImage {
    val img: ImageView
}

abstract class KeyView(
    ctx: Context,
    var theme: Theme,
    val def: KeyDef.Appearance,
    horizontalGapScale: Float = 1f
) :
    CustomGestureView(ctx) {

    protected fun punctuationPositionForKey(): PunctuationPosition {
        val character = (def as? KeyDef.Appearance.AltText)?.character?.singleOrNull()
        return if (character != null && (character in 'a'..'z' || character in 'A'..'Z')) {
            ThemeManager.prefs.punctuationPosition.getValue()
        } else {
            // Other keys keep the default position; explicit layout overrides
            // and physical up/down swipe labels are resolved by their views.
            PunctuationPosition.Bottom
        }
    }

    internal var useFloatingGboardSideKeyStyle: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            refreshGboardSideKeyShape()
        }

    private fun isMainKeyAreaById(viewId: Int): Boolean {
        return viewId == R.id.button_space ||
                viewId == R.id.button_lang
    }

    private fun resolvedViewId(): Int {
        return (tag as? Int) ?: def.viewId
    }

    private fun resolveKeyBackgroundColor(theme: Theme): Int {
        if (isMainKeyAreaById(def.viewId)) {
            return theme.keyBackgroundColor
        }
        return when (def.variant) {
            Variant.Normal -> theme.keyBackgroundColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyBackgroundColor
            Variant.Accent -> theme.accentKeyBackgroundColor
        }
    }

    val bordered: Boolean
    val borderStroke: Boolean
    val rippled: Boolean
    val radius: Float
    val hMargin: Int
    val vMargin: Int
    var isCircularSideKey: Boolean = false
        private set
    protected val cornerLabelHorizontalSafeInset: Int
    protected val cornerLabelVerticalSafeInset: Int

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
        cornerLabelHorizontalSafeInset = dp(3)
        cornerLabelVerticalSafeInset = dp(1)
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
        // Side keys (?123 and return) - always handle first, before border check
        val viewId = resolvedViewId()
        val isSideKey = viewId == R.id.button_layout_switch || viewId == R.id.button_return
        if (isSideKey) {
            val defaultBkgColor = when (def.variant) {
                Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
                Variant.Alternative -> theme.altKeyBackgroundColor
                Variant.Accent -> theme.accentKeyBackgroundColor
            }
            val bkgColor = resolveBackgroundColor(theme, defaultBkgColor)
            if (ThemeManager.prefs.gboardStyleSideKeys.getValue() && shouldUseCircularGboardSideKeys()) {
                // Circular shape will be applied in onSizeChanged/onLayout when dimensions are available
                isCircularSideKey = true
            } else {
                applyRoundedSideKeyBackground(bkgColor)
            }
            setupPressHighlight()
        } else if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            val defaultBkgColor = if (isMainKeyAreaById(viewId)) {
                theme.keyBackgroundColor
            } else {
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
                    Variant.Alternative -> theme.altKeyBackgroundColor
                    Variant.Accent -> theme.accentKeyBackgroundColor
                }
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

    private fun resolveColorOverride(theme: Theme, staticColor: Int?, colorRef: String?): Int? {
        return resolveThemeColorReference(context, theme, colorRef)
            ?: resolveMonetColor(colorRef)
            ?: staticColor
    }

    protected fun resolveBackgroundColor(theme: Theme, defaultColor: Int): Int {
        return resolveColorOverride(theme, def.backgroundColor, def.backgroundColorMonet) ?: defaultColor
    }

    protected fun resolveShadowColor(theme: Theme): Int {
        return resolveColorOverride(theme, def.shadowColor, def.shadowColorMonet) ?: theme.keyShadowColor
    }

    protected fun resolveTextColor(defaultColor: Int): Int {
        return resolveColorOverride(theme, def.textColor, def.textColorMonet) ?: defaultColor
    }

    protected fun resolveAltTextColor(defaultColor: Int): Int {
        return resolveColorOverride(theme, def.altTextColor, def.altTextColorMonet) ?: defaultColor
    }

    fun resolveBlurClipInsets(viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        if (!isCircularSideKey) return hMargin to vMargin
        return resolveSideKeyCircleInsets(viewWidth, viewHeight)
    }

    private fun resolveSideKeyCircleInsets(viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val minInset = dp(4)
        val usableWidth = (viewWidth - minInset * 2).coerceAtLeast(0)
        val usableHeight = (viewHeight - minInset * 2).coerceAtLeast(0)
        val diameter = min(usableWidth, usableHeight)
        val horizontalInset = ((viewWidth - diameter) / 2).coerceAtLeast(minInset)
        val verticalInset = ((viewHeight - diameter) / 2).coerceAtLeast(minInset)
        return horizontalInset to verticalInset
    }

    private fun applyCircularSideKeyBackground(
        viewWidth: Int,
        viewHeight: Int,
        @ColorInt backgroundColor: Int
    ) {
        val (hInset, vInset) = resolveSideKeyCircleInsets(viewWidth, viewHeight)
        isCircularSideKey = true
        appearanceView.background = insetOvalDrawable(hInset, vInset, backgroundColor)
        // Keep content inside the visible circular background when the side key becomes short.
        appearanceView.setPadding(hInset, vInset, hInset, vInset)
        setupPressHighlight(
            insetOvalDrawable(
                hInset, vInset,
                if (rippled) Color.WHITE else theme.keyPressHighlightColor
            )
        )
    }

    private fun applyRoundedSideKeyBackground(@ColorInt backgroundColor: Int) {
        val borderOrShadowWidth = dp(1)
        isCircularSideKey = false
        appearanceView.background = shadowedKeyBackgroundDrawable(
            backgroundColor, resolveShadowColor(theme),
            radius, borderOrShadowWidth, hMargin, vMargin
        )
        appearanceView.padding = 0
        setupPressHighlight(
            insetRadiusDrawable(
                hMargin, vMargin, radius,
                if (rippled) Color.WHITE else theme.keyPressHighlightColor
            )
        )
    }

    private fun shouldUseCircularGboardSideKeys(): Boolean {
        return resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE ||
            useFloatingGboardSideKeyStyle
    }

    private fun refreshGboardSideKeyShape() {
        val viewWidth = appearanceView.width.takeIf { it > 0 } ?: width
        val viewHeight = appearanceView.height.takeIf { it > 0 } ?: height
        if (viewWidth <= 0 || viewHeight <= 0) return
        maybeRefreshGboardSideKeyShape(viewWidth, viewHeight)
    }

    private fun maybeRefreshGboardSideKeyShape(viewWidth: Int, viewHeight: Int) {
        if (!ThemeManager.prefs.gboardStyleSideKeys.getValue()) return
        val viewId = resolvedViewId()
        if (viewId != R.id.button_layout_switch && viewId != R.id.button_return) return
        val defaultBkgColor = when (def.variant) {
            Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
            Variant.Alternative -> theme.altKeyBackgroundColor
            Variant.Accent -> theme.accentKeyBackgroundColor
        }
        val backgroundColor = resolveBackgroundColor(theme, defaultBkgColor)
        if (shouldUseCircularGboardSideKeys()) {
            applyCircularSideKeyBackground(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                backgroundColor = backgroundColor
            )
        } else {
            applyRoundedSideKeyBackground(backgroundColor)
        }
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
        val appearanceWidth = appearanceView.width.takeIf { it > 0 } ?: (right - left)
        val appearanceHeight = appearanceView.height.takeIf { it > 0 } ?: (bottom - top)
        maybeRefreshGboardSideKeyShape(appearanceWidth, appearanceHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (
            bordered &&
            resolvedViewId() != R.id.button_layout_switch &&
            resolvedViewId() != R.id.button_return
        ) return
        when (resolvedViewId()) {
            R.id.button_layout_switch -> {
                val defaultBkgColor = when (def.variant) {
                    Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
                    Variant.Alternative -> theme.altKeyBackgroundColor
                    Variant.Accent -> theme.accentKeyBackgroundColor
                }
                val bkgColor = resolveBackgroundColor(theme, defaultBkgColor)
                if (ThemeManager.prefs.gboardStyleSideKeys.getValue()) {
                    if (shouldUseCircularGboardSideKeys()) {
                        applyCircularSideKeyBackground(w, h, bkgColor)
                    } else {
                        applyRoundedSideKeyBackground(bkgColor)
                    }
                } else {
                    applyRoundedSideKeyBackground(bkgColor)
                }
            }

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
                val defaultBkgColor = when (def.variant) {
                    Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
                    Variant.Alternative -> theme.altKeyBackgroundColor
                    Variant.Accent -> theme.accentKeyBackgroundColor
                }
                val bkgColor = resolveBackgroundColor(theme, defaultBkgColor)
                if (ThemeManager.prefs.gboardStyleSideKeys.getValue()) {
                    if (shouldUseCircularGboardSideKeys()) {
                        applyCircularSideKeyBackground(w, h, bkgColor)
                    } else {
                        applyRoundedSideKeyBackground(bkgColor)
                    }
                } else {
                    applyRoundedSideKeyBackground(bkgColor)
                }
            }
        }
    }

    /**
     * Update theme without rebuilding view
     */
    open fun updateTheme(newTheme: Theme) {
        theme = newTheme

        // Side keys (?123 and return) - always handle first, before border check
        val viewId = resolvedViewId()
        val isSideKey = viewId == R.id.button_layout_switch || viewId == R.id.button_return
        if (isSideKey) {
            val defaultBkgColor = when (def.variant) {
                Variant.Normal, Variant.AltForeground -> newTheme.keyBackgroundColor
                Variant.Alternative -> newTheme.altKeyBackgroundColor
                Variant.Accent -> newTheme.accentKeyBackgroundColor
            }
            val bkgColor = resolveBackgroundColor(newTheme, defaultBkgColor)
            if (ThemeManager.prefs.gboardStyleSideKeys.getValue()) {
                if (shouldUseCircularGboardSideKeys()) {
                    val w = appearanceView.width
                    val h = appearanceView.height
                    if (w > 0 && h > 0) {
                        applyCircularSideKeyBackground(w, h, bkgColor)
                    }
                } else {
                    applyRoundedSideKeyBackground(bkgColor)
                }
            } else {
                applyRoundedSideKeyBackground(bkgColor)
            }
        } else if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            val defaultBkgColor = if (isMainKeyAreaById(viewId)) {
                newTheme.keyBackgroundColor
            } else {
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground -> newTheme.keyBackgroundColor
                    Variant.Alternative -> newTheme.altKeyBackgroundColor
                    Variant.Accent -> newTheme.accentKeyBackgroundColor
                }
            }
            val bkgColor = resolveBackgroundColor(newTheme, defaultBkgColor)
            val borderOrShadowWidth = dp(1)
            // background: key border
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
        R.id.button_symbol_layout_switch -> def.textSize
        else -> org.fxboomk.fcitx5.android.input.font.FontProviders.getFontSize(
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
        centerLowercaseXHeight = true
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

    private var spaceVoiceIcon: ImageView? = null

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
        if (def.viewId == R.id.button_space) {
            setSpaceVoiceIconVisible(
                AppPrefs.getInstance().keyboard.spaceKeyLongPressBehavior.getValue() ==
                    SpaceLongPressBehavior.VoiceHold
            )
        }
    }

    internal fun setSpaceVoiceIconVisible(visible: Boolean) {
        if (def.viewId != R.id.button_space) return
        if (visible && spaceVoiceIcon == null) {
            spaceVoiceIcon = imageView {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                imageResource = R.drawable.ic_baseline_keyboard_voice_24
            }.also { icon ->
                appearanceView.add(icon, appearanceView.lParams(dp(16), dp(16)) {
                    leftToLeft = parentId
                    leftMargin = hMargin + dp(10)
                    topToTop = parentId
                    bottomToBottom = parentId
                })
            }
        }
        spaceVoiceIcon?.apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            imageTintList = ColorStateList.valueOf(mainText.currentTextColor)
        }
        // Reserve equal space on both sides to keep the input method label centered.
        val inset = hMargin + dp(10) + if (visible) dp(20) else 0
        if (mainText.paddingLeft != inset || mainText.paddingRight != inset) {
            mainText.setPadding(inset, 0, inset, 0)
            // AutoScaleTextView caches its transform even when only padding changes.
            mainText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mainText.textSize)
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
        spaceVoiceIcon?.imageTintList = ColorStateList.valueOf(mainText.currentTextColor)
    }
}

@SuppressLint("ViewConstructor")
class AltTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.AltText,
    horizontalGapScale: Float = 1f
) :
    TextKeyView(ctx, theme, def, horizontalGapScale), SwipeHintAwareKeyView {
    private enum class LabelPosition {
        Hidden,
        Top,
        TopRight,
        Bottom
    }

    private data class LabelLayout(
        val primary: LabelPosition,
        val secondary: LabelPosition
    )

    private val baseAltTextSizeSp = org.fxboomk.fcitx5.android.input.font.FontProviders.getFontSize(
        "key_alt_font", 10.666667f
    )
    private var lastLayout: LabelLayout? = null
    private var lastLayoutHeight = -1
    private var lastDirectionalLabelMask = -1
    private var uppercaseHintInSecondarySlot = false
    private var remainingSpaceTopLabels = emptyList<AutoScaleTextView>()
    private var remainingSpaceBottomLabels = emptyList<AutoScaleTextView>()

    /**
     * The base-class `def` is typed as the generic [KeyDef.Appearance];
     * member functions use this AltText-typed reference instead of re-casting.
     */
    private val altDef = def

    val altText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp)
        // Set font key for batch setting in BaseKeyboard.reloadLayout()
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.NORMAL)
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

    val altText1 = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp)
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.NORMAL)
        text = def.altText1.orEmpty()
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
            add(altText1, lParams(0, wrapContent))
        }
        // 修复时序问题：使用 post 延后执行，确保获取到 layout 后的最终高度
        appearanceView.post {
            applyLayout()
        }
    }

    override fun setTextScale(scale: Float) {
        super.setTextScale(scale)
        altText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp * scale)
        altText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp * scale)
        altText.requestLayout()
        altText1.requestLayout()
        lastLayout = null
        applyLayout()
    }

    private fun hasSecondAltText(): Boolean = !altText1.text.isNullOrBlank()

    private fun resolveUppercaseMode(): UppercasePosition {
        if (def.directionalSwipeLabels) return UppercasePosition.None
        val pref = ThemeManager.prefs.uppercasePosition.getValue()
        if (pref == UppercasePosition.None) return UppercasePosition.None
        if (!altDef.supportsUppercaseHint) return UppercasePosition.None
        // The hint uses the altText1 slot. Preserve a distinct configured label;
        // an empty slot or its uppercase alias may be supplied by the preference.
        if (!altDef.altText1.isNullOrBlank() && !isUppercaseAliasAlt1()) {
            return UppercasePosition.None
        }
        val character = altDef.character
        if (character.length != 1 || !character[0].isLetter()) return UppercasePosition.None
        return pref
    }

    /** True when the secondary slot is populated by the generated uppercase hint. */
    internal fun isUppercaseHintActive(): Boolean = resolveUppercaseMode() != UppercasePosition.None

    /**
     * Whether [KeyDef.Appearance.AltText.altText1] is the uppercase letter of this key,
     * i.e. the value auto-filled by the layout editor when uppercase labels are enabled.
     */
    private fun isUppercaseAliasAlt1(): Boolean {
        val alt1 = altDef.altText1 ?: return false
        if (alt1.length != 1) return false
        val character = altDef.character
        return character.length == 1 && character[0].isLetter() && alt1 == character.uppercase()
    }

    internal fun uppercaseSwipeAction(): KeyAction? {
        if (resolveUppercaseMode() == UppercasePosition.None) return null
        val character = altDef.character
        if (character.length != 1) return null
        // Commit as-is: must not be lowercased by the caps state transformation
        return KeyAction.CommitAction(character.uppercase())
    }

    private fun syncAltText1() {
        val showUppercaseHint = resolveUppercaseMode() != UppercasePosition.None
        if (showUppercaseHint || uppercaseHintInSecondarySlot) {
            val text = if (showUppercaseHint) altDef.character.uppercase() else altDef.altText1.orEmpty()
            if (altText1.text.toString() != text) {
                altText1.text = text
            }
        }
        uppercaseHintInSecondarySlot = showUppercaseHint
    }

    private fun applyMainTextCenterPosition() {
        val params = mainText.layoutParams as ConstraintLayout.LayoutParams
        if (mainText.gravity == Gravity.CENTER && params.height == wrapContent &&
            params.topMargin == 0 && params.bottomMargin == 0 &&
            params.topToBottom == params.unset && params.bottomToTop == params.unset &&
            params.topToTop == params.parentId && params.bottomToBottom == params.parentId
        ) return

        mainText.gravity = Gravity.CENTER
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = wrapContent
            topMargin = 0
            bottomMargin = 0
            topToBottom = unset
            bottomToTop = unset
            topToTop = parentId
            bottomToBottom = parentId
        }
    }

    private fun resetLabelConstraints(label: AutoScaleTextView, width: Int) {
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            this.width = width
            height = wrapContent
            leftToLeft = unset
            leftToRight = unset
            rightToLeft = unset
            rightToRight = unset
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftMargin = 0
            topMargin = 0
            rightMargin = 0
            bottomMargin = 0
        }
    }

    private fun placeIndependentTopLabel(
        label: AutoScaleTextView,
        edge: Int,
        flushToEdge: Boolean = true
    ) {
        resetLabelConstraints(label, 0)
        val centeredAtTop = edge == 0 && flushToEdge
        label.visibility = View.VISIBLE
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            leftToLeft = parentId
            leftMargin = hMargin
            rightToRight = parentId
            rightMargin = hMargin
            topToTop = parentId
            topMargin = if (centeredAtTop) vMargin else vMargin + cornerLabelVerticalSafeInset
        }
        label.setPadding(0, 0, if (edge > 0) cornerLabelHorizontalSafeInset else 0, 0)
        label.useGlyphBounds = centeredAtTop
        label.gravity = when {
            edge > 0 -> Gravity.END or Gravity.CENTER_VERTICAL
            centeredAtTop -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }
    }

    private fun placeIndependentBottomLabel(label: AutoScaleTextView) {
        resetLabelConstraints(label, 0)
        label.visibility = View.VISIBLE
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            leftToLeft = parentId
            leftMargin = hMargin
            rightToRight = parentId
            rightMargin = hMargin
            bottomToBottom = parentId
            bottomMargin = vMargin
        }
        label.setPadding(0, 0, 0, 0)
        label.useGlyphBounds = true
        label.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private fun placeCornerLabel(label: AutoScaleTextView, top: Boolean, right: Boolean) {
        resetLabelConstraints(label, wrapContent)
        label.visibility = View.VISIBLE
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (top) {
                topToTop = parentId
                topMargin = vMargin + cornerLabelVerticalSafeInset
            } else {
                bottomToBottom = parentId
                bottomMargin = vMargin + cornerLabelVerticalSafeInset
            }
            if (right) {
                rightToRight = parentId
                rightMargin = hMargin + cornerLabelHorizontalSafeInset
            } else {
                leftToLeft = parentId
                leftMargin = hMargin + cornerLabelHorizontalSafeInset
            }
        }
        label.setPadding(0, 0, 0, 0)
        label.useGlyphBounds = !top
        label.gravity = if (top) Gravity.CENTER else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private fun applyCornerPairAltTextPosition(top: Boolean, primaryRight: Boolean) {
        applyMainTextCenterPosition()
        placeCornerLabel(altText, top, primaryRight)
        placeCornerLabel(altText1, top, !primaryRight)
    }

    private fun hideAltText1() {
        altText1.visibility = View.GONE
        altText1.setPadding(hMargin, 0, hMargin, 0)
        altText1.gravity = Gravity.CENTER
        altText1.useGlyphBounds = false
        altText1.maxHeight = Int.MAX_VALUE
    }

    private fun hidePrimaryAltText() {
        altText.visibility = View.GONE
        altText.setPadding(hMargin, 0, hMargin, 0)
        altText.gravity = Gravity.CENTER
        altText.useGlyphBounds = false
        altText.maxHeight = Int.MAX_VALUE
    }

    private fun positionLabel(label: AutoScaleTextView, position: LabelPosition) {
        when (position) {
            LabelPosition.Hidden -> label.visibility = View.GONE
            LabelPosition.Top -> placeIndependentTopLabel(label, edge = 0)
            LabelPosition.TopRight -> placeIndependentTopLabel(label, edge = 1, flushToEdge = false)
            LabelPosition.Bottom -> placeIndependentBottomLabel(label)
        }
    }

    private fun positionFrom(position: PunctuationPosition): LabelPosition = when (position) {
        PunctuationPosition.Top -> LabelPosition.Top
        PunctuationPosition.TopRight -> LabelPosition.TopRight
        PunctuationPosition.Bottom -> LabelPosition.Bottom
        PunctuationPosition.None -> LabelPosition.Hidden
    }

    private fun positionFrom(position: KeyDef.Appearance.AltTextPosition?): LabelPosition = when (position) {
        KeyDef.Appearance.AltTextPosition.Top -> LabelPosition.Top
        KeyDef.Appearance.AltTextPosition.TopRight -> LabelPosition.TopRight
        KeyDef.Appearance.AltTextPosition.Bottom -> LabelPosition.Bottom
        KeyDef.Appearance.AltTextPosition.TopBottom, null -> positionFrom(punctuationPositionForKey())
    }

    private fun isTop(position: LabelPosition): Boolean =
        position == LabelPosition.Top || position == LabelPosition.TopRight

    private fun labelHeight(label: AutoScaleTextView): Int =
        (label.paint.fontMetrics.bottom - label.paint.fontMetrics.top).toInt()

    private fun resolvePrimaryPosition(): LabelPosition {
        return when (val override = def.altTextPositionOverride) {
            null, KeyDef.Appearance.AltTextPosition.TopBottom -> positionFrom(punctuationPositionForKey())
            else -> positionFrom(override)
        }
    }

    private fun resolveSecondaryPosition(): LabelPosition {
        val override = def.altText1PositionOverride
        if (override != null) return positionFrom(override)

        return when (val uppercasePosition = resolveUppercaseMode()) {
            UppercasePosition.Top -> LabelPosition.Top
            UppercasePosition.Bottom -> LabelPosition.Bottom
            UppercasePosition.None -> positionFrom(punctuationPositionForKey())
        }
    }

    private fun resolveLayout(keyHeight: Int): LabelLayout {
        if (def.directionalSwipeLabels) {
            val punctuation = punctuationPositionForKey()
            if (punctuation == PunctuationPosition.None) {
                return LabelLayout(LabelPosition.Hidden, LabelPosition.Hidden)
            }
            return LabelLayout(
                primary = if (altText.text.isNullOrBlank()) {
                    LabelPosition.Hidden
                } else if (punctuation == PunctuationPosition.TopRight) {
                    LabelPosition.TopRight
                } else {
                    LabelPosition.Top
                },
                secondary = if (hasSecondAltText()) LabelPosition.Bottom else LabelPosition.Hidden
            )
        }

        val hasPrimary = !altText.text.isNullOrBlank()
        val hasSecondary = hasSecondAltText()
        val requestedPrimary = resolvePrimaryPosition()
        var primary = if (hasPrimary) requestedPrimary else LabelPosition.Hidden
        var secondary = if (hasSecondary) resolveSecondaryPosition() else LabelPosition.Hidden

        // TopBottom is the legacy combined row setting: it pins the first label
        // above the second, regardless of the theme's independent positions.
        if (hasPrimary && hasSecondary &&
            def.altTextPositionOverride == KeyDef.Appearance.AltTextPosition.TopBottom
        ) {
            primary = LabelPosition.Top
            secondary = LabelPosition.Bottom
        }

        // There is one upper-right corner. Keep the first label there and place
        // the second in the remaining upper corner when legacy data collides.
        if (requestedPrimary == LabelPosition.TopRight && secondary == LabelPosition.TopRight) {
            secondary = LabelPosition.Top
        }

        val layout = LabelLayout(primary, secondary)
        if (keyHeight > 0 && !hasEnoughRoom(layout, keyHeight)) {
            return LabelLayout(LabelPosition.Hidden, LabelPosition.Hidden)
        }
        return layout
    }

    private fun hasEnoughRoom(layout: LabelLayout, keyHeight: Int): Boolean {
        val primaryVisible = layout.primary != LabelPosition.Hidden
        val secondaryVisible = layout.secondary != LabelPosition.Hidden
        if (!primaryVisible && !secondaryVisible) return true

        val contentHeight = keyHeight - vMargin * 2
        val minimumHeight = when {
            primaryVisible && secondaryVisible && isTop(layout.primary) == isTop(layout.secondary) &&
                isTop(layout.primary) -> cornerLabelVerticalSafeInset + dp(4) + 3
            primaryVisible && secondaryVisible -> maxOf(labelHeight(altText), labelHeight(altText1)) +
                cornerLabelVerticalSafeInset
            primaryVisible -> labelHeight(altText) + cornerLabelVerticalSafeInset
            else -> labelHeight(altText1) + cornerLabelVerticalSafeInset
        }
        return contentHeight >= minimumHeight
    }

    private fun applyLayout(keyHeight: Int = appearanceView.height) {
        if (altText1.typeface.isBold) {
            altText1.setTypeface(
                altText1.typeface,
                if (altText1.typeface.isItalic) Typeface.ITALIC else Typeface.NORMAL
            )
        }
        syncAltText1()
        val layout = resolveLayout(keyHeight)
        val directionalLabelMask = if (def.directionalSwipeLabels) {
            (if (altText.text.isNullOrBlank()) 0 else 1) or
                (if (altText1.text.isNullOrBlank()) 0 else 2)
        } else 0
        if (layout == lastLayout && keyHeight == lastLayoutHeight &&
            directionalLabelMask == lastDirectionalLabelMask
        ) {
            if (!def.directionalSwipeLabels) updateRemainingSpaceLabels(keyHeight)
            return
        }

        lastLayout = layout
        lastLayoutHeight = keyHeight
        lastDirectionalLabelMask = directionalLabelMask
        remainingSpaceTopLabels = emptyList()
        remainingSpaceBottomLabels = emptyList()
        mainText.glyphPlacement = null
        mainText.useGlyphBounds = false
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = wrapContent
            topToBottom = unset
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }

        val primaryVisible = layout.primary != LabelPosition.Hidden
        val secondaryVisible = layout.secondary != LabelPosition.Hidden
        val separateLabelsOnEdges = primaryVisible && secondaryVisible &&
            isTop(layout.primary) != isTop(layout.secondary)
        val labelMaxHeight = if (separateLabelsOnEdges && keyHeight > 0) {
            ((keyHeight - vMargin * 2 - cornerLabelVerticalSafeInset * 2 - dp(4)) / 3)
                .coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        altText.maxHeight = labelMaxHeight
        altText1.maxHeight = labelMaxHeight

        when {
            primaryVisible && secondaryVisible && isTop(layout.primary) == isTop(layout.secondary) -> {
                val primaryRight = layout.primary == LabelPosition.TopRight
                applyCornerPairAltTextPosition(isTop(layout.primary), primaryRight)
            }
            else -> {
                if (primaryVisible) positionLabel(altText, layout.primary) else hidePrimaryAltText()
                if (secondaryVisible) positionLabel(altText1, layout.secondary) else hideAltText1()
            }
        }

        if (!primaryVisible && !secondaryVisible) applyMainTextCenterPosition()
        updateRemainingSpaceLabels(keyHeight)
    }

    private fun updateRemainingSpaceLabels(keyHeight: Int) {
        val visibleLabels = listOf(altText, altText1).filter {
            it.visibility == View.VISIBLE && !it.text.isNullOrBlank()
        }
        remainingSpaceTopLabels = visibleLabels.filter {
            (it.layoutParams as ConstraintLayout.LayoutParams).topToTop == ConstraintLayout.LayoutParams.PARENT_ID
        }
        remainingSpaceBottomLabels = visibleLabels.filter {
            (it.layoutParams as ConstraintLayout.LayoutParams).bottomToBottom == ConstraintLayout.LayoutParams.PARENT_ID
        }
        if (visibleLabels.isNotEmpty()) {
            val topInset = remainingSpaceTopLabels.maxOfOrNull {
                (it.layoutParams as ConstraintLayout.LayoutParams).topMargin
            } ?: vMargin
            val bottomInset = remainingSpaceBottomLabels.maxOfOrNull {
                (it.layoutParams as ConstraintLayout.LayoutParams).bottomMargin
            } ?: vMargin
            val hasSingleEdge = remainingSpaceTopLabels.isNotEmpty() xor
                remainingSpaceBottomLabels.isNotEmpty()
            if (keyHeight > 0 && hasSingleEdge) {
                val maxLabelHeight = ((keyHeight - topInset - bottomInset - dp(4)) / 3).coerceAtLeast(1)
                visibleLabels.forEach {
                    if (it.maxHeight != maxLabelHeight) it.maxHeight = maxLabelHeight
                }
            }
            mainText.useGlyphBounds = true
            val params = mainText.layoutParams as ConstraintLayout.LayoutParams
            if (params.height != 0 || params.topToTop != params.parentId ||
                params.bottomToBottom != params.parentId || params.topToBottom != params.unset ||
                params.bottomToTop != params.unset || params.topMargin != 0 || params.bottomMargin != 0
            ) {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    height = 0
                    topToTop = parentId
                    bottomToBottom = parentId
                    topToBottom = unset
                    bottomToTop = unset
                    topMargin = 0
                    bottomMargin = 0
                }
            }
        } else {
            mainText.glyphPlacement = null
            applyMainTextCenterPosition()
        }
    }

    override fun secondarySwipeTarget(): AltTextSwipeTarget =
        if (isUppercaseHintActive()) AltTextSwipeTarget.Uppercase else AltTextSwipeTarget.Secondary

    override fun selectAltTextSwipeTarget(totalY: Int): AltTextSwipeTarget? {
        if (totalY == 0) return null
        val layout = lastLayout ?: resolveLayout(appearanceView.height)
        val primaryShown = layout.primary != LabelPosition.Hidden && !altText.text.isNullOrBlank()
        val secondaryShown = layout.secondary != LabelPosition.Hidden && !altText1.text.isNullOrBlank()
        if (!primaryShown && !secondaryShown) return null

        if (def.directionalSwipeLabels) {
            return if (totalY < 0) {
                AltTextSwipeTarget.Primary.takeIf { primaryShown }
            } else {
                AltTextSwipeTarget.Secondary.takeIf { secondaryShown }
            }
        }

        val secondaryTarget = secondarySwipeTarget()
        if (primaryShown && secondaryShown && isTop(layout.primary) == isTop(layout.secondary)) {
            // Same-edge pairs keep the established gesture mapping: up selects
            // the second label, down selects the first.
            return if (totalY < 0) secondaryTarget else AltTextSwipeTarget.Primary
        }

        val topTarget = when {
            primaryShown && isTop(layout.primary) -> AltTextSwipeTarget.Primary
            secondaryShown && isTop(layout.secondary) -> secondaryTarget
            else -> null
        }
        val bottomTarget = when {
            primaryShown && !isTop(layout.primary) -> AltTextSwipeTarget.Primary
            secondaryShown && !isTop(layout.secondary) -> secondaryTarget
            else -> null
        }
        return if (totalY < 0) topTarget else bottomTarget
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        lastLayout = null
        appearanceView.post { applyLayout() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (height > 0) applyLayout(height)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAppearanceLayoutChanged(width: Int, height: Int) {
        applyLayout(height)
        alignRemainingSpaceMainLabels(listOf(this))
        if (mainText.glyphPlacement == null) {
            mainText.constrainGlyphsVertically(vMargin, height - vMargin)
        }
    }

    companion object {
        internal fun alignRemainingSpaceMainLabels(keys: List<AltTextKeyView>) {
            val eligible = keys.filter { key ->
                key.visibility == View.VISIBLE &&
                    key.mainText.text.isNotBlank() &&
                    key.mainText.measuredWidth > 0 && key.mainText.measuredHeight > 0
            }
            val placements = eligible.mapNotNull { key ->
                val text = key.mainText.text.toString()
                val bounds = Rect()
                key.mainText.paint.getTextBounds(text, 0, text.length, bounds)
                if (bounds.isEmpty) return@mapNotNull null

                val visibleLabels = listOf(key.altText, key.altText1).filter {
                    it.visibility == View.VISIBLE && !it.text.isNullOrBlank()
                }
                val topLabels = key.remainingSpaceTopLabels.ifEmpty {
                    visibleLabels.filter { label ->
                        label.top + label.renderedGlyphBounds().centerY() < key.appearanceView.height / 2f
                    }
                }
                val bottomLabels = key.remainingSpaceBottomLabels.ifEmpty {
                    visibleLabels.filter { label ->
                        label.top + label.renderedGlyphBounds().centerY() >= key.appearanceView.height / 2f
                    }
                }
                val bandTop = if (topLabels.isNotEmpty()) {
                    topLabels.maxOf { label ->
                        label.top + label.renderedGlyphBounds().bottom
                    }
                } else {
                    key.vMargin.toFloat()
                }
                val bandBottom = if (bottomLabels.isNotEmpty()) {
                    bottomLabels.minOf { label ->
                        label.top + label.renderedGlyphBounds().top
                    }
                } else {
                    (key.appearanceView.height - key.vMargin).toFloat()
                }
                val inputs = MainLabelBandGeometryInputs(
                    bandTop = bandTop,
                    bandBottom = bandBottom,
                    faceCenter = key.appearanceView.height / 2f,
                    inkTop = bounds.top.toFloat(),
                    inkBottom = bounds.bottom.toFloat(),
                    minGap = (key.dp(2) - 1).toFloat(),
                )
                val availableWidth = (key.mainText.measuredWidth - key.mainText.paddingLeft - key.mainText.paddingRight)
                    .coerceAtLeast(0)
                MainLabelPlacement(
                    key = key,
                    widthScale = min(1f, availableWidth.toFloat() / bounds.width()),
                    inputs = inputs,
                )
            }
            if (placements.isEmpty()) return
            val isSingleLetterKey = { placement: MainLabelPlacement ->
                val keyText = placement.key.mainText.text.toString()
                val visibleText = keyText.filterNot {
                    it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt()
                }
                val character = (placement.key.def as? KeyDef.Appearance.AltText)?.character
                visibleText.singleOrNull()?.let {
                    it.isLetter()
                } == true && character?.singleOrNull()?.let {
                    it.isLetter()
                } == true
            }
            val singleLetters = placements.filter(isSingleLetterKey)
            val sharedLetterScale = singleLetters.minOfOrNull { placement ->
                min(placement.widthScale, placement.inputs.geometry().scale)
            }
            placements.forEach { placement ->
                val isLetter = isSingleLetterKey(placement)
                val scale = if (isLetter) {
                    sharedLetterScale ?: placement.inputs.geometry().scale
                } else {
                    placement.inputs.geometry().scale
                }
                val geometry = placement.inputs.geometry(scaleOverride = scale)
                placement.key.mainText.glyphPlacement = AutoScaleTextView.GlyphPlacement(
                    geometry.baseline - placement.key.mainText.top,
                    geometry.scale,
                    if (isLetter) geometry.scale else placement.widthScale * geometry.scale,
                )
            }
        }

        private data class MainLabelPlacement(
            val key: AltTextKeyView,
            val widthScale: Float,
            val inputs: MainLabelBandGeometryInputs,
        )

        private data class MainLabelBandGeometryInputs(
            val bandTop: Float,
            val bandBottom: Float,
            val faceCenter: Float,
            val inkTop: Float,
            val inkBottom: Float,
            val minGap: Float,
        ) {
            fun geometry(scaleOverride: Float? = null) = computeMainLabelGeometry(
                bandTop = bandTop,
                bandBottom = bandBottom,
                faceCenter = faceCenter,
                inkTop = inkTop,
                inkBottom = inkBottom,
                minGap = minGap,
                scaleOverride = scaleOverride,
            )
        }
    }

    /**
     * Force refresh layout with current final height.
     * Used by BaseKeyboard to ensure correct layout after keyboard size is fully applied.
     */
    internal fun refreshLayout() {
        lastLayout = null
        applyLayout()
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        val color = resolveAltTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                Variant.Accent -> newTheme.accentKeyTextColor
            }
        )
        altText.setTextColor(color)
        altText1.setTextColor(color)
        lastLayout = null
        appearanceView.post { applyLayout() }
    }

}

@SuppressLint("ViewConstructor")
class ImageAltTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.ImageAltText,
    horizontalGapScale: Float = 1f
) : KeyView(ctx, theme, def, horizontalGapScale), SwipeHintAwareKeyView, KeyViewWithImage {
    private enum class AltTextLayoutMode {
        Top,
        TopRight,
        Bottom,
        DirectionalTopBottom,
        DirectionalTopRightBottom,
        Hidden
    }

    private val baseAltTextSizeSp = org.fxboomk.fcitx5.android.input.font.FontProviders.getFontSize(
        "key_alt_font", 10.666667f
    )
    private var lastLayoutMode: AltTextLayoutMode? = null
    private var lastLayoutHeight = -1
    private var lastDirectionalLabelMask = -1

    override val img = imageView { configure(theme, def.src, def.variant, def.viewId) }.apply {
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

    val altText = createAltText(def.altText)
    val altText1 = createAltText(def.altText1.orEmpty())

    private fun createAltText(label: String) = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp)
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.NORMAL)
        text = label
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
            add(img, lParams(wrapContent, wrapContent))
            add(altText, lParams(0, wrapContent))
            add(altText1, lParams(0, wrapContent))
        }
        applyLayout()
    }

    override fun setTextScale(scale: Float) {
        altText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp * scale)
        altText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseAltTextSizeSp * scale)
        altText.requestLayout()
        altText1.requestLayout()
        lastLayoutMode = null
        applyLayout()
    }


    private fun applyTopRightAltTextPadding() {
        altText.setPaddingRelative(0, 0, cornerLabelHorizontalSafeInset, 0)
    }

    private fun applyBottomAltTextPadding() {
        altText.setPadding(hMargin, 0, hMargin, 0)
    }

    private fun applyTopRightAltTextPosition() {
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            bottomToBottom = parentId
            startToStart = parentId
            endToEnd = parentId
            topMargin = 0
            bottomMargin = 0
            bottomToTop = unset
        }
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId; topMargin = vMargin + cornerLabelVerticalSafeInset
            bottomToBottom = unset; bottomMargin = 0
            leftToLeft = parentId; leftMargin = hMargin
            rightToRight = parentId; rightMargin = hMargin
        }
        applyTopRightAltTextPadding()
        altText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun applyTopAltTextPosition() {
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            bottomToBottom = parentId
            startToStart = parentId
            endToEnd = parentId
            topMargin = 0
            bottomMargin = 0
            bottomToTop = unset
        }
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topMargin = vMargin + cornerLabelVerticalSafeInset
            bottomToBottom = unset
            bottomMargin = 0
            leftToLeft = parentId
            leftMargin = hMargin
            rightToRight = parentId
            rightMargin = hMargin
        }
        applyBottomAltTextPadding()
        altText.useGlyphBounds = false
        altText.gravity = Gravity.CENTER
    }

    private fun applyBottomAltTextPosition() {
        centerImageOnKey()
        altText.visibility = View.VISIBLE
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = unset; topMargin = 0
            leftToLeft = parentId; leftMargin = hMargin
            rightToRight = parentId; rightMargin = hMargin
            bottomToBottom = parentId; bottomMargin = vMargin
        }
        applyBottomAltTextPadding()
        altText.useGlyphBounds = true
        altText.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private fun applyNoAltTextPosition() {
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            bottomToBottom = parentId
            startToStart = parentId
            endToEnd = parentId
            topMargin = 0
            bottomMargin = 0
            bottomToTop = unset
        }
        altText.visibility = View.GONE
        applyBottomAltTextPadding()
        altText.gravity = Gravity.CENTER
    }

    private fun applyDirectionalSwipeLabelPositions(topRight: Boolean, keyHeight: Int) {
        val labelMaxHeight = if (keyHeight > 0) {
            ((keyHeight - vMargin * 2 - cornerLabelVerticalSafeInset * 2 - dp(4)) / 3)
                .coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        listOf(altText, altText1).forEach { label ->
            label.visibility = if (label.text.isNullOrBlank()) View.GONE else View.VISIBLE
            label.useGlyphBounds = true
            label.maxHeight = labelMaxHeight
        }
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topMargin = vMargin + cornerLabelVerticalSafeInset
            bottomToBottom = unset
            bottomMargin = 0
            leftToLeft = parentId
            leftMargin = hMargin
            rightToRight = parentId
            rightMargin = hMargin
        }
        altText.setPaddingRelative(0, 0, if (topRight) cornerLabelHorizontalSafeInset else 0, 0)
        altText.gravity = if (topRight) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = unset
            topMargin = 0
            bottomToBottom = parentId
            bottomMargin = vMargin
            leftToLeft = parentId
            leftMargin = hMargin
            rightToRight = parentId
            rightMargin = hMargin
        }
        altText1.setPadding(0, 0, 0, 0)
        altText1.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        centerImageOnKey()
    }

    private fun centerImageOnKey() {
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            constrainedHeight = false
            topToTop = parentId
            topToBottom = unset
            topMargin = 0
            bottomToBottom = parentId
            bottomToTop = unset
            bottomMargin = 0
            startToStart = parentId
            endToEnd = parentId
        }
    }

    private fun resolveThemeLayoutMode(): AltTextLayoutMode {
        val pref = punctuationPositionForKey()
        if (pref == PunctuationPosition.None) return AltTextLayoutMode.Hidden
        return when (pref) {
            PunctuationPosition.Top -> AltTextLayoutMode.Top
            PunctuationPosition.TopRight -> AltTextLayoutMode.TopRight
            PunctuationPosition.Bottom -> AltTextLayoutMode.Bottom
            PunctuationPosition.None -> AltTextLayoutMode.Hidden
        }
    }


    private fun resolveLayoutMode(keyHeight: Int): AltTextLayoutMode {
        if (def.directionalSwipeLabels) {
            return when (punctuationPositionForKey()) {
                PunctuationPosition.None -> AltTextLayoutMode.Hidden
                PunctuationPosition.TopRight -> AltTextLayoutMode.DirectionalTopRightBottom
                PunctuationPosition.Top, PunctuationPosition.Bottom -> AltTextLayoutMode.DirectionalTopBottom
            }
        }
        if (altText.text.isNullOrBlank()) return AltTextLayoutMode.Hidden
        if (punctuationPositionForKey() == PunctuationPosition.None) {
            return AltTextLayoutMode.Hidden
        }
        val preferred = when (def.altTextPositionOverride) {
            KeyDef.Appearance.AltTextPosition.TopBottom -> resolveThemeLayoutMode()
            KeyDef.Appearance.AltTextPosition.Top -> AltTextLayoutMode.Top
            KeyDef.Appearance.AltTextPosition.TopRight -> AltTextLayoutMode.TopRight
            KeyDef.Appearance.AltTextPosition.Bottom -> AltTextLayoutMode.Bottom
            null -> resolveThemeLayoutMode()
        }
        if (keyHeight <= 0) return preferred

        val contentHeight = keyHeight - vMargin * 2
        val iconHeight = img.measuredHeight.takeIf { it > 0 } ?: dp(24)
        val altHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
        val compactMinHeight = max(iconHeight.toFloat(), altHeight + cornerLabelVerticalSafeInset.toFloat())
        val stackedMinHeight = iconHeight + altHeight + dp(1)

        return when (preferred) {
            AltTextLayoutMode.Bottom -> when {
                contentHeight >= stackedMinHeight -> AltTextLayoutMode.Bottom
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.Top -> when {
                contentHeight >= compactMinHeight -> AltTextLayoutMode.Top
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.TopRight -> when {
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalTopRightBottom -> preferred
            AltTextLayoutMode.Hidden -> AltTextLayoutMode.Hidden
        }
    }

    private fun applyLayout(keyHeight: Int = appearanceView.height) {
        val mode = resolveLayoutMode(keyHeight)
        val directionalLabelMask = if (def.directionalSwipeLabels) {
            (if (altText.text.isNullOrBlank()) 0 else 1) or
                    (if (altText1.text.isNullOrBlank()) 0 else 2)
        } else 0
        if (mode == lastLayoutMode && (!def.directionalSwipeLabels ||
                    (keyHeight == lastLayoutHeight && directionalLabelMask == lastDirectionalLabelMask))
        ) return
        lastLayoutMode = mode
        lastLayoutHeight = keyHeight
        lastDirectionalLabelMask = directionalLabelMask
        altText1.visibility = View.GONE
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = unset
            constrainedHeight = false
        }
        when (mode) {
            AltTextLayoutMode.Bottom -> applyBottomAltTextPosition()
            AltTextLayoutMode.Top -> applyTopAltTextPosition()
            AltTextLayoutMode.TopRight -> applyTopRightAltTextPosition()
            AltTextLayoutMode.DirectionalTopBottom ->
                applyDirectionalSwipeLabelPositions(topRight = false, keyHeight = keyHeight)
            AltTextLayoutMode.DirectionalTopRightBottom ->
                applyDirectionalSwipeLabelPositions(topRight = true, keyHeight = keyHeight)
            AltTextLayoutMode.Hidden -> applyNoAltTextPosition()
        }
    }

    override fun selectAltTextSwipeTarget(totalY: Int): AltTextSwipeTarget? {
        if (totalY == 0) return null
        return when (lastLayoutMode ?: resolveLayoutMode(appearanceView.height)) {
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalTopRightBottom -> if (totalY < 0) {
                AltTextSwipeTarget.Primary.takeUnless { altText.text.isNullOrBlank() }
            } else {
                AltTextSwipeTarget.Secondary.takeUnless { altText1.text.isNullOrBlank() }
            }
            AltTextLayoutMode.Bottom -> AltTextSwipeTarget.Primary.takeIf { totalY > 0 }
            AltTextLayoutMode.Top,
            AltTextLayoutMode.TopRight -> AltTextSwipeTarget.Primary.takeIf { totalY < 0 }
            AltTextLayoutMode.Hidden -> null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        lastLayoutMode = null
        applyLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (def.directionalSwipeLabels && height > 0) applyLayout(height)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAppearanceLayoutChanged(width: Int, height: Int) {
        applyLayout(height)
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
        altText.setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
        altText1.setTextColor(altText.currentTextColor)
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
    KeyView(ctx, theme, def, horizontalGapScale), KeyViewWithImage {
    override val img = imageView { configure(theme, def.src, def.variant, def.viewId) }.apply {
        val defaultColor = when (def.variant) {
            Variant.Normal -> theme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
            Variant.Accent -> theme.accentKeyTextColor
        }
        imageTintList = ColorStateList.valueOf(resolveTextColor(defaultColor))
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
        val defaultColor = when (def.variant) {
            Variant.Normal -> newTheme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
            Variant.Accent -> newTheme.accentKeyTextColor
        }
        img.imageTintList = ColorStateList.valueOf(resolveTextColor(defaultColor))
    }
}

private fun resolveForegroundColor(theme: Theme, variant: Variant, viewId: Int): Int {
    return when (variant) {
        Variant.Normal -> theme.keyTextColor
        Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
        Variant.Accent -> theme.accentKeyTextColor
    }
}

private fun ImageView.configure(
    theme: Theme,
    @DrawableRes src: Int,
    variant: Variant,
    viewId: Int
) = apply {
    isClickable = false
    isFocusable = false
    imageTintList = ColorStateList.valueOf(resolveForegroundColor(theme, variant, viewId))
    imageResource = src
}

@SuppressLint("ViewConstructor")
class ImageTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.ImageText,
    horizontalGapScale: Float = 1f
) :
    TextKeyView(ctx, theme, def, horizontalGapScale), KeyViewWithImage {
    override val img = imageView {
        configure(theme, def.src, def.variant, def.viewId)
        val defaultColor = when (def.variant) {
            Variant.Normal -> theme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
            Variant.Accent -> theme.accentKeyTextColor
        }
        imageTintList = ColorStateList.valueOf(resolveTextColor(defaultColor))
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
        val defaultColor = when (def.variant) {
            Variant.Normal -> newTheme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
            Variant.Accent -> newTheme.accentKeyTextColor
        }
        img.imageTintList = ColorStateList.valueOf(resolveTextColor(defaultColor))
    }
}
