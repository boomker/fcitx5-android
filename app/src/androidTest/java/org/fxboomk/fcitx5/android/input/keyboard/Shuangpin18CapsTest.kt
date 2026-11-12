/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.*
import org.junit.Test

/** Render the real keyboard, recording actions instead of sending keys into the user's editor. */
class Shuangpin18CapsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun compactCapsSwipeSeparatesWordsWithoutTogglingCaps() = withKeyboard { scene ->
        val originalCaps = onMain {
            assertCompact(scene.keyboard)
            caps(scene.keyboard) as ImageAltTextKeyView
        }
        val positions = listOf(PunctuationPosition.Top, PunctuationPosition.TopRight,
            PunctuationPosition.Bottom, PunctuationPosition.None, PunctuationPosition.Top)
        for (position in positions) {
            onMain {
                ThemeManager.prefs.punctuationPosition.setValue(position)
            }
            instrumentation.waitForIdleSync()
            onMain {
                layout(scene.keyboard)
                assertSame("Changing $position replaced the Caps view", originalCaps, caps(scene.keyboard))
                assertCapsSeparatorPosition(originalCaps, position)
                scene.actions.clear()
                swipeUp(originalCaps)
            }
            waitFor("Apostrophe macro did not release its key at $position") {
                scene.actions.filterIsInstance<KeyAction.FcitxKeyAction>().any { it.up }
            }
            onMain {
                val keys = scene.actions.filterIsInstance<KeyAction.FcitxKeyAction>()
                assertEquals(listOf("apostrophe", "apostrophe"), keys.map { it.act })
                assertEquals(listOf(false, true), keys.map { it.up })
                assertTrue(keys.none { it.states.has(KeyState.Shift) })
                assertTrue(scene.actions.none { it is KeyAction.CommitAction || it is KeyAction.CapsAction })
                assertCompact(scene.keyboard)
                assertSame(originalCaps, caps(scene.keyboard))
            }
        }
        onMain {
            tap(textKey(scene.keyboard, "WE"))
            assertTrue(scene.actions.filterIsInstance<KeyAction.SymAction>().any {
                Shuangpin18Keys.indexOf(it.sym) == 1
            })
        }
    }

    @Test
    fun oneCapsTapShows26UppercaseKeysAndReturnsAfterOneLetter() = withKeyboard { scene ->
        onMain {
            tap(caps(scene.keyboard))
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            scene.actions.clear()
            tap(textKey(scene.keyboard, "W"))
            layout(scene.keyboard)
            assertUppercaseW(scene.actions)
            assertCompact(scene.keyboard)
            assertTrue("Temporary uppercase mode overwrote the stored compact layout",
                XIAOHE in AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue())
        }
    }

    @Test
    fun doubleTapLocksAcrossTheRebuiltCapsView() = withKeyboard { scene ->
        onMain {
            val firstCaps = caps(scene.keyboard)
            tap(firstCaps)
            layout(scene.keyboard)
            val secondCaps = caps(scene.keyboard)
            assertNotSame("The test must exercise a replacement Caps view", firstCaps, secondCaps)
            tap(secondCaps)
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            scene.actions.clear()
            tap(textKey(scene.keyboard, "W"))
            layout(scene.keyboard)
            assertUppercaseW(scene.actions)
            assertUppercase26(scene.keyboard)
            tap(caps(scene.keyboard))
            layout(scene.keyboard)
            assertCompact(scene.keyboard)
        }
    }

    @Test
    fun longPressLockSurvivesLettersAndCanBeUnlocked() = withKeyboard { scene ->
        onMain {
            assertTrue(caps(scene.keyboard).performLongClick())
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            scene.actions.clear()
            tap(textKey(scene.keyboard, "W"))
            layout(scene.keyboard)
            assertUppercaseW(scene.actions)
            assertUppercase26(scene.keyboard)
            tap(caps(scene.keyboard))
            layout(scene.keyboard)
            assertCompact(scene.keyboard)
        }
    }

    @Test
    fun inputMethodChangeAndReattachmentDoNotKeepStaleGroupedActions() = withKeyboard { scene ->
        onMain {
            val staleGroupedKey = textKey(scene.keyboard, "WE")
            tap(caps(scene.keyboard))
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            scene.keyboard.onInputMethodUpdate(entry("keyboard-us", "keyboard"))
            layout(scene.keyboard)
            scene.actions.clear()
            assertTrue(staleGroupedKey.performClick())
            assertTrue("A detached grouped key leaked into English input", scene.actions.isEmpty())
            scene.activity.setContentView(FrameLayout(scene.activity))
            scene.activity.setContentView(scene.keyboard)
            scene.keyboard.onAttach()
            scene.keyboard.onInputMethodUpdate(entry(XIAOHE, "pinyin"))
            layout(scene.keyboard)
            assertCompact(scene.keyboard)
        }
    }

    private data class Scene(
        val activity: MainActivity,
        val keyboard: TextKeyboard,
        val actions: MutableList<KeyAction>,
    )

    private fun withKeyboard(block: (Scene) -> Unit) {
        val prefs = AppPrefs.getInstance()
        val compact = prefs.internal.compactShuangpinInputMethods.getValue()
        val behavior = prefs.keyboard.capsKeyBehavior.getValue()
        val uppercase = prefs.keyboard.keepLettersUppercase.getValue()
        val popup = prefs.keyboard.popupOnKeyPress.getValue()
        val swipe = prefs.keyboard.swipeSymbolDirection.getValue()
        val haptic = prefs.keyboard.hapticOnKeyPress.getValue()
        val sound = prefs.keyboard.soundOnKeyPress.getValue()
        val punctuationPosition = ThemeManager.prefs.punctuationPosition.getValue()
        var activity: MainActivity? = null
        try {
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(compact + XIAOHE)
                prefs.keyboard.capsKeyBehavior.setValue(CapsKeyBehavior.Default)
                prefs.keyboard.keepLettersUppercase.setValue(false)
                prefs.keyboard.popupOnKeyPress.setValue(false)
                prefs.keyboard.swipeSymbolDirection.setValue(SwipeSymbolDirection.Auto)
                prefs.keyboard.hapticOnKeyPress.setValue(InputFeedbackMode.Disabled)
                prefs.keyboard.soundOnKeyPress.setValue(InputFeedbackMode.Disabled)
            }
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val host = activity
            val scene = onMain {
                val actions = mutableListOf<KeyAction>()
                val keyboard = TextKeyboard(host, ThemePreset.MaterialLight, entry(XIAOHE, "pinyin"))
                keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
                host.setContentView(keyboard)
                keyboard.onAttach()
                layout(keyboard)
                Scene(host, keyboard, actions)
            }
            waitFor("The keyboard was not attached to its real Activity") { scene.keyboard.isAttachedToWindow }
            block(scene)
        } finally {
            onMain {
                activity?.finish()
                prefs.internal.compactShuangpinInputMethods.setValue(compact)
                prefs.keyboard.capsKeyBehavior.setValue(behavior)
                prefs.keyboard.keepLettersUppercase.setValue(uppercase)
                prefs.keyboard.popupOnKeyPress.setValue(popup)
                prefs.keyboard.swipeSymbolDirection.setValue(swipe)
                prefs.keyboard.hapticOnKeyPress.setValue(haptic)
                prefs.keyboard.soundOnKeyPress.setValue(sound)
                ThemeManager.prefs.punctuationPosition.setValue(punctuationPosition)
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun caps(keyboard: TextKeyboard): KeyView =
        keyboard.allViews.filterIsInstance<KeyView>().single { it.def.viewId == R.id.button_caps }

    private fun textKey(keyboard: TextKeyboard, label: String): TextKeyView =
        keyboard.allViews.filterIsInstance<TextKeyView>().single { it.mainText.text.toString() == label }

    private fun labels(keyboard: TextKeyboard): List<String> =
        keyboard.allViews.filterIsInstance<TextKeyView>().map { it.mainText.text.toString() }.toList()

    private fun assertCompact(keyboard: TextKeyboard) {
        assertTrue("The grouped WE key is missing", "WE" in labels(keyboard))
        assertFalse("The uppercase 26-key layout was retained", "W" in labels(keyboard))
        caps(keyboard)
    }

    private fun assertCapsSeparatorPosition(key: ImageAltTextKeyView, position: PunctuationPosition) {
        val label = key.altText
        assertEquals("'", label.text.toString())
        assertEquals(View.GONE, key.altText1.visibility)
        assertEquals(if (position == PunctuationPosition.None) View.GONE else View.VISIBLE, label.visibility)
        if (position == PunctuationPosition.None) return

        val params = label.layoutParams as ConstraintLayout.LayoutParams
        val parent = label.parent as View
        val diagnostic = "position=$position; label=[${label.left},${label.top},${label.right},${label.bottom}] " +
            "measured=${label.measuredWidth}x${label.measuredHeight} paint.textSize=${label.paint.textSize} " +
            "useGlyphBounds=${label.useGlyphBounds}; " +
            "parent=[${parent.left},${parent.top},${parent.right},${parent.bottom}] " +
            "measured=${parent.measuredWidth}x${parent.measuredHeight}; " +
            "icon=[${key.img.left},${key.img.top},${key.img.right},${key.img.bottom}] " +
            "measured=${key.img.measuredWidth}x${key.img.measuredHeight}; " +
            "params topToTop=${params.topToTop} topToBottom=${params.topToBottom} " +
            "bottomToBottom=${params.bottomToBottom} bottomToTop=${params.bottomToTop} " +
            "leftToLeft=${params.leftToLeft} rightToRight=${params.rightToRight} " +
            "width=${params.width} height=${params.height} " +
            "margins=${params.leftMargin},${params.topMargin},${params.rightMargin},${params.bottomMargin}"
        assertTrue("The separator was not laid out: $diagnostic", label.width > 0 && label.height > 0)
        assertTrue(label.left >= 0 && label.right <= parent.width)
        val labelCenterY = (label.top + label.bottom) / 2f
        val iconCenterY = (key.img.top + key.img.bottom) / 2f
        if (position == PunctuationPosition.Bottom) {
            assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.bottomToBottom)
            assertEquals(ConstraintLayout.LayoutParams.UNSET, params.topToTop)
            assertTrue("The separator is above the icon at Bottom", labelCenterY > iconCenterY)
        } else {
            assertEquals(ConstraintLayout.LayoutParams.PARENT_ID, params.topToTop)
            assertEquals(ConstraintLayout.LayoutParams.UNSET, params.bottomToBottom)
            assertTrue("The separator is below the icon at $position", labelCenterY < iconCenterY)
        }
        assertEquals(when (position) {
            PunctuationPosition.Top -> Gravity.CENTER
            PunctuationPosition.TopRight -> Gravity.END or Gravity.CENTER_VERTICAL
            PunctuationPosition.Bottom -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            PunctuationPosition.None -> error("Hidden labels have no placement")
        }, label.gravity)
    }

    private fun assertUppercase26(keyboard: TextKeyboard) {
        val letters = labels(keyboard).filter { it.length == 1 && it[0].lowercaseChar() in 'a'..'z' }
        assertEquals("The temporary layout must render all 26 uppercase letters",
            ('A'..'Z').map(Char::toString), letters.sorted())
        assertFalse("A grouped key survived uppercase mode", "WE" in labels(keyboard))
    }

    private fun assertUppercaseW(actions: List<KeyAction>) {
        val key = actions.filterIsInstance<KeyAction.FcitxKeyAction>().last { !it.up }
        assertEquals("W", key.act)
        assertTrue(key.states.has(KeyState.Virtual))
        assertTrue(key.states.has(KeyState.Shift))
        assertFalse(key.states.has(KeyState.CapsLock))
    }

    private fun layout(keyboard: TextKeyboard) {
        val density = keyboard.resources.displayMetrics.density
        val width = (400 * density).toInt()
        val height = (320 * density).toInt()
        repeat(2) {
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, width, height)
        }
    }

    private fun tap(view: KeyView) {
        val now = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 16 else 0,
                action, x, y, 0)
            try {
                assertTrue("A physical tap was not handled", view.onTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }

    private fun swipeUp(view: KeyView) {
        val listener = requireNotNull(view.onGestureListener)
        val x = view.width / 2f
        val y = view.height / 2f
        listener.onGesture(view, Event(GestureType.Down, false, x, y, 0, 0, 0, 0))
        val endY = y - 2 * view.swipeThresholdY
        val consumed = listener.onGesture(view, Event(GestureType.Move, false, x, endY, 0, -2, 0, -2))
        assertTrue(listener.onGesture(view, Event(GestureType.Up, consumed, x, endY, 0, 0, 0, -2)))
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(20)
        }
        fail(message)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { result[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    private fun entry(name: String, addon: String) =
        InputMethodEntry(name, name, "", name, name, "zh_CN", addon, true)

    private companion object {
        const val XIAOHE = "shuangpin-xiaohe"
    }
}
