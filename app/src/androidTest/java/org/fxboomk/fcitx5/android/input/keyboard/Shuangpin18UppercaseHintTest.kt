/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.UppercasePosition
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class Shuangpin18UppercaseHintTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun uppercaseHintsUseTheRequestedEdgeAndKeepPunctuationOnTheOppositeEdge() = withTheme {
        for (landscape in listOf(false, true)) {
            for (density in listOf(240, 480)) {
                val context = keyboardContext(landscape, density)
                for (punctuation in listOf(PunctuationPosition.Top, PunctuationPosition.TopRight,
                    PunctuationPosition.Bottom)) {
                    ThemeManager.prefs.punctuationPosition.setValue(punctuation)
                    for (uppercase in listOf(UppercasePosition.Top, UppercasePosition.Bottom)) {
                        ThemeManager.prefs.uppercasePosition.setValue(uppercase)
                        for (semicolon in listOf(false, true)) {
                            val keys = groupedKeys(context, semicolon)
                            keys.forEach { layoutKey(it, landscape) }
                            AltTextKeyView.alignRemainingSpaceMainLabels(keys)
                            keys.forEach { key ->
                                val appearance = key.def as KeyDef.Appearance.AltText
                                val letters = appearance.character.filter { it.isLetter() }.uppercase()
                                val case = "${appearance.character}: landscape=$landscape density=$density " +
                                    "punctuation=$punctuation uppercase=$uppercase"
                                val top = uppercase == UppercasePosition.Top
                                assertTrue(case, key.isUppercaseHintActive())
                                assertEquals(case, letters.first().toString(), key.altText1.text.toString())
                                assertOnEdge(key.altText1, top, case)
                                assertOnEdge(key.altText, !top, case)
                                if (punctuation == PunctuationPosition.TopRight && !top) {
                                    assertEquals(case, Gravity.RIGHT,
                                        key.altText.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                                    assertTrue("Punctuation must occupy the upper-right corner: $case",
                                        bounds(key.altText).left > key.getChildAt(0).width / 2f)
                                } else {
                                    assertCentered(key, key.altText, case)
                                }
                                assertEquals(case, KeyAction.CommitAction(letters), key.uppercaseSwipeAction())
                                if (letters.length == 2) {
                                    val second = requireNotNull(key.groupedUppercaseText)
                                    assertEquals(case, letters.last().toString(), second.text.toString())
                                    assertOnEdge(second, top, case)
                                    assertTrue(case, bounds(key.altText1).right < bounds(second).left)
                                    val firstParams = key.altText1.layoutParams as ConstraintLayout.LayoutParams
                                    val secondParams = second.layoutParams as ConstraintLayout.LayoutParams
                                    assertEquals(case, ConstraintLayout.LayoutParams.PARENT_ID,
                                        firstParams.leftToLeft)
                                    assertEquals(case, ConstraintLayout.LayoutParams.PARENT_ID,
                                        secondParams.rightToRight)
                                } else {
                                    assertCentered(key, key.altText1, case)
                                    assertTrue(case, key.groupedUppercaseText?.visibility != View.VISIBLE)
                                }
                                assertMainLabelBetweenHints(key, case)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun togglingUppercaseAndPunctuationRefreshesExistingGroupedKeys() = withTheme {
        val context = keyboardContext(landscape = true, density = 480)
        val key = groupedKeys(context, semicolon = true).single {
            (it.def as KeyDef.Appearance.AltText).character == "io"
        }
        val second = requireNotNull(key.groupedUppercaseText)
        for (uppercase in listOf(UppercasePosition.Top, UppercasePosition.Bottom,
            UppercasePosition.None, UppercasePosition.Top)) {
            ThemeManager.prefs.uppercasePosition.setValue(uppercase)
            ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.None)
            layoutKey(key, landscape = true)
            assertEquals(View.GONE, key.altText.visibility)
            if (uppercase == UppercasePosition.None) {
                assertFalse(key.isUppercaseHintActive())
                assertEquals(View.GONE, key.altText1.visibility)
                assertEquals(View.GONE, second.visibility)
            } else {
                val top = uppercase == UppercasePosition.Top
                assertEquals("I", key.altText1.text.toString())
                assertEquals("O", second.text.toString())
                assertOnEdge(key.altText1, top, "first uppercase hint")
                assertOnEdge(second, top, "second uppercase hint")
                assertMainLabelBetweenHints(key, "uppercase-only $uppercase")
            }
        }

        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.None)
        for (punctuation in listOf(PunctuationPosition.Top, PunctuationPosition.Bottom)) {
            ThemeManager.prefs.punctuationPosition.setValue(punctuation)
            layoutKey(key, landscape = true)
            assertOnEdge(key.altText, punctuation == PunctuationPosition.Top, "punctuation-only")
            assertEquals(View.GONE, key.altText1.visibility)
            assertEquals(View.GONE, second.visibility)
        }
    }

    @Test
    fun bothGroupedUppercaseHintsShareFontSizeTypefaceAndThemeColor() = withTheme {
        ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
        ThemeManager.prefs.uppercasePosition.setValue(UppercasePosition.Top)
        val key = groupedKeys(keyboardContext(landscape = false, density = 480), semicolon = false)
            .single { (it.def as KeyDef.Appearance.AltText).character == "io" }
        val second = requireNotNull(key.groupedUppercaseText)
        key.altText1.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
        key.setTextScale(1.25f)
        layoutKey(key, landscape = false)
        val enlargedSize = key.altText1.textSize
        assertEquals(key.altText1.typeface, second.typeface)
        assertEquals(enlargedSize, second.textSize, 0.01f)
        assertFalse(second.paint.isFakeBoldText)

        key.setTextScale(0.8f)
        layoutKey(key, landscape = false)
        assertTrue(key.altText1.textSize < enlargedSize)
        assertEquals(key.altText1.textSize, second.textSize, 0.01f)
        assertEquals(key.altText1.typeface, second.typeface)

        key.updateTheme(ThemePreset.MaterialDark)
        layoutKey(key, landscape = false)
        assertEquals(key.altText1.currentTextColor, second.currentTextColor)
        assertEquals(key.altText1.typeface, second.typeface)
        assertEquals(key.altText1.textSize, second.textSize, 0.01f)
    }

    @Test
    fun groupedUppercaseSwipesCommitAllLettersAndKeepPunctuationSeparate() = withTheme {
        for (uppercase in listOf(UppercasePosition.Top, UppercasePosition.Bottom)) {
            ThemeManager.prefs.uppercasePosition.setValue(uppercase)
            // Request the same edge to exercise the punctuation collision fallback.
            ThemeManager.prefs.punctuationPosition.setValue(
                if (uppercase == UppercasePosition.Top) PunctuationPosition.Top
                else PunctuationPosition.Bottom
            )
            val uppercaseDirection = if (uppercase == UppercasePosition.Top) -2 else 2
            for ((group, letters, punctuation) in listOf(
                Triple("io", "IO", "6"), Triple("we", "WE", "2"), Triple("l;", "L", "-")
            )) {
                val keyboard = createRecordingKeyboard(groupedDefinition(group))
                assertTrue(keyboard.key.swipeEnabled)
                assertEquals(letters, keyboard.previewText(uppercaseDirection))
                assertTrue(swipe(keyboard.key, uppercaseDirection))
                assertEquals(listOf(KeyAction.CommitAction(letters, swipeSymbol = true)), keyboard.actions)

                keyboard.actions.clear()
                assertEquals(punctuation, keyboard.previewText(-uppercaseDirection))
                assertTrue(swipe(keyboard.key, -uppercaseDirection))
                assertEquals(listOf(KeyAction.FcitxKeyAction(punctuation)), keyboard.actions)
            }
        }
    }

    @Test
    fun aPhysicalMacroStillTakesPriorityOverTheGroupedUppercaseHint() = withTheme {
        for (uppercase in listOf(UppercasePosition.Top, UppercasePosition.Bottom)) {
            ThemeManager.prefs.uppercasePosition.setValue(uppercase)
            ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
            val original = groupedDefinition("io")
            val macro = MacroAction(listOf(MacroStep.Text("explicit swipe")))
            val direction = if (uppercase == UppercasePosition.Top) -2 else 2
            val definition = KeyDef(
                original.appearance,
                original.behaviors.filterNot { it is KeyDef.Behavior.Swipe }.toSet() +
                    KeyDef.Behavior.Swipe(
                        action = KeyAction.FcitxKeyAction("6"),
                        upMacro = macro.takeIf { direction < 0 },
                        downMacro = macro.takeIf { direction > 0 },
                    ),
                original.popup,
            )
            val keyboard = createRecordingKeyboard(definition)
            assertTrue(swipe(keyboard.key, direction))
            assertEquals(listOf(macro), keyboard.actions)
        }
    }

    private fun groupedDefinition(character: String): KeyDef =
        Shuangpin18Keys.layout(false, semicolon = true).take(3).flatten().single {
            val appearance = it.appearance as? KeyDef.Appearance.AltText
            appearance?.isLetterGroup == true && appearance.character == character
        }

    private fun createRecordingKeyboard(definition: KeyDef): RecordingKeyboard =
        RecordingKeyboard(instrumentation.targetContext, definition).also { keyboard ->
            val density = keyboard.resources.displayMetrics.density
            val width = (400 * density).roundToInt()
            val height = (96 * density).roundToInt()
            repeat(3) {
                keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                keyboard.layout(0, 0, width, height)
            }
        }

    private fun swipe(view: KeyView, totalY: Int): Boolean {
        val listener = requireNotNull(view.onGestureListener)
        val x = view.width / 2f
        val y = view.height / 2f
        var consumed = false
        fun send(type: GestureType, countY: Int, accumulatedY: Int): Boolean {
            val handled = listener.onGesture(
                view,
                Event(type, consumed, x, y + accumulatedY * view.swipeThresholdY,
                    0, countY, 0, accumulatedY)
            )
            consumed = consumed || handled
            return handled
        }
        send(GestureType.Down, 0, 0)
        send(GestureType.Move, totalY, totalY)
        return send(GestureType.Up, 0, totalY)
    }

    private class RecordingKeyboard(context: Context, private val definition: KeyDef) :
        BaseKeyboard(context, ThemePreset.MaterialLight, { listOf(listOf(definition)) }) {
        val actions = mutableListOf<KeyAction>()
        val key: AltTextKeyView
            get() = allViews.filterIsInstance<AltTextKeyView>().single()

        @SuppressLint("MissingSuperCall")
        override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
            actions += action
        }

        fun previewText(totalY: Int): String {
            val method = BaseKeyboard::class.java.getDeclaredMethod(
                "selectAltPreviewText", View::class.java, Integer.TYPE,
                KeyDef.Popup.AltPreview::class.java, KeyDef.Behavior.Swipe::class.java
            ).apply { isAccessible = true }
            return method.invoke(
                this, key, totalY, definition.popup.orEmpty().filterIsInstance<KeyDef.Popup.AltPreview>().single(),
                definition.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
            ) as String
        }
    }

    private fun groupedKeys(context: Context, semicolon: Boolean): List<AltTextKeyView> =
        Shuangpin18Keys.layout(false, semicolon).take(3).flatten().mapNotNull {
            val appearance = it.appearance as? KeyDef.Appearance.AltText
            appearance?.takeIf { def -> def.isLetterGroup }?.let { def ->
                AltTextKeyView(context, ThemePreset.MaterialLight, def)
            }
        }.also { assertEquals(18, it.size) }

    private fun assertOnEdge(label: AutoScaleTextView, top: Boolean, message: String) {
        assertEquals(message, View.VISIBLE, label.visibility)
        val params = label.layoutParams as ConstraintLayout.LayoutParams
        assertEquals(message, ConstraintLayout.LayoutParams.PARENT_ID,
            if (top) params.topToTop else params.bottomToBottom)
        assertEquals(message, ConstraintLayout.LayoutParams.UNSET,
            if (top) params.bottomToBottom else params.topToTop)
    }

    private fun assertCentered(key: AltTextKeyView, label: AutoScaleTextView, message: String) {
        assertEquals(message, Gravity.CENTER_HORIZONTAL,
            label.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(message, key.getChildAt(0).width / 2f, bounds(label).centerX(), 1f)
    }

    private fun assertMainLabelBetweenHints(key: AltTextKeyView, message: String) {
        val main = bounds(key.mainText)
        val center = key.getChildAt(0).height / 2f
        val hints = listOfNotNull(key.altText, key.altText1, key.groupedUppercaseText)
            .filter { it.visibility == View.VISIBLE && it.text.isNotBlank() }
        assertTrue(message, main.height() > 0f)
        hints.forEach { hint ->
            val hintBounds = bounds(hint)
            if (hintBounds.centerY() < center) {
                assertTrue("Main label overlaps upper hint: $message", main.top >= hintBounds.bottom - 1f)
            } else {
                assertTrue("Main label overlaps lower hint: $message", main.bottom <= hintBounds.top + 1f)
            }
        }
    }

    private fun bounds(label: AutoScaleTextView): RectF = RectF(label.renderedGlyphBounds()).apply {
        offset(label.left.toFloat(), label.top.toFloat())
    }

    private fun layoutKey(key: AltTextKeyView, landscape: Boolean) {
        val density = key.resources.displayMetrics.density
        val width = ((if (landscape) 100 else 60) * density).roundToInt()
        val height = ((if (landscape) 38 else 56) * density).roundToInt()
        repeat(3) {
            key.refreshLayout()
            key.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            key.layout(0, 0, width, height)
        }
    }

    private fun keyboardContext(landscape: Boolean, density: Int): Context =
        instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE
                    else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) 900 else 400
                screenHeightDp = if (landscape) 400 else 900
                densityDpi = density
                fontScale = 1f
            }
        )

    private fun withTheme(block: () -> Unit) {
        instrumentation.runOnMainSync {
            val prefs = ThemeManager.prefs
            val oldPunctuation = prefs.punctuationPosition.getValue()
            val oldUppercase = prefs.uppercasePosition.getValue()
            val keyboardPrefs = AppPrefs.getInstance().keyboard
            val oldDirection = keyboardPrefs.swipeSymbolDirection.getValue()
            val oldHapticOnRepeat = keyboardPrefs.hapticOnRepeat.getValue()
            val oldPunctuationStrategy = keyboardPrefs.punctuationSwipeStrategy.getValue()
            try {
                keyboardPrefs.swipeSymbolDirection.setValue(SwipeSymbolDirection.Auto)
                keyboardPrefs.hapticOnRepeat.setValue(false)
                keyboardPrefs.punctuationSwipeStrategy.setValue(PunctuationSwipeStrategy.Default)
                block()
            } finally {
                prefs.punctuationPosition.setValue(oldPunctuation)
                prefs.uppercasePosition.setValue(oldUppercase)
                keyboardPrefs.swipeSymbolDirection.setValue(oldDirection)
                keyboardPrefs.hapticOnRepeat.setValue(oldHapticOnRepeat)
                keyboardPrefs.punctuationSwipeStrategy.setValue(oldPunctuationStrategy)
            }
        }
    }
}
