/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeCodec
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.MemoryConfigProvider
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.popup.PopupAction
import org.fxboomk.fcitx5.android.input.popup.PopupActionListener
import org.fxboomk.fcitx5.android.input.popup.resolveGroupedPopupPresetCandidates
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.*
import org.junit.Test

/** Render the real keyboard, recording actions instead of sending keys into the user's editor. */
class Shuangpin18CapsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun composingShowsATapSeparatorInTheCapsSlotAndRestoresCaps() = withKeyboard { scene ->
        val positions = listOf(PunctuationPosition.Top, PunctuationPosition.TopRight,
            PunctuationPosition.Bottom, PunctuationPosition.None)
        for (position in positions) {
            onMain {
                ThemeManager.prefs.punctuationPosition.setValue(position)
            }
            instrumentation.waitForIdleSync()
            onMain {
                layout(scene.keyboard)
                val originalCaps = caps(scene.keyboard)
                val originalGroup = textKey(scene.keyboard, "we")
                assertTrue(originalCaps is ImageKeyView)
                assertFalse("Caps still exposes an upward separator gesture", originalCaps.swipeEnabled)
                val slot = listOf(originalCaps.left, originalCaps.top, originalCaps.width, originalCaps.height)
                scene.keyboard.onCompositionStateChanged(true)
                layout(scene.keyboard)
                val separator = textKey(scene.keyboard, "'词")
                assertEquals(slot, listOf(separator.left, separator.top, separator.width, separator.height))
                assertEquals(View.VISIBLE, separator.mainText.visibility)
                assertTrue("The separator label is missing at $position", separator.mainText.width > 0)
                assertSame("Composing replaced an unrelated group key", originalGroup, textKey(scene.keyboard, "we"))
                scene.keyboard.onCompositionStateChanged(true)
                assertSame(separator, textKey(scene.keyboard, "'词"))
                scene.actions.clear()
                tap(separator)
                assertSeparatorKeys(scene.actions)
                assertCompact(scene.keyboard)
                scene.keyboard.onCompositionStateChanged(false)
                layout(scene.keyboard)
                assertTrue(caps(scene.keyboard) is ImageKeyView)
                assertFalse("The separator label survived the end of composing", "'词" in labels(scene.keyboard))
            }
        }
        onMain {
            tap(textKey(scene.keyboard, "we"))
            assertTrue(scene.actions.filterIsInstance<KeyAction.SymAction>().any {
                Shuangpin18Keys.indexOf(it.sym) == 1
            })
        }
    }

    @Test
    fun separatorHasOnlyTapActionsAfterRebuildAndReattachment() = withKeyboard { scene ->
        onMain {
            scene.keyboard.onCompositionStateChanged(true)
            layout(scene.keyboard)
            val separator = textKey(scene.keyboard, "'词")
            assertFalse(separator.doubleTapEnabled)
            assertFalse(separator.repeatEnabled)
            assertFalse(separator.swipeEnabled)
            scene.actions.clear()
            assertFalse("Holding the separator still has a long-press action", separator.performLongClick())
            assertTrue(scene.actions.isEmpty())
            tap(separator)
            tap(separator)
            assertSeparatorKeys(scene.actions, taps = 2)
            assertCompact(scene.keyboard)
            scene.keyboard.onInputMethodUpdate(entry("keyboard-us", "keyboard"))
            layout(scene.keyboard)
            assertFalse("The 18-key separator leaked into English", "'词" in labels(scene.keyboard))
            scene.keyboard.onInputMethodUpdate(entry(XIAOHE, "pinyin"))
            layout(scene.keyboard)
            textKey(scene.keyboard, "'词")
            scene.activity.setContentView(FrameLayout(scene.activity))
            scene.activity.setContentView(scene.keyboard)
            scene.keyboard.onAttach()
            layout(scene.keyboard)
            assertCompact(scene.keyboard)
            assertFalse(textKey(scene.keyboard, "'词").performLongClick())
            scene.keyboard.onCompositionStateChanged(false)
            layout(scene.keyboard)
            assertTrue(caps(scene.keyboard).performLongClick())
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
        }
    }

    @Test
    fun temporaryUppercase26KeepsItsCapsKeyWhileComposing() = withKeyboard { scene ->
        onMain {
            tap(caps(scene.keyboard))
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            val uppercaseCaps = caps(scene.keyboard)
            scene.keyboard.onCompositionStateChanged(true)
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            assertSame(uppercaseCaps, caps(scene.keyboard))
            assertFalse("The temporary 26-key Caps was replaced", "'词" in labels(scene.keyboard))
            tap(caps(scene.keyboard))
            layout(scene.keyboard)
            assertUppercase26(scene.keyboard)
            assertTrue(caps(scene.keyboard).performLongClick())
            layout(scene.keyboard)
            assertCompact(scene.keyboard)
            textKey(scene.keyboard, "'词")
            scene.keyboard.onCompositionStateChanged(false)
            layout(scene.keyboard)
            assertTrue(caps(scene.keyboard) is ImageKeyView)
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
            val staleGroupedKey = textKey(scene.keyboard, "we")
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

    @Test
    fun groupedLettersFollowTheUppercaseDisplayPreference() = withKeyboard { scene ->
        onMain {
            val prefs = AppPrefs.getInstance().keyboard
            assertGroupLabels(scene.keyboard, uppercase = false, semicolon = false)
            prefs.keepLettersUppercase.setValue(true)
            scene.keyboard.onInputMethodUpdate(entry(XIAOHE, "pinyin"))
            layout(scene.keyboard)
            assertGroupLabels(scene.keyboard, uppercase = true, semicolon = false)
            scene.actions.clear()
            tap(textKey(scene.keyboard, "WE"))
            assertTrue("Uppercase presentation changed the grouped key action", scene.actions.any {
                it is KeyAction.SymAction && Shuangpin18Keys.indexOf(it.sym) == 1 &&
                    !it.states.has(KeyState.Shift)
            })
            prefs.keepLettersUppercase.setValue(false)
            scene.keyboard.onInputMethodUpdate(entry(XIAOHE, "pinyin"))
            layout(scene.keyboard)
            assertGroupLabels(scene.keyboard, uppercase = false, semicolon = false)
        }
    }

    @Test
    fun microsoftSemicolonGroupFollowsTheUppercaseDisplayPreference() =
        withKeyboard(uniqueName = "shuangpin-ms") { scene ->
            onMain {
                assertGroupLabels(scene.keyboard, uppercase = false, semicolon = true)
                AppPrefs.getInstance().keyboard.keepLettersUppercase.setValue(true)
                scene.keyboard.onInputMethodUpdate(entry("shuangpin-ms", "pinyin"))
                layout(scene.keyboard)
                assertGroupLabels(scene.keyboard, uppercase = true, semicolon = true)
                AppPrefs.getInstance().keyboard.keepLettersUppercase.setValue(false)
                scene.keyboard.onInputMethodUpdate(entry("shuangpin-ms", "pinyin"))
                layout(scene.keyboard)
                assertGroupLabels(scene.keyboard, uppercase = false, semicolon = true)
            }
        }

    @Test
    fun switchingCompactSchemesRefreshesSemicolonLabelsAndPopups() = withKeyboard { scene ->
        onMain {
            val previousProvider = ConfigProviders.provider
            var shownPopup: KeyDef.Popup.Keyboard? = null
            scene.keyboard.popupActionListener = PopupActionListener { action ->
                if (action is PopupAction.ShowKeyboardAction) shownPopup = action.keyboard
            }
            fun assertSemicolonVariant(semicolon: Boolean) {
                assertGroupLabels(scene.keyboard, uppercase = false, semicolon = semicolon)
                shownPopup = null
                textKey(scene.keyboard, if (semicolon) "l;" else "l").performLongClick()
                assertTrue("The current key did not open its grouped popup",
                    shownPopup is KeyDef.Popup.Keyboard.GroupedPreset)
                val preset = shownPopup as KeyDef.Popup.Keyboard.GroupedPreset
                assertEquals(if (semicolon) "l;" else "l", preset.group)
                assertEquals("The literal semicolon must follow the active scheme", semicolon,
                    ";" in resolveGroupedPopupPresetCandidates(preset.group, preset.corner))
            }
            try {
                val compact = AppPrefs.getInstance().internal.compactShuangpinInputMethods
                compact.setValue(compact.getValue() + "shuangpin-ms")
                val layouts = listOf(
                    JsonObject(emptyMap()),
                    JsonObject(mapOf("shuangpin" to JsonArray(emptyList()))),
                )
                for (json in layouts) {
                    ConfigProviders.provider = MemoryConfigProvider(json, previousProvider)
                    scene.keyboard.onInputMethodUpdate(entry(XIAOHE, "pinyin"))
                    layout(scene.keyboard)
                    assertSemicolonVariant(false)
                    scene.keyboard.onInputMethodUpdate(entry("shuangpin-ms", "pinyin"))
                    layout(scene.keyboard)
                    assertSemicolonVariant(true)
                    scene.keyboard.onInputMethodUpdate(entry(XIAOHE, "pinyin"))
                    layout(scene.keyboard)
                    assertSemicolonVariant(false)
                }
            } finally {
                scene.keyboard.popupActionListener = null
                ConfigProviders.provider = previousProvider
                TextKeyboard.invalidateLayoutCache()
            }
        }
    }

    @Test
    fun customSchemeMappingsRefreshRenderedSemicolonAndPopup() {
        val original = CustomShuangpinSchemeStore.load()
        val uniqueName = CustomShuangpinSchemeStore.uniqueNameFor(original.id)
        val entryDir = UserConfigFiles.inputMethodConfDir()
        val originalEntries = entryDir?.listFiles().orEmpty().filter {
            it.isFile && ShuangpinLayoutKeys.isCustomSchemeUniqueName(it.name.removeSuffix(".conf"))
        }
        val files = listOf(UserConfigFiles.customShuangpinSchemeJson(),
            UserConfigFiles.customShuangpinSpDat()) + originalEntries
        val backups = files.associateWith { it.takeIf { file -> file.isFile }?.readBytes() }
        try {
            val default = CustomShuangpinSchemeCodec.defaultScheme().copy(name = "天鹅双拼", id = original.id)
            CustomShuangpinSchemeStore.save(default).getOrThrow()
            withKeyboard(uniqueName) { scene ->
                onMain {
                    var shownPopup: KeyDef.Popup.Keyboard? = null
                    scene.keyboard.popupActionListener = PopupActionListener { action ->
                        if (action is PopupAction.ShowKeyboardAction) shownPopup = action.keyboard
                    }
                    try {
                        val cases = listOf(
                            default to false,
                            default.copy(finals = default.finals + ("ing" to ";")) to true,
                            default to false,
                            default.copy(initials = default.initials + ("sh" to ";")) to true,
                            default to false,
                            default.copy(syllables = mapOf("ying" to listOf("y;"))) to true,
                            default to false,
                        )
                        for ((scheme, semicolon) in cases) {
                            CustomShuangpinSchemeStore.save(scheme).getOrThrow()
                            assertEquals(scheme, CustomShuangpinSchemeStore.load())
                            scene.keyboard.onInputMethodUpdate(entry(uniqueName, "pinyin"))
                            layout(scene.keyboard)
                            assertGroupLabels(scene.keyboard, uppercase = false, semicolon = semicolon)
                            val expected = if (semicolon) "l;" else "l"
                            val key = textKey(scene.keyboard, expected)
                            assertTrue("The key label was not rendered", key.mainText.width > 0)
                            assertTrue("The key label was hidden", key.mainText.visibility == View.VISIBLE)
                            shownPopup = null
                            key.performLongClick()
                            assertTrue("The current key did not open its grouped popup",
                                shownPopup is KeyDef.Popup.Keyboard.GroupedPreset)
                            val preset = shownPopup as KeyDef.Popup.Keyboard.GroupedPreset
                            assertEquals(expected, preset.group)
                            assertEquals("The popup retained an unused semicolon", semicolon,
                                ";" in resolveGroupedPopupPresetCandidates(preset.group, preset.corner))
                        }
                    } finally {
                        scene.keyboard.popupActionListener = null
                    }
                }
            }
        } finally {
            try {
                CustomShuangpinSchemeStore.save(original).getOrThrow()
            } finally {
                entryDir?.listFiles().orEmpty().filter {
                    it.isFile && it !in backups &&
                        ShuangpinLayoutKeys.isCustomSchemeUniqueName(it.name.removeSuffix(".conf"))
                }.forEach { assertTrue("Could not remove temporary scheme entry", it.delete()) }
                backups.forEach { (file, bytes) ->
                    if (bytes != null) file.writeBytes(bytes)
                    else if (file.exists()) assertTrue("Could not restore absent config", file.delete())
                }
            }
        }
    }

    private fun assertGroupLabels(keyboard: TextKeyboard, uppercase: Boolean, semicolon: Boolean) {
        val expected = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk",
            if (semicolon) "l;" else "l", "z", "xc", "v", "bn", "m")
            .map { if (uppercase) it.uppercase() else it }
        val actual = keyboard.allViews.filterIsInstance<AltTextKeyView>()
            .filter { (it.def as KeyDef.Appearance.AltText).character in Shuangpin18Keys.groups ||
                (!semicolon && (it.def as KeyDef.Appearance.AltText).character == "l") }
            .map { it.mainText.text.toString() }.toList()
        assertEquals("Every grouped letter must follow the display preference", expected, actual)
    }

    private data class Scene(
        val activity: MainActivity,
        val keyboard: TextKeyboard,
        val actions: MutableList<KeyAction>,
    )

    private fun withKeyboard(uniqueName: String = XIAOHE, block: (Scene) -> Unit) {
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
                prefs.internal.compactShuangpinInputMethods.setValue(compact + uniqueName)
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
                val keyboard = TextKeyboard(host, ThemePreset.MaterialLight, entry(uniqueName, "pinyin"))
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
        assertTrue("The grouped we key is missing", "we" in labels(keyboard))
        assertFalse("The uppercase 26-key layout was retained", "W" in labels(keyboard))
    }

    private fun assertSeparatorKeys(actions: List<KeyAction>, taps: Int = 1) {
        assertTrue("Separator taps emitted a non-symbol action", actions.all { it is KeyAction.SymAction })
        val symbols = actions.filterIsInstance<KeyAction.SymAction>()
        val modeSymbols = listOf(false, true).map { Shuangpin18Keys.modeAction(it).sym.sym }
        assertTrue("Separator taps emitted an unrelated symbol", symbols.all {
            it.sym.sym == FcitxKeyMapping.FcitxKey_apostrophe || it.sym.sym in modeSymbols
        })
        val keys = symbols.filter { it.sym.sym == FcitxKeyMapping.FcitxKey_apostrophe }
        assertEquals(taps, keys.size)
        assertTrue(keys.all { it.states.has(KeyState.Virtual) })
        assertTrue(keys.none { it.states.has(KeyState.Shift) || it.states.has(KeyState.CapsLock) })
    }

    private fun assertUppercase26(keyboard: TextKeyboard) {
        val letters = labels(keyboard).filter { it.length == 1 && it[0].lowercaseChar() in 'a'..'z' }
        assertEquals("The temporary layout must render all 26 uppercase letters",
            ('A'..'Z').map(Char::toString), letters.sorted())
        assertFalse("A grouped key survived uppercase mode", labels(keyboard).any { it.equals("we", ignoreCase = true) })
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
