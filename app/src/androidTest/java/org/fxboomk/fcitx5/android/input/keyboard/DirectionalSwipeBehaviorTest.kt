/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionalSwipeBehaviorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun eachKeyTypeDispatchesItsPhysicalUpAndDownMacros() = withPreferences {
        val up = macro("up")
        val down = macro("down")
        keyFactories.forEach { factory ->
            val keyboard = createKeyboard(factory(up, down, "UP", "DOWN"))
            assertSwipes(keyboard, up, down)
        }
    }

    @Test
    fun explicitMacrosOverrideGlobalSwipeDirectionForEveryKeyType() = withPreferences {
        val up = macro("explicit up")
        val down = macro("explicit down")
        listOf(SwipeSymbolDirection.Disabled, SwipeSymbolDirection.Up, SwipeSymbolDirection.Down)
            .forEach { direction ->
                AppPrefs.getInstance().keyboard.swipeSymbolDirection.setValue(direction)
                keyFactories.forEach { factory ->
                    val keyboard = createKeyboard(factory(up, down, "UP", "DOWN"))
                    assertSwipes(keyboard, up, down)
                }
            }
    }

    @Test
    fun groupedSwipeEventsOverrideBothAltCharactersAndGlobalDirections() = withPreferences {
        val up = macro("explicit group up")
        val down = macro("explicit group down")
        SwipeSymbolDirection.entries.forEach { direction ->
            AppPrefs.getInstance().keyboard.swipeSymbolDirection.setValue(direction)
            listOf(PunctuationPosition.Top, PunctuationPosition.Bottom, PunctuationPosition.None).forEach { position ->
                ThemeManager.prefs.punctuationPosition.setValue(position)
                val keyboard = createKeyboard(ShuangpinGroupKey(
                    groupId = "we", punctuation = "@", punctuation1 = "#",
                    swipeUp = up, swipeDown = down,
                ))
                assertSwipes(keyboard, up, down)
            }
        }
    }

    @Test
    fun physicalMacrosWorkWithoutLabelsAndWithPunctuationHidden() = withPreferences {
        val up = macro("up without visible label")
        val down = macro("down without visible label")
        listOf(
            Triple(PunctuationPosition.Top, null, null),
            Triple(PunctuationPosition.None, "UP", "DOWN"),
            Triple(PunctuationPosition.None, null, null)
        ).forEach { (position, upLabel, downLabel) ->
            ThemeManager.prefs.punctuationPosition.setValue(position)
            keyFactories.forEach { factory ->
                val keyboard = createKeyboard(factory(up, down, upLabel, downLabel))
                assertSwipes(keyboard, up, down)
            }
        }
    }

    @Test
    fun backspaceHorizontalSwipeStillSelectsAndDeletesWithoutFiringVerticalMacros() = withPreferences {
        val keyboard = createKeyboard(
            BackspaceKey(
                swipeUp = macro("up"),
                swipeDown = macro("down"),
                swipeUpLabel = "UP",
                swipeDownLabel = "DOWN"
            )
        )
        // A small vertical drift must not turn a horizontal selection into a macro.
        listOf(0, -1, 1).forEach { verticalDrift ->
            keyboard.actions.clear()
            swipe(keyboard.key, totalX = -5, totalY = verticalDrift)

            assertEquals(
                listOf(
                    KeyAction.MoveSelectionAction(-5),
                    KeyAction.DeleteSelectionAction(-5)
                ),
                keyboard.actions
            )
        }
    }

    @Test
    fun backspaceVerticalSwipeWithHorizontalDriftOnlyDispatchesItsMacro() = withPreferences {
        val up = macro("up")
        val down = macro("down")
        val keyboard = createKeyboard(BackspaceKey(swipeUp = up, swipeDown = down))
        // X and Y use different thresholds: five X steps still travel less than two Y steps.
        listOf(-1, 1, -5, 5).forEach { horizontalDrift ->
            listOf(-2, 2).forEach { verticalTotal ->
                keyboard.actions.clear()
                assertTrue(swipe(keyboard.key, totalX = horizontalDrift, totalY = verticalTotal))

                assertEquals(listOf(if (verticalTotal < 0) up else down), keyboard.actions)
            }
        }
    }

    @Test
    fun backspaceKeepsHorizontalSelectionAfterLaterVerticalMovement() = withPreferences {
        val keyboard = createKeyboard(
            BackspaceKey(swipeUp = macro("up"), swipeDown = macro("down"))
        )
        listOf(-4, 4).forEach { verticalTotal ->
            keyboard.actions.clear()
            swipePath(keyboard.key, -5 to 0, -5 to verticalTotal)

            assertEquals(
                listOf(
                    KeyAction.MoveSelectionAction(-5),
                    KeyAction.DeleteSelectionAction(-5)
                ),
                keyboard.actions
            )
        }
    }

    @Test
    fun backspaceUnboundVerticalDirectionDoesNotDeleteSelection() = withPreferences {
        listOf(
            BackspaceKey(swipeUp = macro("up only")) to 2,
            BackspaceKey(swipeDown = macro("down only")) to -2
        ).forEach { (definition, unboundDirection) ->
            val keyboard = createKeyboard(definition)
            listOf(0, -1, 1).forEach { horizontalDrift ->
                keyboard.actions.clear()
                swipe(keyboard.key, totalX = horizontalDrift, totalY = unboundDirection)

                assertTrue(keyboard.actions.isEmpty())
            }
        }
    }

    @Test
    fun backspaceConsumedOrRepeatReleaseDoesNotTriggerDirectSwipeMacros() = withPreferences {
        val keyboard = createKeyboard(
            BackspaceKey(swipeUp = macro("direct up"), swipeDown = macro("direct down"))
        )
        // Repeat movement does not accumulate swipe counts; consumed gestures are also separate.
        listOf(true to -2, true to 2, false to 0).forEach { (consumed, totalY) ->
            keyboard.actions.clear()
            val key = keyboard.key
            val listener = requireNotNull(key.onGestureListener)
            val x = key.width / 2f
            val startY = key.height / 2f
            val endY = if (totalY == 0) {
                -10f * context.resources.displayMetrics.density
            } else {
                startY + totalY * key.swipeThresholdY
            }
            listener.onGesture(key, Event(GestureType.Down, false, x, startY, 0, 0, 0, 0))
            listener.onGesture(key, Event(GestureType.Up, consumed, x, endY, 0, 0, 0, totalY))

            assertTrue(keyboard.actions.none { it is MacroAction })
        }
    }

    @Test
    fun compositionSwitchesBothMacrosAndBothLabelsForEveryKeyType() = withPreferences {
        val baseUp = macro("base up")
        val baseDown = macro("base down")
        val composeUp = macro("compose up")
        val composeDown = macro("compose down")
        keyFactories.forEach { factory ->
            val definition = factory(baseUp, baseDown, "BASE UP", "BASE DOWN").apply {
                composeOverride = factory(composeUp, composeDown, "COMPOSE UP", "COMPOSE DOWN")
            }
            val keyboard = createKeyboard(definition)
            listOf(false, true, false, true, false).forEach { composing ->
                keyboard.onCompositionStateChanged(composing)
                layout(keyboard)

                assertEquals(
                    if (composing) "COMPOSE UP" to "COMPOSE DOWN" else "BASE UP" to "BASE DOWN",
                    labels(keyboard.key)
                )
                assertSwipes(
                    keyboard,
                    if (composing) composeUp else baseUp,
                    if (composing) composeDown else baseDown
                )
            }
        }
    }

    private fun assertSwipes(keyboard: RecordingKeyboard, up: MacroAction, down: MacroAction) {
        assertTrue(keyboard.key.swipeEnabled)
        keyboard.actions.clear()
        assertTrue(swipe(keyboard.key, totalY = -2))
        assertEquals(listOf(up), keyboard.actions)
        keyboard.actions.clear()
        assertTrue(swipe(keyboard.key, totalY = 2))
        assertEquals(listOf(down), keyboard.actions)
    }

    private fun swipe(view: KeyView, totalX: Int = 0, totalY: Int): Boolean =
        swipePath(view, totalX to totalY)

    private fun swipePath(view: KeyView, vararg totals: Pair<Int, Int>): Boolean {
        require(totals.isNotEmpty())
        val listener = requireNotNull(view.onGestureListener)
        val startX = view.width / 2f
        val startY = view.height / 2f
        var consumed = false
        fun send(type: GestureType, countX: Int, countY: Int, xTotal: Int, yTotal: Int): Boolean {
            val handled = listener.onGesture(
                view,
                Event(
                    type = type,
                    consumed = consumed,
                    x = startX + xTotal * view.swipeThresholdX,
                    y = startY + yTotal * view.swipeThresholdY,
                    countX = countX,
                    countY = countY,
                    totalX = xTotal,
                    totalY = yTotal
                )
            )
            consumed = consumed || handled
            return handled
        }
        // Deliver the same accumulated threshold counts and consumption state as CustomGestureView.
        send(GestureType.Down, 0, 0, 0, 0)
        var previousX = 0
        var previousY = 0
        totals.forEach { (x, y) ->
            send(GestureType.Move, x - previousX, y - previousY, x, y)
            previousX = x
            previousY = y
        }
        return send(GestureType.Up, 0, 0, previousX, previousY)
    }

    private fun labels(view: KeyView): Pair<String, String> = when (view) {
        is AltTextKeyView -> view.altText.text.toString() to view.altText1.text.toString()
        is ImageAltTextKeyView -> view.altText.text.toString() to view.altText1.text.toString()
        else -> error("Expected a view with directional labels")
    }

    private fun createKeyboard(definition: KeyDef) = RecordingKeyboard(context, definition).also(::layout)

    private fun layout(keyboard: RecordingKeyboard) {
        val density = context.resources.displayMetrics.density
        val width = (400 * density).toInt()
        val height = (96 * density).toInt()
        repeat(2) {
            keyboard.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            keyboard.layout(0, 0, width, height)
        }
    }

    private fun withPreferences(block: () -> Unit) = instrumentation.runOnMainSync {
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val originalDirection = keyboardPrefs.swipeSymbolDirection.getValue()
        val originalHapticOnRepeat = keyboardPrefs.hapticOnRepeat.getValue()
        val originalPosition = ThemeManager.prefs.punctuationPosition.getValue()
        val originalSideKeyStyle = ThemeManager.prefs.gboardStyleSideKeys.getValue()
        try {
            keyboardPrefs.swipeSymbolDirection.setValue(SwipeSymbolDirection.Auto)
            keyboardPrefs.hapticOnRepeat.setValue(false)
            ThemeManager.prefs.punctuationPosition.setValue(PunctuationPosition.Top)
            ThemeManager.prefs.gboardStyleSideKeys.setValue(false)
            block()
        } finally {
            keyboardPrefs.swipeSymbolDirection.setValue(originalDirection)
            keyboardPrefs.hapticOnRepeat.setValue(originalHapticOnRepeat)
            ThemeManager.prefs.punctuationPosition.setValue(originalPosition)
            ThemeManager.prefs.gboardStyleSideKeys.setValue(originalSideKeyStyle)
        }
    }

    private fun macro(text: String) = MacroAction(listOf(MacroStep.Text(text)))

    private class RecordingKeyboard(context: Context, definition: KeyDef) :
        BaseKeyboard(context, ThemePreset.MaterialLight, { listOf(listOf(definition)) }) {
        val actions = mutableListOf<KeyAction>()
        val key: KeyView
            get() = allViews.filterIsInstance<KeyView>().single()

        @SuppressLint("MissingSuperCall")
        override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
            actions += action
        }
    }

    private val keyFactories: List<(MacroAction, MacroAction, String?, String?) -> KeyDef> = listOf(
        { up, down, upLabel, downLabel ->
            CapsKey(swipeUp = up, swipeDown = down, swipeUpLabel = upLabel, swipeDownLabel = downLabel)
        },
        { up, down, upLabel, downLabel ->
            LayoutSwitchKey("?123", swipeUp = up, swipeDown = down, swipeUpLabel = upLabel, swipeDownLabel = downLabel)
        },
        { up, down, upLabel, downLabel ->
            SymbolKey("!", swipeUp = up, swipeDown = down, swipeUpLabel = upLabel, swipeDownLabel = downLabel)
        },
        { up, down, upLabel, downLabel ->
            ReturnKey(swipeUp = up, swipeDown = down, swipeUpLabel = upLabel, swipeDownLabel = downLabel)
        },
        { up, down, upLabel, downLabel ->
            BackspaceKey(swipeUp = up, swipeDown = down, swipeUpLabel = upLabel, swipeDownLabel = downLabel)
        }
    )
}
