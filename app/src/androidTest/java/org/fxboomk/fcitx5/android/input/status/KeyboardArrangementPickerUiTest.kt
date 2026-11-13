/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.bar.ui.ToolButton
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.MemoryConfigProvider
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.fxboomk.fcitx5.android.input.keyboard.AltTextKeyView
import org.fxboomk.fcitx5.android.input.keyboard.BaseKeyboard
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinLayoutVariants
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog.TextKeyboardLayoutProfilePickerDialog
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class KeyboardArrangementPickerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun previewsScrollAndSelectingACardMovesTheOnlyCheckWithoutChangingProfile() = withActivity { activity ->
        val prefs = AppPrefs.getInstance()
        val profile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val provider = ConfigProviders.provider
        prefs.internal.compactShuangpinInputMethods.setValue(setOf("shuangpin-ms"))
        lateinit var ui: KeyboardArrangementPickerUi
        ui = KeyboardArrangementPickerUi(ContextThemeWrapper(activity, R.style.Theme_InputViewTheme),
            ThemePreset.MaterialLight) { entry, layout ->
            ShuangpinKeyboardLayouts.write(entry.uniqueName, layout)
            ui.setSelected(layout)
        }
        ui.show(ime())
        layout(ui)
        assertSelected(ui, ShuangpinKeyboardLayout.Full)
        val full = bitmap(ui, ShuangpinKeyboardLayout.Full)
        val compact = bitmap(ui, ShuangpinKeyboardLayout.Compact18)
        assertTrue(full.width > 0 && full.height > 0)
        assertFalse("26-key and 18-key previews must differ", full.sameAs(compact))
        assertTrue("Preview images must contain a rendered keyboard", pixelColors(full).size > 2)
        assertTrue("Preview content must be wider than its viewport",
            ui.getChildAt(0).width > ui.width - ui.paddingLeft - ui.paddingRight)
        assertTrue("The previews must overflow horizontally", ui.canScrollHorizontally(1))
        ui.scrollTo(ui.getChildAt(0).width, 0)
        assertTrue("Other previews must be reachable", ui.scrollX > 0)
        assertTrue(ui.canScrollHorizontally(-1))
        assertTrue("Previews must expose images rather than interactive keyboard keys",
            ui.allViews.none { it is BaseKeyboard })
        assertSame(provider, ConfigProviders.provider)
        assertEquals(setOf("shuangpin-ms"), prefs.internal.compactShuangpinInputMethods.getValue())

        ui.cards.getValue(ShuangpinKeyboardLayout.Compact18).performClick()
        assertSelected(ui, ShuangpinKeyboardLayout.Compact18)
        assertEquals(setOf("shuangpin-ms", "shuangpin-xiaohe"),
            prefs.internal.compactShuangpinInputMethods.getValue())
        assertEquals(profile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
        ui.show(ime())
        layout(ui)
        assertSelected(ui, ShuangpinKeyboardLayout.Compact18)

        ui.cards.getValue(ShuangpinKeyboardLayout.Full).performClick()
        assertSelected(ui, ShuangpinKeyboardLayout.Full)
        assertEquals(setOf("shuangpin-ms"), prefs.internal.compactShuangpinInputMethods.getValue())
        assertEquals(profile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
        ui.clearPreviews()
    }

    @Test
    fun previewsReadCurrentProfileContentWithoutReplacingTheProviderOrActiveArrangement() = withActivity { activity ->
        val originalProvider = ConfigProviders.provider
        val originalArrangement = AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue()
        val data = LayoutDataManager(activity)
        val fullRows = data.defaultPresetSnapshot().map { row -> row.map { it.toMutableMap() }.toMutableList() }
        val compactRows = data.compactDefaultRows()
        val entries = mapOf("shuangpin-xiaohe" to fullRows,
            ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe") to compactRows)
        val firstProvider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(entries), originalProvider)
        ConfigProviders.provider = firstProvider
        TextKeyboard.clearCachedKeyDefLayouts()
        val ui = KeyboardArrangementPickerUi(activity, ThemePreset.MaterialLight) { _, _ ->
            fail("Rendering or scrolling a preview must not select a layout")
        }
        ui.show(ime())
        layout(ui)
        val beforeFull = bitmap(ui, ShuangpinKeyboardLayout.Full)
        val beforeCompact = bitmap(ui, ShuangpinKeyboardLayout.Compact18)
        assertSame(firstProvider, ConfigProviders.provider)

        // Move the first row's real keys in the current profile; compact content stays unchanged.
        fullRows.first().reverse()
        val secondProvider = MemoryConfigProvider(LayoutJsonUtils.convertToSaveJson(entries), originalProvider)
        ConfigProviders.provider = secondProvider
        TextKeyboard.clearCachedKeyDefLayouts()
        ui.show(ime())
        layout(ui)
        assertFalse("The full preview must follow current profile edits",
            beforeFull.sameAs(bitmap(ui, ShuangpinKeyboardLayout.Full)))
        assertTrue("Changing only the full arrangement must preserve the compact preview",
            beforeCompact.sameAs(bitmap(ui, ShuangpinKeyboardLayout.Compact18)))
        assertSame(secondProvider, ConfigProviders.provider)
        assertEquals(originalArrangement, AppPrefs.getInstance().internal.compactShuangpinInputMethods.getValue())
        ui.clearPreviews()
    }

    @Test
    fun profilePickerWorksFromTheImeThemeAndKeepsTheArrangement() = withActivity { activity ->
        val prefs = AppPrefs.getInstance()
        val originalArrangement = prefs.internal.compactShuangpinInputMethods.getValue()
        prefs.keyboard.textKeyboardLayoutProfile.setValue("status-area-test-${UUID.randomUUID()}")
        var selectedProfile: String? = null
        val themed = ContextThemeWrapper(activity, R.style.Theme_InputViewTheme)
        val dialog = TextKeyboardLayoutProfilePickerDialog.build(themed, onProfileSelected = { selectedProfile = it })
        dialog.show()
        try {
            dialog.listView.performItemClick(dialog.listView.getChildAt(0), 0, 0)
            assertEquals(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, selectedProfile)
            assertEquals(selectedProfile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
            assertEquals(originalArrangement, prefs.internal.compactShuangpinInputMethods.getValue())
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
        }
        assertTrue(KeyboardArrangementWindow.supports(ime()))
        assertFalse(KeyboardArrangementWindow.supports(ime("pinyin")))
        assertFalse(KeyboardArrangementWindow.supports(ime("rime", "rime")))
        assertFalse(KeyboardArrangementWindow.supports(ime(addon = "test")))
    }

    @Test
    fun statusAreaKeepsThePreviewPanelAndReturningUsesTheChosenArrangement() {
        val prefs = AppPrefs.getInstance()
        val originalArrangement = prefs.internal.compactShuangpinInputMethods.getValue()
        val connectionName = "KeyboardArrangementPickerUiTest"
        val connection = FcitxDaemon.connect(connectionName)
        val activity = instrumentation.startActivitySync(
            Intent(context, GestureEditingTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as GestureEditingTestActivity
        var originalIme: InputMethodEntry? = null
        var service: FcitxInputMethodService? = null
        var originalShiftLock: Boolean? = null
        try {
            lateinit var editor: EditText
            onMain {
                editor = EditText(activity).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText("preview selection preserves editor")
                }
                activity.setContentView(editor)
                editor.requestFocus()
            }
            await { editor.isShown && editor.hasWindowFocus() }
            onMain { activity.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            await { runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true }
            val activeService = requireNotNull(runningService()).also { service = it }
            originalShiftLock = activeService.isVirtualShiftLockOn()
            originalIme = connection.runImmediately { inputMethodEntryCached }
            val target = connection.runImmediately { enabledIme() }.first { KeyboardArrangementWindow.supports(it) }
            connection.runImmediately { activateIme(target.uniqueName) }
            await { connection.runImmediately { inputMethodEntryCached.uniqueName } == target.uniqueName }
            val wm = windowManager(activeService)
            onMain {
                activeService.setVirtualShiftLockState(true)
                activeService.inputView!!.executeButtonAction("more")
            }
            await { (wm.currentWindowOrNull() as? StatusAreaWindow)?.view?.adapter?.let { adapter ->
                (adapter as StatusAreaAdapter).entries.any {
                    it is StatusAreaEntry.ActionEntry && it.buttonAction.id == "keyboard_arrangement"
                }
            } == true }
            onMain {
                val status = wm.currentWindowOrNull() as StatusAreaWindow
                val adapter = status.view.adapter as StatusAreaAdapter
                val entry = adapter.entries.filterIsInstance<StatusAreaEntry.ActionEntry>()
                    .single { it.buttonAction.id == "keyboard_arrangement" }
                adapter.onItemClick(status.view, entry)
            }
            await { wm.currentWindowOrNull() is KeyboardArrangementWindow }
            val ui = (wm.currentWindowOrNull() as KeyboardArrangementWindow).onCreateView() as KeyboardArrangementPickerUi
            await { ui.cards.size == 2 }
            onMain {
                assertFalse("Virtual Shift-lock must not turn the 18-key preview into a 26-key image",
                    bitmap(ui, ShuangpinKeyboardLayout.Full).sameAs(bitmap(ui, ShuangpinKeyboardLayout.Compact18)))
                ui.cards.getValue(ShuangpinKeyboardLayout.Compact18).performClick()
                assertTrue(wm.currentWindowOrNull() is KeyboardArrangementWindow)
                assertSelected(ui, ShuangpinKeyboardLayout.Compact18)
                assertEquals("preview selection preserves editor", editor.text.toString())
                activeService.setVirtualShiftLockState(false)
            }
            returnToKeyboard(activeService)
            await { hasGroupKeys(activeService) }
            onMain { activeService.inputView!!.executeButtonAction("keyboard_arrangement") }
            await { wm.currentWindowOrNull() is KeyboardArrangementWindow }
            await { ((wm.currentWindowOrNull() as KeyboardArrangementWindow).onCreateView()
                as KeyboardArrangementPickerUi).cards.size == 2 }
            onMain {
                val reopened = (wm.currentWindowOrNull() as KeyboardArrangementWindow).onCreateView() as KeyboardArrangementPickerUi
                assertSelected(reopened, ShuangpinKeyboardLayout.Compact18)
                reopened.cards.getValue(ShuangpinKeyboardLayout.Full).performClick()
                assertSelected(reopened, ShuangpinKeyboardLayout.Full)
            }
            returnToKeyboard(activeService)
            await { !hasGroupKeys(activeService) }
            onMain { assertEquals("preview selection preserves editor", editor.text.toString()) }
        } finally {
            originalIme?.let { entry -> connection.runImmediately { activateIme(entry.uniqueName) } }
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(originalArrangement)
                originalShiftLock?.let { locked -> service?.setVirtualShiftLockState(locked) }
                service?.let { (windowManager(it).getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards() }
                activity.finish()
            }
            FcitxDaemon.disconnect(connectionName)
            instrumentation.waitForIdleSync()
        }
    }

    private fun assertSelected(ui: KeyboardArrangementPickerUi, expected: ShuangpinKeyboardLayout) {
        assertEquals(listOf(expected), ui.cards.filterValues { it.isSelected }.keys.toList())
        ui.cards.forEach { (layout, card) ->
            val check = card.children.filterIsInstance<ImageView>().last()
            assertEquals(if (layout == expected) View.VISIBLE else View.INVISIBLE, check.visibility)
            assertEquals(Gravity.TOP or Gravity.END, (check.layoutParams as FrameLayout.LayoutParams).gravity)
        }
    }

    private fun bitmap(ui: KeyboardArrangementPickerUi, layout: ShuangpinKeyboardLayout): Bitmap =
        (ui.cards.getValue(layout).children.filterIsInstance<ImageView>().first().drawable as BitmapDrawable).bitmap

    private fun pixelColors(bitmap: Bitmap): Set<Int> = buildSet {
        for (y in 0 until bitmap.height step 13) for (x in 0 until bitmap.width step 13) add(bitmap.getPixel(x, y))
    }

    private fun layout(view: View) {
        repeat(2) {
            // onSizeChanged adds the cards after the initial measure; emulate the next traversal.
            view.requestLayout()
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 720)
        }
    }

    private fun ime(name: String = "shuangpin-xiaohe", addon: String = "pinyin") =
        InputMethodEntry(name, name, "", "", "", "zh", addon, false)

    private fun withActivity(block: (MainActivity) -> Unit) {
        val prefs = AppPrefs.getInstance()
        val original = prefs.internal.compactShuangpinInputMethods.getValue()
        val originalProfile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val originalProvider = ConfigProviders.provider
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            onMain { block(activity) }
        } finally {
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(original)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(originalProfile)
                ConfigProviders.provider = originalProvider
                TextKeyboard.clearCachedKeyDefLayouts()
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun windowManager(service: FcitxInputMethodService): InputWindowManager =
        service.inputView!!.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
            .get(service.inputView) as InputWindowManager

    private fun runningService(): FcitxInputMethodService? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun hasGroupKeys(service: FcitxInputMethodService): Boolean =
        service.inputView!!.allViews.filterIsInstance<AltTextKeyView>().any {
            (it.def as? KeyDef.Appearance.AltText)?.isLetterGroup == true
        }

    private fun returnToKeyboard(service: FcitxInputMethodService) {
        onMain {
            service.inputView!!.allViews.filterIsInstance<ToolButton>().single {
                it.isShown && it.contentDescription == context.getString(R.string.back_to_keyboard)
            }.performClick()
        }
        await { windowManager(service).currentWindowOrNull() is KeyboardWindow }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            onMain { result = condition() }
            if (result) return
            SystemClock.sleep(50)
        }
        onMain { assertTrue("Timed out waiting for keyboard arrangement UI", condition()) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
