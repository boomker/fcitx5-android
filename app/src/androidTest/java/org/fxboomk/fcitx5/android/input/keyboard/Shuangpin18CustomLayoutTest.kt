/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.keyboard

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.MemoryConfigProvider
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Test

class Shuangpin18CustomLayoutTest {
    @Test
    fun sixColumnsRenderAndKeepNativeIdentityAfterMovingAndCaps() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefs = AppPrefs.getInstance()
        val originalCompact = prefs.internal.compactShuangpinInputMethods.getValue()
        val originalUppercase = prefs.keyboard.keepLettersUppercase.getValue()
        val originalCapsBehavior = prefs.keyboard.capsKeyBehavior.getValue()
        val originalSideKeys = ThemeManager.prefs.gboardStyleSideKeys.getValue()
        val originalProvider = ConfigProviders.provider
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            val host = activity
            instrumentation.runOnMainSync {
                prefs.internal.compactShuangpinInputMethods.setValue(originalCompact + "shuangpin-xiaohe")
                prefs.keyboard.keepLettersUppercase.setValue(false)
                prefs.keyboard.capsKeyBehavior.setValue(CapsKeyBehavior.Default)
                ThemeManager.prefs.gboardStyleSideKeys.setValue(true)
                val data = LayoutDataManager(host)
                val rows = data.compactDefaultRows(sixColumns = true)
                val moved = rows[0][1]
                rows[0][1] = rows[2][4]
                rows[2][4] = moved
                val originalOrder = rows.take(3).flatten().map { it["groupId"] }
                val entryKey = ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe")
                ConfigProviders.provider = MemoryConfigProvider(
                    LayoutJsonUtils.convertToSaveJson(mapOf(entryKey to rows)), originalProvider
                )
                TextKeyboard.clearCachedKeyDefLayouts()
                val ime = InputMethodEntry(
                    "shuangpin-xiaohe", "小鹤双拼", "", "", "双", "zh", "pinyin", false
                )
                val actions = mutableListOf<KeyAction>()
                val splitManager = SplitKeyboardStateManager.getInstance()
                val listeners = splitManager.javaClass.getDeclaredField("listeners").apply { isAccessible = true }
                    .get(splitManager) as List<*>
                val originalListenerCount = listeners.size
                val keyboard = TextKeyboard(host, ThemePreset.MaterialLight, ime)
                assertEquals("An unattached preview leaked a split listener", originalListenerCount, listeners.size)
                keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
                host.setContentView(keyboard)
                assertEquals(originalListenerCount + 1, listeners.size)
                keyboard.onAttach()

                for ((width, height) in listOf(400 to 320, 800 to 210)) {
                    layout(keyboard, width, height)
                    val groups = groupViews(keyboard)
                    assertEquals(18, groups.size)
                    assertEquals(originalOrder.map { if (it == "l;") "l" else it },
                        groups.map { (it.def as KeyDef.Appearance.AltText).character })
                    assertTrue("Empty key at ${width}x$height: ${groups.map { "${(it.def as KeyDef.Appearance.AltText).character}=${it.width}x${it.height}" }}",
                        groups.all { it.width > 0 && it.height > 0 })
                    assertEquals(listOf(6, 6, 6), groups.groupBy { key ->
                        val position = IntArray(2)
                        key.getLocationInWindow(position)
                        position[1]
                    }.values.map { it.size })
                    assertTrue(keyboard.allViews.filterIsInstance<KeyView>()
                        .single { it.def.viewId == R.id.button_space }.width > 0)
                    val returnKey = keyboard.allViews.filterIsInstance<KeyView>()
                        .single { it.def.viewId == R.id.button_return }
                    val appearance = (returnKey as ViewGroup).getChildAt(0)
                    val background = appearance.background
                    layout(keyboard, width, height)
                    assertSame("Repeated layout recreated the side-key background", background, appearance.background)
                }

                groupViews(keyboard).single {
                    (it.def as KeyDef.Appearance.AltText).character == "we"
                }.performClick()
                assertEquals(1, Shuangpin18Keys.indexOf(actions.filterIsInstance<KeyAction.SymAction>().last().sym))
                val capsWidth = keyboard.allViews.filterIsInstance<KeyView>()
                    .single { it.def.viewId == R.id.button_caps }.width
                keyboard.onCompositionStateChanged(true)
                layout(keyboard, 800, 210)
                assertEquals(capsWidth, keyboard.allViews.filterIsInstance<TextKeyView>()
                    .single { it.mainText.text.toString() == "'词" }.width)
                keyboard.onCompositionStateChanged(false)
                keyboard.allViews.filterIsInstance<KeyView>()
                    .single { it.def.viewId == R.id.button_caps }.performClick()
                layout(keyboard, 400, 320)
                assertTrue(groupViews(keyboard).isEmpty())
                keyboard.allViews.filterIsInstance<TextKeyView>()
                    .single { it.mainText.text.toString() == "W" }.performClick()
                layout(keyboard, 400, 320)
                assertEquals(originalOrder.map { if (it == "l;") "l" else it },
                    groupViews(keyboard).map { (it.def as KeyDef.Appearance.AltText).character })
                host.setContentView(FrameLayout(host))
                assertEquals("A removed preview retained its Activity", originalListenerCount, listeners.size)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                ConfigProviders.provider = originalProvider
                TextKeyboard.clearCachedKeyDefLayouts()
                prefs.internal.compactShuangpinInputMethods.setValue(originalCompact)
                prefs.keyboard.keepLettersUppercase.setValue(originalUppercase)
                prefs.keyboard.capsKeyBehavior.setValue(originalCapsBehavior)
                ThemeManager.prefs.gboardStyleSideKeys.setValue(originalSideKeys)
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun groupViews(keyboard: TextKeyboard): List<AltTextKeyView> =
        keyboard.allViews.filterIsInstance<AltTextKeyView>().filter {
            (it.def as KeyDef.Appearance.AltText).isLetterGroup
        }.toList()

    private fun layout(keyboard: TextKeyboard, widthDp: Int, heightDp: Int) {
        val density = keyboard.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        // A width change can rebuild the split-keyboard rows from onSizeChanged.
        // Drive the next traversal explicitly instead of relying on a Choreographer
        // frame while this test is still occupying the main thread.
        repeat(3) {
            keyboard.allViews.forEach { it.forceLayout() }
            keyboard.forceLayout()
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            keyboard.layout(0, 0, width, height)
        }
    }
}
